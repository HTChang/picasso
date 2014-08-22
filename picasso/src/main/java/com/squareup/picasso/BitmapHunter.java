/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static android.content.ContentResolver.SCHEME_ANDROID_RESOURCE;
import static android.content.ContentResolver.SCHEME_CONTENT;
import static android.content.ContentResolver.SCHEME_FILE;
import static android.provider.ContactsContract.Contacts;
import static com.squareup.picasso.AssetBitmapHunter.ANDROID_ASSET;
import static com.squareup.picasso.FaceDetector.Face;
import static com.squareup.picasso.Picasso.LoadedFrom.CUSTOMDISK;
import static com.squareup.picasso.Picasso.LoadedFrom.DISK;
import static com.squareup.picasso.Picasso.LoadedFrom.MEMORY;
import static com.squareup.picasso.Utils.OWNER_HUNTER;
import static com.squareup.picasso.Utils.VERB_DECODED;
import static com.squareup.picasso.Utils.VERB_EXECUTING;
import static com.squareup.picasso.Utils.VERB_JOINED;
import static com.squareup.picasso.Utils.VERB_REMOVED;
import static com.squareup.picasso.Utils.VERB_TRANSFORMED;
import static com.squareup.picasso.Utils.getLogIdsForHunter;
import static com.squareup.picasso.Utils.log;

abstract class BitmapHunter implements Runnable {

  /**
   * Global lock for bitmap decoding to ensure that we are only are decoding one at a time. Since
   * this will only ever happen in background threads we help avoid excessive memory thrashing as
   * well as potential OOMs. Shamelessly stolen from Volley.
   */
  private static final Object DECODE_LOCK = new Object();

  private static final ThreadLocal<StringBuilder> NAME_BUILDER = new ThreadLocal<StringBuilder>() {
    @Override protected StringBuilder initialValue() {
      return new StringBuilder(Utils.THREAD_PREFIX);
    }
  };

  final Picasso picasso;
  final Dispatcher dispatcher;
  final Cache cache;
  final Cache diskCache;
  final Stats stats;
  final String key;
  final Request data;
  final boolean fromCacheOnly;
  final boolean skipMemoryCache;
  final boolean skipDiskCache;

  Action action;
  List<Action> actions;
  Bitmap result;
  Future<?> future;
  Picasso.LoadedFrom loadedFrom;
  Exception exception;
  int exifRotation; // Determined during decoding of original resource.

  BitmapHunter(Picasso picasso, Dispatcher dispatcher, Cache cache, Cache diskCache, Stats stats,
      Action action) {
    this.picasso = picasso;
    this.dispatcher = dispatcher;
    this.cache = cache;
    this.diskCache = diskCache;
    this.stats = stats;
    this.key = action.getKey();
    this.data = action.getRequest();
    this.fromCacheOnly = action.fromCacheOnly;
    this.skipMemoryCache = action.skipCache;
    this.skipDiskCache = action.skipDiskCache;
    this.action = action;
  }

  protected void setExifRotation(int exifRotation) {
    this.exifRotation = exifRotation;
  }

  @Override public void run() {
    try {
      updateThreadName(data);

      if (picasso.loggingEnabled) {
        log(OWNER_HUNTER, VERB_EXECUTING, getLogIdsForHunter(this));
      }

      result = hunt();

      if (result == null) {
        dispatcher.dispatchFailed(this);
      } else {
        dispatcher.dispatchComplete(this);
      }
    } catch (Downloader.ResponseException e) {
      exception = e;
      dispatcher.dispatchFailed(this);
    } catch (IOException e) {
      exception = e;
      dispatcher.dispatchRetry(this);
    } catch (OutOfMemoryError e) {
      StringWriter writer = new StringWriter();
      stats.createSnapshot().dump(new PrintWriter(writer));
      exception = new RuntimeException(writer.toString(), e);
      dispatcher.dispatchFailed(this);
    } catch (Exception e) {
      exception = e;
      dispatcher.dispatchFailed(this);
    } finally {
      Thread.currentThread().setName(Utils.THREAD_IDLE_NAME);
    }
  }

  abstract Bitmap decode(Request data) throws IOException;

  Bitmap hunt() throws IOException {
    Bitmap bitmap = null;

    if (!skipMemoryCache) {
      bitmap = cache.get(key);
      if (bitmap != null) {
        stats.dispatchCacheHit();
        loadedFrom = MEMORY;
        if (picasso.loggingEnabled) {
          log(OWNER_HUNTER, VERB_DECODED, data.logId(), "from cache");
        }
        return bitmap;
      }
    }

    if (!skipDiskCache) {
      if (diskCache != null) {
        bitmap = diskCache.get(key);
        if (bitmap != null) {
          stats.dispatchDiskCacheHit();
          loadedFrom = CUSTOMDISK;
          if (picasso.loggingEnabled) {
            log(OWNER_HUNTER, VERB_DECODED, data.logId(), "from disk");
          }
          return bitmap;
        }
        stats.dispatchDiskCacheMiss();
      }
    }

    if (!fromCacheOnly) {
      bitmap = decode(data);
    }

    if (bitmap != null) {
      if (picasso.loggingEnabled) {
        log(OWNER_HUNTER, VERB_DECODED, data.logId());
      }
      stats.dispatchBitmapDecoded(bitmap);
      if (data.needsTransformation() || exifRotation != 0) {
        synchronized (DECODE_LOCK) {
          if (data.needsMatrixTransform() || exifRotation != 0) {
            bitmap = transformResult(data, bitmap, exifRotation);
            if (picasso.loggingEnabled) {
              log(OWNER_HUNTER, VERB_TRANSFORMED, data.logId());
            }
          }
          if (data.hasCustomTransformations()) {
            bitmap = applyCustomTransformations(data.transformations, bitmap);
            if (picasso.loggingEnabled) {
              log(OWNER_HUNTER, VERB_TRANSFORMED, data.logId(), "from custom transformations");
            }
          }
        }
        if (bitmap != null) {
          stats.dispatchBitmapTransformed(bitmap);
        }
      }
      if (diskCache != null) {
        if (getLoadedFrom() != DISK) {
          diskCache.set(key, bitmap);
        }
      }
    }

    return bitmap;
  }

  void attach(Action action) {
    boolean loggingEnabled = picasso.loggingEnabled;
    Request request = action.request;

    if (this.action == null) {
      this.action = action;
      if (loggingEnabled) {
        if (actions == null || actions.isEmpty()) {
          log(OWNER_HUNTER, VERB_JOINED, request.logId(), "to empty hunter");
        } else {
          log(OWNER_HUNTER, VERB_JOINED, request.logId(), getLogIdsForHunter(this, "to "));
        }
      }
      return;
    }

    if (actions == null) {
      actions = new ArrayList<Action>(3);
    }

    actions.add(action);

    if (loggingEnabled) {
      log(OWNER_HUNTER, VERB_JOINED, request.logId(), getLogIdsForHunter(this, "to "));
    }
  }

  void detach(Action action) {
    if (this.action == action) {
      this.action = null;
    } else if (actions != null) {
      actions.remove(action);
    }

    if (picasso.loggingEnabled) {
      log(OWNER_HUNTER, VERB_REMOVED, action.request.logId(), getLogIdsForHunter(this, "from "));
    }
  }

  boolean cancel() {
    return action == null
        && (actions == null || actions.isEmpty())
        && future != null
        && future.cancel(false);
  }

  boolean isCancelled() {
    return future != null && future.isCancelled();
  }

  boolean shouldSkipMemoryCache() {
    return skipMemoryCache;
  }

  boolean shouldSkipDiskCache() {
    return skipDiskCache;
  }

  boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
    return false;
  }

  boolean supportsReplay() {
    return false;
  }

  Bitmap getResult() {
    return result;
  }

  String getKey() {
    return key;
  }

  Request getData() {
    return data;
  }

  Action getAction() {
    return action;
  }

  Picasso getPicasso() {
    return picasso;
  }

  List<Action> getActions() {
    return actions;
  }

  Exception getException() {
    return exception;
  }

  Picasso.LoadedFrom getLoadedFrom() {
    return loadedFrom;
  }

  static void updateThreadName(Request data) {
    String name = data.getName();

    StringBuilder builder = NAME_BUILDER.get();
    builder.ensureCapacity(Utils.THREAD_PREFIX.length() + name.length());
    builder.replace(Utils.THREAD_PREFIX.length(), builder.length(), name);

    Thread.currentThread().setName(builder.toString());
  }

  static BitmapHunter forRequest(Context context, Picasso picasso, Dispatcher dispatcher,
      Cache cache, Cache diskCache, Stats stats, Action action, Downloader downloader) {
    if (action.getRequest().resourceId != 0) {
      return new ResourceBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats,
          action);
    }
    Uri uri = action.getRequest().uri;
    String scheme = uri.getScheme();
    if (SCHEME_CONTENT.equals(scheme)) {
      if (Contacts.CONTENT_URI.getHost().equals(uri.getHost()) //
          && !uri.getPathSegments().contains(Contacts.Photo.CONTENT_DIRECTORY)) {
        return new ContactsPhotoBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats,
            action);
      } else if (MediaStore.AUTHORITY.equals(uri.getAuthority())) {
        return new MediaStoreBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats,
            action);
      } else {
        return new ContentStreamBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats,
            action);
      }
    } else if (SCHEME_FILE.equals(scheme)) {
      if (!uri.getPathSegments().isEmpty() && ANDROID_ASSET.equals(uri.getPathSegments().get(0))) {
        return new AssetBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats, action);
      }
      return new FileBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats, action);
    } else if (SCHEME_ANDROID_RESOURCE.equals(scheme)) {
      return new ResourceBitmapHunter(context, picasso, dispatcher, cache, diskCache, stats,
          action);
    } else {
      return new NetworkBitmapHunter(picasso, dispatcher, cache, diskCache, stats, action,
          downloader);
    }
  }

  /**
   * Lazily create {@link android.graphics.BitmapFactory.Options} based in given
   * {@link com.squareup.picasso.Request}, only instantiating them if needed.
   */
  static BitmapFactory.Options createBitmapOptions(Request data) {
    final boolean justBounds = data.hasSize();
    final boolean hasConfig = data.config != null;
    BitmapFactory.Options options = null;
    if (justBounds || hasConfig) {
      options = new BitmapFactory.Options();
      options.inJustDecodeBounds = justBounds;
      if (hasConfig) {
        options.inPreferredConfig = data.config;
      }
    }
    return options;
  }

  static boolean requiresInSampleSize(BitmapFactory.Options options) {
    return options != null && options.inJustDecodeBounds;
  }

  static void calculateInSampleSize(int reqWidth, int reqHeight, BitmapFactory.Options options) {
    calculateInSampleSize(reqWidth, reqHeight, options.outWidth, options.outHeight, options);
  }

  static void calculateInSampleSize(int reqWidth, int reqHeight, int width, int height,
      BitmapFactory.Options options) {
    int sampleSize = 1;
    if (height > reqHeight || width > reqWidth) {
      final int heightRatio = (int) Math.floor((float) height / (float) reqHeight);
      final int widthRatio = (int) Math.floor((float) width / (float) reqWidth);
      sampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
    }
    options.inSampleSize = sampleSize;
    options.inJustDecodeBounds = false;
  }

  static Bitmap applyCustomTransformations(List<Transformation> transformations, Bitmap result) {
    for (int i = 0, count = transformations.size(); i < count; i++) {
      final Transformation transformation = transformations.get(i);
      Bitmap newResult = transformation.transform(result);

      if (newResult == null) {
        final StringBuilder builder = new StringBuilder() //
            .append("Transformation ")
            .append(transformation.key())
            .append(" returned null after ")
            .append(i)
            .append(" previous transformation(s).\n\nTransformation list:\n");
        for (Transformation t : transformations) {
          builder.append(t.key()).append('\n');
        }
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new NullPointerException(builder.toString());
          }
        });
        return null;
      }

      if (newResult == result && result.isRecycled()) {
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new IllegalStateException("Transformation "
                + transformation.key()
                + " returned input Bitmap but recycled it.");
          }
        });
        return null;
      }

      // If the transformation returned a new bitmap ensure they recycled the original.
      if (newResult != result && !result.isRecycled()) {
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new IllegalStateException("Transformation "
                + transformation.key()
                + " mutated input Bitmap but failed to recycle the original.");
          }
        });
        return null;
      }

      result = newResult;
    }
    return result;
  }

  static Bitmap transformResult(Request data, Bitmap result, int exifRotation) {
    int inWidth = result.getWidth();
    int inHeight = result.getHeight();

    DrawDimens drawDimens = new DrawDimens(0, 0, inWidth, inHeight);
    Matrix matrix = new Matrix();

    if (data.needsMatrixTransform()) {
      int targetWidth = data.targetWidth;
      int targetHeight = data.targetHeight;

      float targetRotation = data.rotationDegrees;
      if (targetRotation != 0) {
        if (data.hasRotationPivot) {
          matrix.setRotate(targetRotation, data.rotationPivotX, data.rotationPivotY);
        } else {
          matrix.setRotate(targetRotation);
        }
      }

      if (data.centerCrop) {
        centerCrop(targetWidth, targetHeight, inWidth, inHeight, drawDimens, matrix,
            new Point(inWidth / 2, inHeight / 2));
      } else if (data.centerInside) {
        float widthRatio = targetWidth / (float) inWidth;
        float heightRatio = targetHeight / (float) inHeight;
        float scale = widthRatio < heightRatio ? widthRatio : heightRatio;
        matrix.preScale(scale, scale);
      } else if (data.faceCenterCrop) {
        // Transform bitmap to RGB565 format and make sure width is even
        // those are requirements for android face detector
        Bitmap convertedBitmap = null;
        List<Face> faces = null;
        try {
          if ((result.getConfig() != Bitmap.Config.RGB_565 || result.getWidth() % 2 != 0)
              && data.faceDetector instanceof AndroidFaceDetector) {
            convertedBitmap = convertToMeetAndroidFaceDetection(result);
          }
          faces =
              data.faceDetector.findFaces(convertedBitmap != null ? convertedBitmap : result, 5);
        } finally {
          if (convertedBitmap != null) {
            convertedBitmap.recycle();
          }
        }
        if (faces == null || faces.size() <= 0) {
          centerCrop(targetWidth, targetHeight, inWidth, inHeight, drawDimens, matrix,
              new Point(inWidth / 2, inHeight / 2));
        } else {
          Point faceGravityPoint = new Point();
          for (Face face : faces) {
            faceGravityPoint.x += face.leftTopPoint.x + face.width / 2;
            faceGravityPoint.y += face.leftTopPoint.y + face.height / 2;
          }
          faceGravityPoint.x /= faces.size();
          faceGravityPoint.y /= faces.size();
          centerCrop(targetWidth, targetHeight, inWidth, inHeight, drawDimens, matrix,
              faceGravityPoint);
        }
      } else if (targetWidth != 0 && targetHeight != 0 //
          && (targetWidth != inWidth || targetHeight != inHeight)) {
        // If an explicit target size has been specified and they do not match the results bounds,
        // pre-scale the existing matrix appropriately.
        float sx = targetWidth / (float) inWidth;
        float sy = targetHeight / (float) inHeight;
        matrix.preScale(sx, sy);
      }
    }

    if (exifRotation != 0) {
      matrix.preRotate(exifRotation);
    }

    Bitmap newResult =
        Bitmap.createBitmap(result, drawDimens.drawX, drawDimens.drawY, drawDimens.drawWidth,
            drawDimens.drawHeight, matrix, true);
    if (newResult != result) {
      result.recycle();
      result = newResult;
    }

    return result;
  }

  private static void centerCrop(int targetWidth, int targetHeight, int inWidth, int inHeight,
      DrawDimens drawDimens, Matrix matrix, Point center) {
    float widthRatio = targetWidth / (float) inWidth;
    float heightRatio = targetHeight / (float) inHeight;
    float scale;
    if (widthRatio > heightRatio) {
      scale = widthRatio;
      int newSize = (int) Math.ceil(inHeight * (heightRatio / widthRatio));
      int drawY = center.y - (newSize / 2);
      drawDimens.drawY = ensureMargin(drawY, inHeight, newSize);
      drawDimens.drawHeight = newSize;
    } else {
      scale = heightRatio;
      int newSize = (int) Math.ceil(inWidth * (widthRatio / heightRatio));
      int drawX = center.x - (newSize / 2);
      drawDimens.drawX = ensureMargin(drawX, inWidth, newSize);
      drawDimens.drawWidth = newSize;
    }
    matrix.preScale(scale, scale);
  }

  /**
   * Ensure the boundary
   */
  private static int ensureMargin(int start, int end, int distance) {
    if (start < 0) {
      return 0;
    } else if (start >= 0 && (start + distance) <= end) {
      return start;
    } else {
      return end - distance;
    }
  }

  /**
   * Convert the given bitmap to the requirements that meet Android's face detector
   * Note that the original bitmap would not be recycled.
   */
  private static Bitmap convertToMeetAndroidFaceDetection(Bitmap bitmap) {
    Bitmap convertedBitmap = null;
    if (bitmap == null) {
      return convertedBitmap;
    }
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    // the width of the bitmap must be even
    if (width % 2 != 0) {
      width--;
    }
    Rect roi = new Rect(0, 0, width, height);
    convertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
    Canvas canvas = new Canvas(convertedBitmap);
    Paint paint = new Paint();
    paint.setColor(Color.BLACK);
    canvas.drawBitmap(bitmap, roi, roi, paint);

    return convertedBitmap;
  }

  private static class DrawDimens {
    private int drawX;
    private int drawY;
    private int drawWidth;
    private int drawHeight;

    DrawDimens(int drawX, int drawY, int drawWidth, int drawHeight) {
      this.drawX = drawX;
      this.drawY = drawY;
      this.drawWidth = drawWidth;
      this.drawHeight = drawHeight;
    }
  }
}

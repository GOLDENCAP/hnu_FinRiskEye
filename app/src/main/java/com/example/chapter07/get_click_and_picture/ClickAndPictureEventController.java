package com.example.chapter07.get_click_and_picture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 独立事件类：
 * 1. 拦截全屏点击；
 * 2. 记录点击时刻周围截图；
 * 3. 延迟 0.5 秒后临时移除悬浮层，让原始点击自然落到下层界面；
 * 4. 将图片与点击记录一一对应保存到自定义文件夹数据库中。
 *
 * 说明：
 * - 该类只放在 get_click_and_picture 包内，不依赖修改外部代码；
 * - 调用方只需准备好 MediaProjection 授权结果并调用 start()；
 * - 若要停止监听，调用 stop()。
 */
public class ClickAndPictureEventController {

    public interface EventListener {
        void onEventStored(ClickPictureEventStore.ClickPictureRecord record);
        void onError(String message, Throwable throwable);
        void onPassThroughStateChanged(boolean passThroughEnabled);
    }

    private static final String TAG = "ClickAndPictureCtrl";
    private static final long PASS_THROUGH_DELAY_MS = 500L;
    private static final int HALF_CROP_SIZE = 50;
    private static final int CLICK_MOVE_THRESHOLD = 20;
    private static final long CLICK_DURATION_THRESHOLD_MS = 500L;

    private final Context appContext;
    private final WindowManager windowManager;
    private final Handler mainHandler;
    private final ClickPictureEventStore eventStore;
    private final EventListener eventListener;
    private final int screenWidth;
    private final int screenHeight;
    private final int screenDensity;
    private final ImageReader imageReader;
    private final MediaProjection mediaProjection;

    private View overlayView;
    private boolean overlayAttached;
    private boolean passThroughEnabled;
    private boolean projectionStarted;
    private VirtualDisplay virtualDisplay;

    public ClickAndPictureEventController(Context context,
                                          MediaProjectionManager projectionManager,
                                          int resultCode,
                                          android.content.Intent resultData,
                                          String customFolderName,
                                          EventListener eventListener) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.eventStore = new ClickPictureEventStore(appContext, customFolderName);
        this.eventListener = eventListener;

        DisplayMetrics metrics = new DisplayMetrics();
        Display display = windowManager.getDefaultDisplay();
        display.getRealMetrics(metrics);
        this.screenWidth = metrics.widthPixels;
        this.screenHeight = metrics.heightPixels;
        this.screenDensity = metrics.densityDpi;

        this.imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        Log.d(TAG, "constructor: screen=" + screenWidth + "x" + screenHeight
                + ", density=" + screenDensity
                + ", folder=" + customFolderName
                + ", resultCode=" + resultCode
                + ", hasResultData=" + (resultData != null));
        this.mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
        Log.d(TAG, "constructor: mediaProjection=" + mediaProjection);
        if (this.mediaProjection == null) {
            throw new IllegalStateException("无法创建 MediaProjection，请确认授权结果有效");
        }
    }

    public synchronized void start() {
        Log.d(TAG, "start: projectionStarted=" + projectionStarted + ", overlayAttached=" + overlayAttached);
        if (projectionStarted) {
            return;
        }
        ensureOverlayPermission();
        Log.d(TAG, "start: overlay permission ok, creating VirtualDisplay");
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "click_picture_capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
        );
        Log.d(TAG, "start: virtualDisplay=" + virtualDisplay);
        if (virtualDisplay == null) {
            throw new IllegalStateException("VirtualDisplay 创建失败");
        }
        projectionStarted = true;
        attachOverlay();
        Log.d(TAG, "start: overlay attached successfully");
    }

    public synchronized void stop() {
        Log.d(TAG, "stop: begin, overlayAttached=" + overlayAttached + ", projectionStarted=" + projectionStarted);
        mainHandler.removeCallbacksAndMessages(null);
        detachOverlay();
        projectionStarted = false;
        if (virtualDisplay != null) {
            Log.d(TAG, "stop: releasing virtualDisplay");
            virtualDisplay.release();
            virtualDisplay = null;
        }
        try {
            imageReader.close();
        } catch (Exception ignore) {
            Log.w(TAG, "stop: imageReader.close failed", ignore);
        }
        try {
            mediaProjection.stop();
        } catch (Exception ignore) {
            Log.w(TAG, "stop: mediaProjection.stop failed", ignore);
        }
        eventStore.close();
        Log.d(TAG, "stop: completed");
    }

    public ClickPictureEventStore getEventStore() {
        return eventStore;
    }

    private void ensureOverlayPermission() {
        boolean canDraw = Settings.canDrawOverlays(appContext);
        Log.d(TAG, "ensureOverlayPermission: canDrawOverlays=" + canDraw);
        if (!canDraw) {
            throw new IllegalStateException("缺少悬浮窗权限，请先授权后再启动");
        }
    }

    private void attachOverlay() {
        if (overlayAttached) {
            Log.d(TAG, "attachOverlay: already attached");
            return;
        }

        int layoutFlag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        overlayView = new View(appContext);
        overlayView.setBackgroundColor(Color.argb(1, 0, 0, 0));
        overlayView.setOnTouchListener(new OverlayTouchListener());

        Log.d(TAG, "attachOverlay: addView with type=" + layoutFlag);
        windowManager.addView(overlayView, params);
        overlayAttached = true;
    }

    private void detachOverlay() {
        if (!overlayAttached || overlayView == null) {
            return;
        }
        try {
            windowManager.removeView(overlayView);
        } catch (Exception e) {
            Log.w(TAG, "移除悬浮层失败", e);
        }
        overlayAttached = false;
        overlayView = null;
    }

    private void handleClick(int clickX, int clickY) {
        try {
            Bitmap aroundBitmap = captureAroundBitmap(clickX, clickY);
            int cropLeft = Math.max(0, clickX - HALF_CROP_SIZE);
            int cropTop = Math.max(0, clickY - HALF_CROP_SIZE);
            ClickPictureEventStore.ClickPictureRecord record = eventStore.saveEvent(
                    clickX,
                    clickY,
                    cropLeft,
                    cropTop,
                    aroundBitmap
            );
            if (eventListener != null) {
                eventListener.onEventStored(record);
            }
            enablePassThroughThenRestore();
        } catch (Throwable throwable) {
            notifyError("记录点击及周围图片失败", throwable);
        }
    }

    private Bitmap captureAroundBitmap(int clickX, int clickY) throws IOException {
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            throw new IOException("当前没有可用屏幕帧，无法截图");
        }

        Bitmap fullBitmap = null;
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                throw new IOException("截图平面为空");
            }

            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            fullBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );
            fullBitmap.copyPixelsFromBuffer(buffer);

            int cropSize = HALF_CROP_SIZE * 2;
            int cropLeft = clamp(clickX - HALF_CROP_SIZE, 0, Math.max(0, fullBitmap.getWidth() - cropSize));
            int cropTop = clamp(clickY - HALF_CROP_SIZE, 0, Math.max(0, fullBitmap.getHeight() - cropSize));
            int cropWidth = Math.min(cropSize, fullBitmap.getWidth() - cropLeft);
            int cropHeight = Math.min(cropSize, fullBitmap.getHeight() - cropTop);

            return Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight);
        } finally {
            if (fullBitmap != null) {
                fullBitmap.recycle();
            }
            image.close();
        }
    }

    private void enablePassThroughThenRestore() {
        if (passThroughEnabled) {
            return;
        }

        passThroughEnabled = true;
        notifyPassThroughState(true);
        detachOverlay();

        mainHandler.postDelayed(() -> {
            passThroughEnabled = false;
            attachOverlay();
            notifyPassThroughState(false);
        }, PASS_THROUGH_DELAY_MS);
    }

    private void notifyPassThroughState(boolean enabled) {
        if (eventListener != null) {
            eventListener.onPassThroughStateChanged(enabled);
        }
    }

    private void notifyError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        if (eventListener != null) {
            eventListener.onError(message, throwable);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class OverlayTouchListener implements View.OnTouchListener {
        private int downX;
        private int downY;
        private long downTime;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = Math.round(event.getRawX());
                    downY = Math.round(event.getRawY());
                    downTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_UP:
                    int upX = Math.round(event.getRawX());
                    int upY = Math.round(event.getRawY());
                    long duration = System.currentTimeMillis() - downTime;
                    if (duration <= CLICK_DURATION_THRESHOLD_MS
                            && Math.abs(upX - downX) <= CLICK_MOVE_THRESHOLD
                            && Math.abs(upY - downY) <= CLICK_MOVE_THRESHOLD) {
                        handleClick(upX, upY);
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}

package com.example.chapter07.getposition;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 全局触摸记录服务
 * 仅记录触摸坐标，不拦截用户交互
 */
public class TouchInterceptorService extends Service {

    private static final String TAG = "TouchInterceptor";
    private static final String CHANNEL_ID = "TouchInterceptorChannel";
    private static final int NOTIFICATION_ID = 10003;

    public static final String ACTION_START = "com.example.chapter07.ACTION_START_INTERCEPT";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_INTERCEPT";

    public interface TouchListener {
        void onTouch(int x, int y);
    }

    private static TouchListener touchListener;

    private WindowManager windowManager;
    private View touchInterceptorView;
    private Handler mainHandler;
    private boolean isRecordingTouches = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TouchInterceptorService created");
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: " + action);

        if (ACTION_START.equals(action)) {
            startForegroundService();
            startRecordingOverlay();
        } else if (ACTION_STOP.equals(action)) {
            stopRecordingOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "TouchInterceptorService destroyed");
        stopRecordingOverlay();
    }

    public static void setTouchListener(TouchListener listener) {
        touchListener = listener;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "触摸记录",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于后台记录触摸操作的服务通知");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, AccessibilityDemoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TouchInterceptorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("触摸记录中")
                .setContentText("正在记录触摸，但不会拦截交互")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startRecordingOverlay() {
        if (isRecordingTouches) {
            Log.w(TAG, "已经在记录中");
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager 为空，无法添加悬浮层");
            return;
        }

        touchInterceptorView = new View(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    int x = (int) event.getRawX();
                    int y = (int) event.getRawY();
                    Log.d(TAG, "触摸坐标: (" + x + ", " + y + ")");
                    if (touchListener != null) {
                        mainHandler.post(() -> touchListener.onTouch(x, y));
                    }
                }
                return false;
            }
        };

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(touchInterceptorView, params);
            isRecordingTouches = true;
            Log.d(TAG, "开始记录触摸事件，但不影响页面交互");
        } catch (Exception e) {
            Log.e(TAG, "添加触摸记录视图失败", e);
            touchInterceptorView = null;
        }
    }

    private void stopRecordingOverlay() {
        isRecordingTouches = false;

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (windowManager != null && touchInterceptorView != null) {
            try {
                windowManager.removeViewImmediate(touchInterceptorView);
                Log.d(TAG, "已移除触摸记录视图");
            } catch (Exception e) {
                Log.w(TAG, "移除触摸记录视图失败", e);
            }
        }

        touchInterceptorView = null;
        windowManager = null;
        touchListener = null;
    }

    public static boolean isRunning() {
        return touchListener != null;
    }

    public static void startIntercepting(Context context) {
        Intent intent = new Intent(context, TouchInterceptorService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopIntercepting(Context context) {
        Intent intent = new Intent(context, TouchInterceptorService.class);
        intent.setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}

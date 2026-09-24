package com.example.chapter07.golbal_view;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.example.chapter07.getposition.DailyClickRecordDatabaseHelper;
import com.example.chapter07.golbal_view.ClickBroadcastReceiver;

public class GlobalTouchService extends Service {

    private static final String TAG = "GlobalTouch";
    private static final String CHANNEL_ID = "GlobalTouchRecordChannel";
    private static final int NOTIFICATION_ID = 20001;

    public static final String ACTION_START = "com.example.chapter07.ACTION_START_GLOBAL_TOUCH";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_GLOBAL_TOUCH";

    private WindowManager windowManager;
    private View overlayView;
    private boolean isRunning = false;
    private DailyClickRecordDatabaseHelper dbHelper;
    private String sessionTimestamp;
    private volatile float lastX = -1f;
    private volatile float lastY = -1f;
    private volatile int lastAction = -1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            startRecording();
        }
        return START_STICKY;
    }

    private void startRecording() {
        if (isRunning) {
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager unavailable");
            stopSelf();
            return;
        }

        sessionTimestamp = DailyClickRecordDatabaseHelper.getCurrentTimestamp();
        dbHelper = new DailyClickRecordDatabaseHelper(this, sessionTimestamp);
        dbHelper.getWritableDatabase();

        overlayView = new View(this);
        overlayView.setBackgroundColor(Color.TRANSPARENT);
        overlayView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                float x = event.getRawX();
                float y = event.getRawY();
                lastX = x;
                lastY = y;
                lastAction = action;
                ClickBroadcastReceiver.recordClick(x, y, "GlobalTouchService");
                long rowId = dbHelper.insertClickRecord(x, y, action, "overlay");
                Log.d(TAG, "点击坐标: (" + x + ", " + y + "), action=" + action + ", rowId=" + rowId + ", t=" + SystemClock.uptimeMillis());
                notifyLatestClick(x, y, action, rowId);
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        windowManager.addView(overlayView, params);
        isRunning = true;
        startForeground(NOTIFICATION_ID, buildNotification("正在记录任意位置点击"));
        Log.d(TAG, "Global touch recording started, session=" + sessionTimestamp);
    }

    private void notifyLatestClick(float x, float y, int action, long rowId) {
        String text = "最近点击: (" + Math.round(x) + ", " + Math.round(y) + ") / action=" + action + " / id=" + rowId;
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private void stopRecording() {
        if (!isRunning) {
            return;
        }

        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.w(TAG, "removeView failed", e);
            }
        }

        if (dbHelper != null) {
            dbHelper.close();
            dbHelper = null;
        }

        overlayView = null;
        isRunning = false;
        Log.d(TAG, "Global touch recording stopped");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "全局触摸记录",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于记录任意位置点击的前台服务通知");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, GlobalTouchService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent openIntent = new Intent(this, GlobalTouchActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("全局触摸记录")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
}


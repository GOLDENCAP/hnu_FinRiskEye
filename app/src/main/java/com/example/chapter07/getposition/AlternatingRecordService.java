package com.example.chapter07.getposition;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AlternatingRecordService extends Service {

    private static final String TAG = "AlternatingRecordSvc";
    private static final String CHANNEL_ID = "AlternatingRecordChannel";
    private static final int NOTIFICATION_ID = 10004;

    public static final String ACTION_START = "com.example.chapter07.ACTION_START_ALTERNATING_RECORD";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_ALTERNATING_RECORD";
    public static final String EXTRA_SESSION_TIMESTAMP = "extra_session_timestamp";
    public static final String EXTRA_START_MODE = "extra_start_mode";

    public static final int MODE_ACCESSIBILITY = 0;
    public static final int MODE_OVERLAY = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning;
    private int clickCount;
    private int currentMode = MODE_ACCESSIBILITY;
    private String sessionTimestamp;

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
        if (ACTION_START.equals(action)) {
            sessionTimestamp = intent.getStringExtra(EXTRA_SESSION_TIMESTAMP);
            currentMode = intent.getIntExtra(EXTRA_START_MODE, MODE_ACCESSIBILITY);
            startForegroundService();
            startCycle();
        } else if (ACTION_STOP.equals(action)) {
            stopCycle();
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
        stopCycle();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "交替记录",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于交替执行无障碍与悬浮窗记录");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundService() {
        Intent stopIntent = new Intent(this, AlternatingRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String text = sessionTimestamp == null ? "正在交替记录" : "会话: " + DailyClickRecordDatabaseHelper.getDisplayTimestamp(sessionTimestamp);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("交替记录中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void startCycle() {
        if (isRunning) {
            return;
        }
        if (sessionTimestamp == null || sessionTimestamp.isEmpty()) {
            sessionTimestamp = DailyClickRecordDatabaseHelper.getCurrentTimestamp();
        }
        isRunning = true;
        clickCount = 0;
        currentMode = MODE_ACCESSIBILITY;
        runNextMode();
    }

    private void runNextMode() {
        if (!isRunning) {
            return;
        }

        int nextIndex = clickCount + 1;
        if (currentMode == MODE_ACCESSIBILITY) {
            Log.d(TAG, "第" + nextIndex + "次点击：无障碍模式");
            performAccessibilityRecord();
        } else {
            Log.d(TAG, "第" + nextIndex + "次点击：悬浮窗模式");
            FloatingTouchRecordController.recordSingleTouch(this, new FloatingTouchRecordController.TouchRecordCallback() {
                @Override
                public void onTouchRecorded(int x, int y) {
                    onClickFinished(x, y, true);
                }

                @Override
                public void onTouchFailed(String reason) {
                    onClickFinished(0, 0, false);
                }
            });
        }
    }

    private void performAccessibilityRecord() {
        ClickAccessibilityService.setClickListener(new ClickAccessibilityService.ClickListener() {
            @Override
            public void onClick(int x, int y) {
                ClickAccessibilityService.setClickListener(null);
                onClickFinished(x, y, true);
            }
        });

        mainHandler.postDelayed(() -> {
            ClickAccessibilityService.LatestClick latestClick = ClickAccessibilityService.getLatestClick();
            if (latestClick != null) {
                ClickAccessibilityService.setClickListener(null);
                onClickFinished(latestClick.x, latestClick.y, true);
            }
        }, 1500);
    }

    private void onClickFinished(int x, int y, boolean success) {
        if (!isRunning) {
            return;
        }

        clickCount++;
        currentMode = currentMode == MODE_ACCESSIBILITY ? MODE_OVERLAY : MODE_ACCESSIBILITY;
        updateNotification("已完成 " + clickCount + " 次，下一次模式: " + (currentMode == MODE_ACCESSIBILITY ? "无障碍" : "悬浮窗"));
        mainHandler.postDelayed(this::runNextMode, 200);
    }

    private void stopCycle() {
        isRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        FloatingTouchRecordController.stop(this);
        ClickAccessibilityService.setClickListener(null);
    }

    public static void start(Context context, String sessionTimestamp, int startMode) {
        Intent intent = new Intent(context, AlternatingRecordService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SESSION_TIMESTAMP, sessionTimestamp);
        intent.putExtra(EXTRA_START_MODE, startMode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AlternatingRecordService.class);
        intent.setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void updateNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("交替记录中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }
}

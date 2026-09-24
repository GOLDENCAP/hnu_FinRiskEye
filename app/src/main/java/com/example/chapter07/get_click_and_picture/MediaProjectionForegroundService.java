package com.example.chapter07.get_click_and_picture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.chapter07.R;

public class MediaProjectionForegroundService extends Service {

    public static final String ACTION_START = "com.example.chapter07.get_click_and_picture.action.START_MEDIA_PROJECTION_FGS";
    public static final String ACTION_STOP = "com.example.chapter07.get_click_and_picture.action.STOP_MEDIA_PROJECTION_FGS";

    private static final String CHANNEL_ID = "media_projection_channel";
    private static final int NOTIFICATION_ID = 12001;

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.d("MediaProjectionFGS", "onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        android.util.Log.d("MediaProjectionFGS", "onStartCommand: action=" + action
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", startId=" + startId);
        if (ACTION_STOP.equals(action)) {
            android.util.Log.d("MediaProjectionFGS", "onStartCommand: stopping foreground service");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.util.Log.d("MediaProjectionFGS", "onStartCommand: startForeground with mediaProjection type");
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            android.util.Log.d("MediaProjectionFGS", "onStartCommand: startForeground legacy mode");
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("屏幕采集中")
                .setContentText("正在为点击记录功能保持截屏前台服务")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕采集服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于 MediaProjection 屏幕采集的前台服务通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}

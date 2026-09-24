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

import java.util.List;

/**
 * 后台点击记录服务
 * 功能：
 * 1. 以后台服务形式运行
 * 2. 通过无障碍服务监听屏幕点击
 * 3. 将点击位置保存到按日期划分的数据库
 */
public class BackgroundRecordService extends Service {

    private static final String TAG = "BackgroundRecordService";
    private static final String CHANNEL_ID = "BackgroundRecordChannel";
    private static final int NOTIFICATION_ID = 10002;

    // Intent Actions
    public static final String ACTION_START = "com.example.chapter07.ACTION_START_RECORD";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_RECORD";

    // 回调接口
    public interface RecordListener {
        void onRecord(int x, int y);
        void onCountUpdate(int count);
        void onStart();
        void onStop();
    }

    private static RecordListener recordListener;

    private DailyClickRecordDatabaseHelper dbHelper;
    private Handler mainHandler;
    private boolean isRecording = false;
    private int currentRecordCount = 0;
    private String sessionTimestamp = null;

    // 触摸监听器
    private TouchInterceptorService.TouchListener touchListener;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BackgroundRecordService created");

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
            String timestamp = intent.getStringExtra("session_timestamp");
            startForegroundService();
            startRecording(timestamp);
        } else if (ACTION_STOP.equals(action)) {
            stopRecording();
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
        Log.d(TAG, "BackgroundRecordService destroyed");
        stopRecording();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    /**
     * 设置记录监听器
     */
    public static void setRecordListener(RecordListener listener) {
        recordListener = listener;
    }

    /**
     * 创建通知渠道（Android 8.0+需要）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "后台点击记录",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于后台记录点击操作的服务通知");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 启动前台服务
     */
    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, AccessibilityDemoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, BackgroundRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("后台点击记录")
                .setContentText("正在记录点击...")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    /**
     * 更新通知
     */
    private void updateNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("后台点击记录")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true);

        Intent stopIntent = new Intent(this, BackgroundRecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    /**
     * 开始记录
     */
    private void startRecording(String timestamp) {
        android.util.Log.d("DEBUG_SERVICE", "startRecording: timestamp=" + timestamp);

        if (isRecording) {
            Log.w(TAG, "已经在记录中");
            return;
        }

        this.sessionTimestamp = timestamp;

        // 初始化数据库
        dbHelper = new DailyClickRecordDatabaseHelper(this, timestamp);
        currentRecordCount = dbHelper.getRecordCount();

        android.util.Log.d("DEBUG_SERVICE", "startRecording: dbHelper创建完成, 当前记录数=" + currentRecordCount);

        isRecording = true;

        // 通知开始
        if (recordListener != null) {
            mainHandler.post(() -> recordListener.onStart());
        }

        // 创建触摸监听器
        touchListener = (x, y) -> onScreenClick(x, y);

        // 启动触摸拦截服务
        TouchInterceptorService.setTouchListener(touchListener);
        TouchInterceptorService.startIntercepting(this);

        updateNotification("记录中... 已记录 " + currentRecordCount + " 条");
        Log.d(TAG, "开始记录，会话: " + timestamp);
    }

    /**
     * 屏幕点击回调
     */
    private void onScreenClick(int x, int y) {
        if (!isRecording) return;

        android.util.Log.d("DEBUG_RECORD", "onScreenClick: 收到点击 (" + x + ", " + y + ")");

        // 保存到数据库
        long id = dbHelper.insertClickRecord(x, y);
        currentRecordCount++;
        if(id<0){
            android.util.Log.d("DEBUG_RECORD","插入数据库失败 过程：BackgroundRecordService onScreenClick" );
        }
        android.util.Log.d("DEBUG_RECORD", "onScreenClick: 插入结果 id=" + id + ", 当前计数=" + currentRecordCount);

        if (id != -1) {
            Log.d(TAG, "已记录点击: (" + x + ", " + y + ")");
        } else {
            android.util.Log.e("DEBUG_RECORD", "onScreenClick: 插入失败！");
        }

        // 更新通知
        updateNotification("记录中... 已记录 " + currentRecordCount + " 条");

        // 回调
        if (recordListener != null) {
            final int finalX = x;
            final int finalY = y;
            mainHandler.post(() -> {
                recordListener.onRecord(finalX, finalY);
                recordListener.onCountUpdate(currentRecordCount);
            });
        }
    }

    /**
     * 停止记录
     */
    private void stopRecording() {
        android.util.Log.d("DEBUG_SERVICE", "stopRecording: isRecording=" + isRecording);

        if (!isRecording) return;

        isRecording = false;

        // 停止触摸拦截
        TouchInterceptorService.setTouchListener(null);
        TouchInterceptorService.stopIntercepting(this);

        // 关闭数据库
        if (dbHelper != null) {
            android.util.Log.d("DEBUG_SERVICE", "stopRecording: 关闭数据库");
            dbHelper.close();
            dbHelper = null;
        }

        // 通知
        if (recordListener != null) {
            mainHandler.post(() -> recordListener.onStop());
        }

        updateNotification("已停止，今日共记录 " + currentRecordCount + " 条");
        Log.d(TAG, "停止记录，共记录 " + currentRecordCount + " 条");
    }

    /**
     * 获取当前记录数
     */
    public int getCurrentRecordCount() {
        return currentRecordCount;
    }

    /**
     * 检查是否正在记录
     */
    public boolean isRecording() {
        return isRecording;
    }

    // ========== 静态启动方法 ==========

    /**
     * 启动后台记录服务
     */
    public static void startRecording(Context context, String timestamp) {
        Intent intent = new Intent(context, BackgroundRecordService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("session_timestamp", timestamp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 停止后台记录服务
     */
    public static void stopRecording(Context context) {
        Intent intent = new Intent(context, BackgroundRecordService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * 获取今日记录数
     */
    public static int getTodayRecordCount(Context context) {
        DailyClickRecordDatabaseHelper dbHelper = new DailyClickRecordDatabaseHelper(context);
        int count = dbHelper.getRecordCount();
        dbHelper.close();
        return count;
    }

    /**
     * 获取所有有记录的日期
     */
    public static List<String> getAllRecordDates(Context context) {
        return DailyClickRecordDatabaseHelper.getAllTimestamps(context);
    }

    /**
     * 获取当前数据库的时间戳
     */
    public static String getCurrentTimestamp() {
        return DailyClickRecordDatabaseHelper.getCurrentTimestamp();
    }

    /**
     * 获取今日所有数据库信息
     */
    public static List<DailyClickRecordDatabaseHelper.DatabaseInfo> getTodayDatabases(Context context) {
        return DailyClickRecordDatabaseHelper.getTodayDatabases(context);
    }
}

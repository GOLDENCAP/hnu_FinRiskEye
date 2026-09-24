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

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台点击回放服务
 * 功能：
 * 1. 以后台服务形式运行
 * 2. 读取数据库中的点击记录
 * 3. 自动回放点击操作
 */
public class BackgroundClickService extends Service {

    private static final String TAG = "BackgroundClickService";
    private static final String CHANNEL_ID = "BackgroundClickChannel";
    private static final int NOTIFICATION_ID = 10001;

    // Intent Actions
    public static final String ACTION_START = "com.example.chapter07.ACTION_START_REPLAY";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_REPLAY";

    // Intent Extra keys
    public static final String EXTRA_DELAY = "extra_delay";
    public static final String EXTRA_LOOP_COUNT = "extra_loop_count";
    public static final String EXTRA_TIMESTAMP = "extra_timestamp";  // 可选：指定时间戳

    private ExecutorService executorService;
    private Handler mainHandler;
    private AtomicBoolean isReplaying;
    private ClickRecordDatabaseHelper dbHelper;
    private DailyClickRecordDatabaseHelper dailyDbHelper;
    private String selectedTimestamp = null;  // 当前选择的时间戳

    // 回调接口
    public interface ReplayListener {
        void onStart(int totalCount);
        void onProgress(int current, int total, int x, int y);
        void onComplete(int successCount, int failCount);
        void onError(String error);
    }

    private ReplayListener replayListener;

    // 静态日志回调（用于后台服务向Activity传递日志）
    public interface LogCallback {
        void onLog(String message);
    }
    private static LogCallback logCallback;

    public static void setLogCallback(LogCallback callback) {
        logCallback = callback;
    }

    public static void log(String message) {
        if (logCallback != null) {
            logCallback.onLog(message);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BackgroundClickService created");

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        isReplaying = new AtomicBoolean(false);
        dbHelper = new ClickRecordDatabaseHelper(this);

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
            long delay = intent.getLongExtra(EXTRA_DELAY, 500);
            int loopCount = intent.getIntExtra(EXTRA_LOOP_COUNT, 1);
            String timestamp = intent.getStringExtra(EXTRA_TIMESTAMP);
            startForegroundService(delay, loopCount, timestamp);
        } else if (ACTION_STOP.equals(action)) {
            stopReplay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "BackgroundClickService destroyed");
        stopReplay();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        if (dbHelper != null) {
            dbHelper.close();
            dbHelper = null;
        }
        if (dailyDbHelper != null) {
            dailyDbHelper.close();
            dailyDbHelper = null;
        }
    }

    /**
     * 设置回放监听器
     */
    public void setReplayListener(ReplayListener listener) {
        this.replayListener = listener;
    }

    /**
     * 创建通知渠道（Android 8.0+需要）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "后台点击服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于后台回放点击操作的服务通知");
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
    private void startForegroundService(long delay, int loopCount, String timestamp) {
        selectedTimestamp = timestamp;

        Intent notificationIntent = new Intent(this, AccessibilityDemoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, BackgroundClickService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentText = timestamp != null
                ? "回放 " + DailyClickRecordDatabaseHelper.getDisplayTimestamp(timestamp) + " 的记录"
                : "正在回放点击操作...";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("后台点击回放")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // 开始回放
        startReplay(delay, loopCount, timestamp);
    }

    /**
     * 更新通知
     */
    private void updateNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("后台点击回放")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true);

        Intent stopIntent = new Intent(this, BackgroundClickService.class);
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
     * 开始回放
     */
    private void startReplay(long delayBetweenClicks, int loopCount, String timestamp) {
        if (isReplaying.get()) {
            Log.w(TAG, "已经在回放中");
            return;
        }

        if (!ClickAccessibilityService.isAlive()) {
            notifyError("无障碍服务未开启");
            return;
        }

        // 根据时间戳选择数据库
        List<ClickRecordDatabaseHelper.ClickRecord> records;
        if (timestamp != null && !timestamp.isEmpty()) {
            // 使用按时间戳划分的数据库
            dailyDbHelper = new DailyClickRecordDatabaseHelper(this, timestamp);
            records = dailyDbHelper.getAllClickRecords();
            log("使用数据库: " + DailyClickRecordDatabaseHelper.getDisplayTimestamp(timestamp));
        } else {
            // 使用默认数据库
            records = dbHelper.getAllClickRecords();
            log("使用默认数据库");
        }

        if (records.isEmpty()) {
            notifyError("没有可回放的记录");
            return;
        }

        final int total = records.size();
        isReplaying.set(true);

        notifyStart(total);
        log("开始回放，共 " + total + " 条记录");

        final long delay = delayBetweenClicks;
        final int fLoopCount = loopCount;

        executorService.execute(() -> {
            int[] successCount = {0};
            int[] failCount = {0};

            try {
                for (int loop = 0; loop < fLoopCount; loop++) {
                    if (!isReplaying.get()) {
                        log("回放已取消");
                        return;
                    }

                    log("--- 第 " + (loop + 1) + "/" + fLoopCount + " 轮开始 ---");

                    for (int i = 0; i < total; i++) {
                        if (!isReplaying.get()) {
                            Log.d(TAG, "回放已取消");
                            return;
                        }

                        ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                        boolean success = ClickAccessibilityService.click(record.x, record.y);

                        if (success) {
                            successCount[0]++;
                            log("✓ 点击成功: (" + record.x + ", " + record.y + ")");
                        } else {
                            failCount[0]++;
                            log("✗ 点击失败: (" + record.x + ", " + record.y + ")");
                        }

                        int current = loop * total + i + 1;
                        int totalOps = total * fLoopCount;
                        notifyProgress(current, totalOps, record.x, record.y);

                        // 延迟
                        if (i < total - 1 && delay > 0) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }

                    // 循环间隔
                    if (loop < fLoopCount - 1 && !isReplaying.get()) {
                        return;
                    }
                    if (loop < fLoopCount - 1) {
                        log("--- 第 " + (loop + 1) + " 轮完成，间隔 " + 1000 + "ms ---");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

                log("========== 全部完成 ==========");
                notifyComplete(successCount[0], failCount[0]);

            } catch (Exception e) {
                Log.e(TAG, "回放失败", e);
                notifyError("回放失败: " + e.getMessage());
            } finally {
                isReplaying.set(false);
            }
        });
    }

    /**
     * 停止回放
     */
    public void stopReplay() {
        isReplaying.set(false);
        Log.d(TAG, "停止回放");
    }

    /**
     * 检查是否正在回放
     */
    public boolean isReplaying() {
        return isReplaying.get();
    }

    // ========== 回调通知 ==========

    private void notifyStart(int totalCount) {
        if (replayListener != null) {
            mainHandler.post(() -> replayListener.onStart(totalCount));
        }
        mainHandler.post(() -> updateNotification("开始回放，共 " + totalCount + " 条记录"));
    }

    private void notifyProgress(int current, int total, int x, int y) {
        if (replayListener != null) {
            final int fCurrent = current;
            final int fTotal = total;
            final int fX = x;
            final int fY = y;
            mainHandler.post(() -> replayListener.onProgress(fCurrent, fTotal, fX, fY));
        }
        final int percent = (int) ((current / (float) total) * 100);
        mainHandler.post(() -> updateNotification("进度: " + percent + "% (" + current + "/" + total + ")"));
    }

    private void notifyComplete(int successCount, int failCount) {
        if (replayListener != null) {
            final int fSuccess = successCount;
            final int fFail = failCount;
            mainHandler.post(() -> replayListener.onComplete(fSuccess, fFail));
        }
        mainHandler.post(() -> updateNotification("回放完成! 成功: " + successCount + ", 失败: " + failCount));
    }

    private void notifyError(String error) {
        if (replayListener != null) {
            final String fError = error;
            mainHandler.post(() -> replayListener.onError(fError));
        }
        mainHandler.post(() -> updateNotification("错误: " + error));
    }

    // ========== 静态启动方法 ==========

    /**
     * 启动后台回放服务（使用默认数据库）
     */
    public static void startReplay(Context context, long delay, int loopCount) {
        startReplay(context, delay, loopCount, null);
    }

    /**
     * 启动后台回放服务（可指定时间戳）
     * @param context 上下文
     * @param delay 点击间隔（毫秒）
     * @param loopCount 循环次数
     * @param timestamp 时间戳字符串（格式：yyyy-MM-dd_HH-mm-ss），为null则使用默认数据库
     */
    public static void startReplay(Context context, long delay, int loopCount, String timestamp) {
        Intent intent = new Intent(context, BackgroundClickService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_DELAY, delay);
        intent.putExtra(EXTRA_LOOP_COUNT, loopCount);
        if (timestamp != null) {
            intent.putExtra(EXTRA_TIMESTAMP, timestamp);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 停止后台回放服务
     */
    public static void stopReplay(Context context) {
        Intent intent = new Intent(context, BackgroundClickService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * 获取所有数据库的时间戳列表
     */
    public static List<String> getAllTimestamps(Context context) {
        return DailyClickRecordDatabaseHelper.getAllTimestamps(context);
    }

    /**
     * 获取所有有记录的日期列表（兼容旧方法）
     */
    public static List<String> getAllRecordDates(Context context) {
        return getAllTimestamps(context);
    }

    /**
     * 获取今日所有数据库信息
     */
    public static List<DailyClickRecordDatabaseHelper.DatabaseInfo> getTodayDatabases(Context context) {
        return DailyClickRecordDatabaseHelper.getTodayDatabases(context);
    }
}

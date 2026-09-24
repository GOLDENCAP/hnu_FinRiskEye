package com.example.chapter07.golbal_view_advanced;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.chapter07.MainActivity;
import com.example.chapter07.golbal_view.ClickBroadcastReceiver;
import com.example.chapter07.golbal_view.GlobalTouchPassThroughService;

import java.io.File;

public class GlobalTouchAdvancedService extends Service {

    // ========== 回调接口 ==========
    public interface RecordListener {
        void onStateChanged(String stateText);
        void onClickRecorded(int x, int y, long rowId, boolean passThroughMode);
    }

    // ========== 常量定义 ==========
    private static final String TAG = "GlobalTouchAdvanced";
    private static final String CHANNEL_ID = "GlobalTouchAdvancedChannel";
    private static final int NOTIFICATION_ID = 20003;
    private static final long PASS_THROUGH_DURATION_MS = 500L;

    // 启动服务的 Action
    public static final String ACTION_START = "com.example.chapter07.ACTION_START_GLOBAL_TOUCH_ADVANCED";
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_GLOBAL_TOUCH_ADVANCED";

    // 数据库路径常量
    private static final String DB_BASE_PATH = "/data/user/0/com.example.chapter07/databases/";

    // ========== 静态变量 ==========
    private static volatile RecordListener recordListener;

    // ========== 实例变量 ==========
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private boolean isRunning;
    private boolean isPassThroughMode;
    private boolean isOverlayAttached;
    private static volatile boolean running;

    // 自定义数据库帮助类
    private CustomClickRecordHelper dbHelper;

    // 自定义数据库名称
    private String customDbName = "click_records.db";

    // ========== 设置回调监听器 ==========
    public static void setRecordListener(@Nullable RecordListener listener) {
        recordListener = listener;
    }

    // ========== 查询服务运行状态 ==========
    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建通知渠道
        createNotificationChannel();

        // 初始化 WindowManager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    // ========== 服务启动时调用 ==========
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_START.equals(action)) {
                // 获取自定义数据库名称
                customDbName = intent.getStringExtra("database_name");
                if (customDbName == null || customDbName.isEmpty()) {
                    customDbName = "click_records.db";
                }

                // 如果服务已经在运行，先停止
                if (running) {
                    stopOverlay();
                }

                // 启动前台服务
                startForeground(NOTIFICATION_ID, createNotification("全局触摸记录服务运行中"));

                // 初始化数据库
                dbHelper = new CustomClickRecordHelper(this, customDbName);

                // 启动悬浮窗
                startOverlay();

                running = true;
                isRunning = true;

                // 回调状态
                notifyStateChanged("✅ 服务运行中（数据库：" + customDbName + "）");

                Log.d(TAG, "服务已启动，数据库：" + customDbName);

            } else if (ACTION_STOP.equals(action)) {
                // 停止服务
                stopSelf();
            }
        }

        return START_STICKY;
    }

    // ========== 启动悬浮窗 ==========
    private void startOverlay() {
        if (isOverlayAttached) {
            return;
        }

        // 创建悬浮窗布局参数
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        // 创建透明的悬浮窗视图
        overlayView = new View(this);
        overlayView.setBackgroundColor(Color.argb(1, 0, 0, 0)); // nearly transparent

        // 设置触摸监听器
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private long startTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = (int) event.getRawX();
                        startY = (int) event.getRawY();
                        startTime = System.currentTimeMillis();
                        Log.d(TAG, "触摸按下: (" + startX + ", " + startY + ")");
                        return true;

                    case MotionEvent.ACTION_UP:
                        int endX = (int) event.getRawX();
                        int endY = (int) event.getRawY();
                        long duration = System.currentTimeMillis() - startTime;

                        // 只有当触摸时间很短（< 500ms）且移动距离很小（< 20px）时才视为点击
                        if (duration < 500 && Math.abs(endX - startX) < 20 && Math.abs(endY - startY) < 20) {
                            // 这是点击事件
                            handleClick(endX, endY);
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            // 添加悬浮窗到屏幕
            windowManager.addView(overlayView, params);
            isOverlayAttached = true;
            Log.d(TAG, "悬浮窗已添加");
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败: " + e.getMessage());
        }
    }

    // ========== 处理点击事件 ==========
    private void handleClick(int x, int y) {
        Log.d(TAG, "记录点击: (" + x + ", " + y + ")");

        // 记录到 ClickBroadcastReceiver（供 adb 实时查询）
        ClickBroadcastReceiver.recordClick(x, y, "GlobalTouchAdvancedService");

        // 保存到自定义数据库
        long rowId = dbHelper.insertClickRecord(x, y);

        // 回调点击信息
        notifyClickRecorded(x, y, rowId, true);

        // 启用放行模式（临时移除悬浮窗）
        enablePassThroughMode();
    }

    // ========== 启用放行模式 ==========
    private void enablePassThroughMode() {
        if (!isOverlayAttached || isPassThroughMode) {
            return;
        }

        isPassThroughMode = true;

        // 移除悬浮窗
        try {
            windowManager.removeView(overlayView);
            isOverlayAttached = false;
            Log.d(TAG, "悬浮窗已移除（放行模式）");
        } catch (Exception e) {
            Log.e(TAG, "移除悬浮窗失败: " + e.getMessage());
        }

        // 延迟后恢复悬浮窗
        mainHandler.postDelayed(() -> {
            disablePassThroughMode();
        }, PASS_THROUGH_DURATION_MS);
    }

    // ========== 禁用放行模式 ==========
    private void disablePassThroughMode() {
        if (!isPassThroughMode) {
            return;
        }

        isPassThroughMode = false;

        // 重新添加悬浮窗
        startOverlay();

        // 回调状态
        notifyStateChanged("✅ 服务运行中（数据库：" + customDbName + "）");
    }

    // ========== 停止悬浮窗 ==========
    private void stopOverlay() {
        if (!isOverlayAttached) {
            return;
        }

        try {
            windowManager.removeView(overlayView);
            isOverlayAttached = false;
            Log.d(TAG, "悬浮窗已移除");
        } catch (Exception e) {
            Log.e(TAG, "移除悬浮窗失败: " + e.getMessage());
        }
    }

    // ========== 创建通知渠道 ==========
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "全局触摸记录",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于全局触摸记录服务");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ========== 创建前台通知 ==========
    private Notification createNotification(String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, GlobalTouchAdvancedService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("全局触摸记录")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    // ========== 回调状态变更 ==========
    private void notifyStateChanged(String stateText) {
        if (recordListener != null) {
            recordListener.onStateChanged(stateText);
        }
    }

    // ========== 回调点击记录 ==========
    private void notifyClickRecorded(int x, int y, long rowId, boolean passThroughMode) {
        if (recordListener != null) {
            recordListener.onClickRecorded(x, y, rowId, passThroughMode);
        }
    }

    // ========== 服务销毁时调用 ==========
    @Override
    public void onDestroy() {
        super.onDestroy();

        // 停止悬浮窗
        stopOverlay();

        // 关闭数据库
        if (dbHelper != null) {
            dbHelper.close();
        }

        running = false;
        isRunning = false;

        // 回调状态
        notifyStateChanged("❌ 服务已停止");

        Log.d(TAG, "服务已销毁");
    }

    // ========== 自定义数据库帮助类 ==========
    /**
     * 专门用于处理自定义数据库名称的帮助类
     * 它会在指定的路径创建数据库，而不是使用默认路径
     */
    public static class CustomClickRecordHelper {
        private final Context context;
        private final String databaseName;
        private android.database.sqlite.SQLiteDatabase db;

        // 列名
        private static final String COLUMN_ID = "_id";
        private static final String COLUMN_X = "x_coordinate";
        private static final String COLUMN_Y = "y_coordinate";
        private static final String COLUMN_TIMESTAMP = "timestamp";

        // 创建表的SQL语句
        private static final String CREATE_TABLE_SQL =
                "CREATE TABLE IF NOT EXISTS click_records (" +
                        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        COLUMN_X + " INTEGER NOT NULL, " +
                        COLUMN_Y + " INTEGER NOT NULL, " +
                        COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" +
                        ")";

        public CustomClickRecordHelper(Context context, String databaseName) {
            this.context = context;
            this.databaseName = databaseName;
            initDatabase();
        }

        private void initDatabase() {
            try {
                // 确保目录存在
                File dbDir = new File(DB_BASE_PATH);
                if (!dbDir.exists()) {
                    dbDir.mkdirs();
                }

                // 打开或创建数据库
                String dbPath = DB_BASE_PATH + databaseName;
                db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null);

                // 创建表
                db.execSQL(CREATE_TABLE_SQL);

                Log.d(TAG, "数据库初始化成功: " + dbPath);
            } catch (Exception e) {
                Log.e(TAG, "数据库初始化失败: " + e.getMessage());
            }
        }

        public long insertClickRecord(int x, int y) {
            if (db == null) {
                return -1;
            }

            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(COLUMN_X, x);
                values.put(COLUMN_Y, y);

                long id = db.insert("click_records", null, values);
                Log.d(TAG, "记录已保存: id=" + id + ", x=" + x + ", y=" + y);
                return id;
            } catch (Exception e) {
                Log.e(TAG, "保存记录失败: " + e.getMessage());
                return -1;
            }
        }

        public void close() {
            if (db != null && db.isOpen()) {
                db.close();
                db = null;
            }
        }
    }
}

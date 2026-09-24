package com.example.chapter07.golbal_view;

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

import com.example.chapter07.getposition.ClickRecordDatabaseHelper;
import com.example.chapter07.golbal_view.ClickBroadcastReceiver;

public class GlobalTouchPassThroughService extends Service {

    // ========== 回调接口 ==========
    // 这个接口用于 Activity 和 Service 之间的通信
    // Service 通过这个接口把状态和点击信息告诉 Activity
    public interface RecordListener {
        // 当服务状态改变时调用（比如"服务已启动"、"已恢复悬浮窗模式"）
        void onStateChanged(String stateText);

        // 每当记录到一个新点击时调用
        // x, y           - 点击的屏幕坐标
        // rowId          - 这条记录在数据库中的行 ID
        // passThroughMode - 当前是否处于放行模式
        void onClickRecorded(int x, int y, long rowId, boolean passThroughMode);
    }

    // ========== 常量定义 ==========
    // TAG 用于在 Logcat 中过滤日志，方便调试
    private static final String TAG = "GlobalTouchPass";

    // 通知渠道 ID，用于 Android 8.0+ 的通知分类
    private static final String CHANNEL_ID = "GlobalTouchPassRecordChannel";

    // 通知的唯一 ID，同一个 ID 的通知只能存在一个
    private static final int NOTIFICATION_ID = 20002;

    // 放行模式的持续时间：500 毫秒 = 0.5 秒
    // 点击被记录后，悬浮窗会暂时移除，让用户可以正常点击
    // 0.5 秒后悬浮窗会重新出现
    private static final long PASS_THROUGH_DURATION_MS = 500L;

    // 启动服务的 Action，用于 Intent 区分是要启动还是停止服务
    public static final String ACTION_START = "com.example.chapter07.ACTION_START_GLOBAL_TOUCH_PASS";

    // 停止服务的 Action
    public static final String ACTION_STOP = "com.example.chapter07.ACTION_STOP_GLOBAL_TOUCH_PASS";

    // ========== 静态变量 ==========
    // 回调监听器，使用 volatile 保证多线程可见性
    // 为什么是静态的？因为 Service 是单例，一个 Service 实例对应所有 Activity
    private static volatile RecordListener recordListener;

    // ========== 实例变量 ==========
    // Handler 用来在主线程（UI 线程）执行任务
    // 因为 Service 运行在后台线程，但更新通知和回调需要在主线程
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // WindowManager 是窗口管理器，用于添加和移除悬浮窗
    private WindowManager windowManager;

    // overlayView 是我们创建的悬浮窗视图
    // 它覆盖在整个屏幕上，拦截所有的触摸事件
    private View overlayView;

    // 数据库帮助类，用于存储点击记录
    private ClickRecordDatabaseHelper dbHelper;

    // 服务是否正在运行
    private boolean isRunning;

    // 当前是否处于放行模式
    // 放行模式 = 临时移除悬浮窗，让用户的点击可以穿透过去
    private boolean isPassThroughMode;

    // 悬浮窗是否已经添加到屏幕上
    private boolean isOverlayAttached;

    // 静态的运行状态，供外部类（如 Activity）查询服务是否在运行
    private static volatile boolean running;

    // ========== 设置回调监听器 ==========
    // Activity 通过这个方法注册监听器，以便接收 Service 的状态更新
    public static void setRecordListener(@Nullable RecordListener listener) {
        recordListener = listener;
    }

    // ========== 查询服务运行状态 ==========
    public static boolean isRunning() {
        return running;
    }

    // ========== Service 绑定时调用 ==========
    // 这里我们不需要绑定，所以返回 null
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========== Service 创建时调用 ==========
    // 只会被调用一次，在 Service 首次创建时
    @Override
    public void onCreate() {
        super.onCreate();

        // 创建通知渠道
        // Android 8.0（API 26）及以上必须创建通知渠道才能显示通知
        createNotificationChannel();
    }

    // ========== 服务收到命令时调用 ==========
    // 每次调用 startService() 或 startForegroundService() 都会触发这个方法
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 如果 intent 是 null，说明服务是被系统回收后重新创建的
        // 这种情况下直接停止服务
        if (intent == null) {
            return START_NOT_STICKY;
        }

        // 获取 Intent 中设置的 Action
        String action = intent.getAction();

        // 判断是否是停止命令
        if (ACTION_STOP.equals(action)) {
            // 停止记录相关的操作
            stopRecording();

            // 移除前台服务的通知
            stopForeground(STOP_FOREGROUND_REMOVE);

            // 停止服务本身
            stopSelf();

            return START_NOT_STICKY;
        }

        // 如果不是停止命令（就是启动命令），且服务还没在运行
        if (!isRunning) {
            // 开始记录
            startRecording();
        }

        // START_STICKY 表示系统杀掉服务后，会重新创建并调用 onStartCommand
        return START_STICKY;
    }

    // ========== 开始记录功能 ==========
    // 设置悬浮窗、初始化数据库、开始前台服务
    private void startRecording() {
        // 如果已经在运行，就不要再重复启动了
        if (isRunning) {
            return;
        }

        // 获取系统窗口管理器
        // WINDOW_SERVICE 是 Android 系统服务之一
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 如果获取失败（比如内存不足），停止服务
        if (windowManager == null) {
            Log.e(TAG, "WindowManager unavailable");
            stopSelf();
            return;
        }

        // 初始化数据库帮助类
        // getWritableDatabase() 会打开或创建数据库
        dbHelper = new ClickRecordDatabaseHelper(this);
        dbHelper.getWritableDatabase();

        // 创建悬浮窗视图
        buildOverlayView();

        // 将悬浮窗添加到屏幕上
        attachOverlay();

        // 更新运行状态
        isRunning = true;
        running = true;
        isPassThroughMode = false;

        // 更新前台通知的内容
        updateNotification("正在记录点击，当前为悬浮窗拦截模式");

        // 以前台服务方式启动
        // 这样可以确保系统在内存不足时也不会轻易杀掉我们
        startForeground(NOTIFICATION_ID, buildNotification("正在记录点击，当前为悬浮窗拦截模式"));

        // 通知 Activity 当前状态
        notifyState("服务已启动，点击后将放行 0.5 秒");

        Log.d(TAG, "GlobalTouchPassThrough started");
    }

    // ========== 创建悬浮窗视图 ==========
    // 创建一个透明的视图，覆盖在整个屏幕上
    // 它的作用是拦截所有的触摸事件，记录下点击位置
    private void buildOverlayView() {
        // 创建一个普通的 View
        overlayView = new View(this);

        // 设置背景为透明
        // 虽然背景透明，但它仍然可以接收触摸事件
        overlayView.setBackgroundColor(Color.TRANSPARENT);

        // 设置触摸监听器
        overlayView.setOnTouchListener((v, event) -> {
            //v.performClick(); // 开启后的点击会有声音和震动
            // 如果服务已停止，不处理任何触摸
            if (!isRunning) {
                return false;
            }

            // 如果当前处于放行模式，不拦截触摸，让它传递下去
            if (isPassThroughMode) {
                return false;
            }

            // 获取触摸事件的类型（按下、移动、抬起等）
            int action = event.getActionMasked();

            // 只处理手指抬起的事件（点击完成）
            if (action == MotionEvent.ACTION_UP) {
                // 获取触摸的坐标
                // getRawX/Y 返回相对于屏幕的坐标（而不是相对于视图的坐标）
                float x = event.getRawX();
                float y = event.getRawY();
                ClickBroadcastReceiver.recordClick(x, y, "GlobalTouchPassThroughService");
                // 将点击坐标保存到数据库
                long rowId = dbHelper.insertClickRecord(Math.round(x), Math.round(y));

                // 打印日志，方便调试
                Log.d(TAG, "记录点击: (" + x + ", " + y + "), rowId=" + rowId);

                // 通知 Activity 有新的点击被记录
                notifyClickRecorded(Math.round(x), Math.round(y), rowId, false);

                // 进入放行模式
                // 临时移除悬浮窗，让用户刚才的点击能够穿透过去
                enterPassThroughMode();
            }

            // 返回 true 表示我们已经处理了这个触摸事件
            // 这样触摸事件不会继续传递到下层的应用
            return true;
        });
    }

    // ========== 将悬浮窗添加到屏幕 ==========
    private void attachOverlay() {
        // 做一些安全检查
        if (overlayView == null || windowManager == null || isOverlayAttached) {
            return;
        }

        // 创建悬浮窗的布局参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                // 宽度：占满整个屏幕
                WindowManager.LayoutParams.MATCH_PARENT,

                // 高度：占满整个屏幕
                WindowManager.LayoutParams.MATCH_PARENT,

                // 窗口类型：
                // Android 8.0 及以上使用 TYPE_APPLICATION_OVERLAY
                // Android 8.0 以下使用 TYPE_PHONE
                // 这两种类型都可以显示在其他应用之上
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,

                // 窗口标志（Window Flags）：
                // FLAG_NOT_FOCUSABLE      - 悬浮窗不会获取焦点
                // FLAG_NOT_TOUCH_MODAL    - 触摸事件可以传递到其他窗口
                // FLAG_LAYOUT_IN_SCREEN   - 允许窗口超出屏幕边界
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

                // 像素格式：TRANSLUCENT 表示半透明
                PixelFormat.TRANSLUCENT
        );

        // 将视图添加到窗口管理器
        windowManager.addView(overlayView, params);

        // 标记悬浮窗已经添加
        isOverlayAttached = true;
    }

    // ========== 移除悬浮窗 ==========
    private void detachOverlay() {
        if (windowManager != null && overlayView != null && isOverlayAttached) {
            try {
                // 立即移除视图
                windowManager.removeViewImmediate(overlayView);
            } catch (Exception e) {
                // 移除失败时打印警告日志
                // 可能是因为视图已经被移除了
                Log.w(TAG, "removeViewImmediate failed", e);
            }
            isOverlayAttached = false;
        }
    }

    // ========== 进入放行模式 ==========
    // 放行模式 = 临时移除悬浮窗，让用户的点击能够真正到达下层的应用
    // 持续 0.5 秒后，自动恢复悬浮窗模式
    private void enterPassThroughMode() {
        if (!isRunning) {
            return;
        }

        // 标记当前处于放行模式
        isPassThroughMode = true;

        // 移除悬浮窗，这样触摸事件就能穿透到下层应用
        detachOverlay();

        // 通知 Activity 状态变化
        notifyState("已保存点击，进入 0.5 秒正常点击模式");

        // 更新通知
        updateNotification("正常点击放行中（0.5 秒）");

        // 使用 Handler 延迟执行任务
        // 0.5 秒后自动恢复悬浮窗模式
        mainHandler.postDelayed(() -> {
            // 检查服务是否还在运行
            if (!isRunning) {
                return;
            }

            // 退出放行模式
            isPassThroughMode = false;

            // 重新添加悬浮窗
            attachOverlay();

            // 通知状态变化
            notifyState("已恢复悬浮窗模式，等待下一次点击");

            // 更新通知
            updateNotification("悬浮窗模式已恢复");
        }, PASS_THROUGH_DURATION_MS); // 延迟 500 毫秒
    }

    // ========== 停止记录功能 ==========
    private void stopRecording() {
        // 标记服务已停止
        isRunning = false;
        running = false;
        isPassThroughMode = false;

        // 移除所有待执行的延迟任务
        // 防止 0.5 秒后自动恢复悬浮窗时，服务已经停止了
        mainHandler.removeCallbacksAndMessages(null);

        // 移除悬浮窗
        detachOverlay();

        // 关闭数据库连接
        if (dbHelper != null) {
            dbHelper.close();
            dbHelper = null;
        }

        // 通知 Activity
        notifyState("服务已停止");

        Log.d(TAG, "GlobalTouchPassThrough stopped");
    }

    // ========== 创建通知渠道（Android 8.0+） ==========
    private void createNotificationChannel() {
        // 只有 Android 8.0 及以上才需要创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 创建通知渠道
            // IMPORTANCE_LOW 表示通知重要性较低，不会发出声音
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,                    // 渠道 ID
                    "全局触摸放行记录",              // 渠道名称（用户可见）
                    NotificationManager.IMPORTANCE_LOW // 重要性级别
            );

            // 设置渠道描述
            channel.setDescription("记录点击后短暂放行 0.5 秒，再恢复悬浮窗模式");

            // 设置不显示角标（桌面图标右上角的小红点）
            channel.setShowBadge(false);

            // 获取通知管理器
            NotificationManager manager = getSystemService(NotificationManager.class);

            // 创建渠道
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ========== 构建前台通知 ==========
    // 前台服务必须显示一个通知，告知用户服务正在运行
    private Notification buildNotification(String text) {
        // 创建停止服务的 Intent
        Intent stopIntent = new Intent(this, GlobalTouchPassThroughService.class);
        stopIntent.setAction(ACTION_STOP);

        // 将 Intent 包装成 PendingIntent
        // PendingIntent 允许其他组件（如通知）在未来某个时刻执行我们指定的 Intent
        // 参数：
        //   this              - 上下文
        //   1                 - 请求码，用于区分不同的 PendingIntent
        //   stopIntent        - 要执行的 Intent
        //   FLAG_UPDATE_CURRENT - 如果已存在相同的 PendingIntent，则更新其内容
        //   FLAG_IMMUTABLE      - 表示这个 PendingIntent 是不可变的
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 创建点击通知打开 Activity 的 Intent
        Intent openIntent = new Intent(this, GlobalTouchPassThroughActivity.class);

        // 包装成 PendingIntent
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 使用 NotificationCompat.Builder 构建通知
        // NotificationCompat 是兼容版，支持更老的 Android 版本
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("全局触摸放行记录")   // 通知标题
                .setContentText(text)                   // 通知内容
                .setSmallIcon(android.R.drawable.ic_menu_view) // 通知图标（使用系统内置图标）
                .setContentIntent(openPendingIntent)     // 点击通知打开 Activity
                .addAction(                            // 添加一个操作按钮
                        android.R.drawable.ic_media_pause, // 按钮图标
                        "停止",                            // 按钮文字
                        stopPendingIntent)               // 点击按钮要执行的 PendingIntent
                .setOngoing(true)                       // 设置为持续通知，用户无法滑动删除
                .build();                               // 构建完成
    }

    // ========== 更新前台通知 ==========
    private void updateNotification(String text) {
        // 获取通知管理器
        NotificationManager manager = getSystemService(NotificationManager.class);

        if (manager != null) {
            // 发送新通知（使用相同的 ID 替换之前的通知）
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    // ========== 通知状态变化给 Activity ==========
    private void notifyState(String stateText) {
        // 获取监听器副本
        RecordListener listener = recordListener;

        if (listener != null) {
            // 使用主线程的 Handler 切换到主线程
            // 然后调用监听器的回调方法
            mainHandler.post(() -> listener.onStateChanged(stateText));
        }
    }

    // ========== 通知点击已记录给 Activity ==========
    private void notifyClickRecorded(int x, int y, long rowId, boolean passThroughMode) {
        RecordListener listener = recordListener;

        if (listener != null) {
            // 切换到主线程，然后调用监听器的回调方法
            mainHandler.post(() -> listener.onClickRecorded(x, y, rowId, passThroughMode));
        }
    }

    // ========== 外部启动服务的静态方法 ==========
    // Activity 调用这个方法启动服务
    public static void start(Context context) {
        // 创建启动服务的 Intent
        Intent intent = new Intent(context, GlobalTouchPassThroughService.class);
        intent.setAction(ACTION_START);

        // Android 8.0 及以上，必须使用 startForegroundService
        // 然后在服务内部调用 startForeground
        // Android 8.0 以下，直接使用 startService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // ========== 外部停止服务的静态方法 ==========
    public static void stop(Context context) {
        Intent intent = new Intent(context, GlobalTouchPassThroughService.class);
        intent.setAction(ACTION_STOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // ========== Service 销毁时调用 ==========
    @Override
    public void onDestroy() {
        // 停止所有记录相关的操作
        stopRecording();

        // 必须调用父类的 onDestroy
        super.onDestroy();
    }
}

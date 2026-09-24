package com.example.chapter07.golbal_view;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.getposition.BackgroundClickService;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;

import java.io.File;

public class GlobalTouchPassThroughActivity extends AppCompatActivity {

    // ========== 控件声明 ==========
    // 状态文本：显示服务是否正在运行
    private TextView tvStatus;

    // 点击信息文本：显示最近一次记录的点击坐标
    private TextView tvClickInfo;

    // 启动按钮：点击后开始记录
    private Button btnStart;

    // 停止按钮：点击后停止记录
    private Button btnStop;

    // 权限申请按钮：当悬浮窗权限未开启时显示，点击跳转到权限设置页面
    private Button btnRequestPermission;

    // 回放按钮：点击后开始回放记录的点击
    private Button btnReplay;

    // ========== 状态变量 ==========
    // 当前正在显示的 Toast，避免多个 Toast 重叠显示
    private static Toast currentToast;

    // 服务是否正在运行（用于 Activity 可见时恢复 UI 状态）
    private boolean isRunning;

    // ========== 页面创建时调用 ==========
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 必须先调用父类的 onCreate，确保 Activity 正确初始化
        super.onCreate(savedInstanceState);

        // 设置当前页面的布局文件
        // R.layout.activity_global_touch 是 res/layout/ 目录下的 XML 布局
        setContentView(R.layout.activity_global_touch);

        // 初始化界面上所有的控件（比如按钮、文本框等）
        initViews();

        // 给每个按钮设置点击事件
        setupListeners();

        // ========== 设置回调监听 ==========
        // 全局放行记录服务（Service）运行过程中，会通过这个监听器把状态和点击信息回调给 Activity
        // 这样 Activity 就能实时更新界面上的文字
        GlobalTouchPassThroughService.setRecordListener(new GlobalTouchPassThroughService.RecordListener() {

            // 当服务状态发生变化时调用（比如"服务已启动"、"悬浮窗模式已恢复"等）
            @Override
            public void onStateChanged(String stateText) {
                // runOnUiThread 是为了确保在主线程（UI 线程）更新界面
                // 因为 Service 的回调可能来自其他线程
                runOnUiThread(() -> tvStatus.setText(stateText));
            }

            // 每当记录到一个新的点击时调用
            // x, y           - 点击的屏幕坐标
            // rowId          - 这条记录在数据库中的 ID
            // passThroughMode - 当前是否处于放行模式
            @Override
            public void onClickRecorded(int x, int y, long rowId, boolean passThroughMode) {
                runOnUiThread(() -> tvClickInfo.setText(
                        "最近一次记录：(" + x + ", " + y + ")\n" +
                                "记录ID: " + rowId + "\n" +
                                "当前状态：" + (passThroughMode ? "放行中" : "悬浮层拦截后已进入放行")
                ));
            }
        });

        // 初始化界面状态：服务未启动，所以是 false
        updateUI(false);
    }

    // ========== 初始化界面控件 ==========
    // 把 XML 布局里的控件（按钮、文本框）和 Java 代码中的变量对应起来
    private void initViews() {
        // findViewById 是 Android 查找界面控件的方法
        // R.id.tv_status 是布局文件中定义的控件 ID
        tvStatus = findViewById(R.id.tv_status);
        tvClickInfo = findViewById(R.id.tv_click_info);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRequestPermission = findViewById(R.id.btn_request_permission);
        btnReplay = findViewById(R.id.btn_replay);

        // 初始状态：服务未启动，显示红色叉号
        tvStatus.setText("❌ 服务未启动");

        // 初始说明文字，解释功能的工作方式
        tvClickInfo.setText("点击启动后，每次记录点击都会短暂放行 0.5 秒，再恢复悬浮窗模式。");
    }

    // ========== 设置按钮点击事件 ==========
    private void setupListeners() {
        // 启动按钮的点击事件
        btnStart.setOnClickListener(v -> {
            // 首先检查是否已经授权悬浮窗权限
            // Settings.canDrawOverlays() 是 Android 提供的检查方法
            if (Settings.canDrawOverlays(this)) {
                // 权限已授权，调用服务类的静态方法启动服务
                GlobalTouchPassThroughService.start(this);

                // 标记服务正在运行
                isRunning = true;

                // 更新界面：启用停止按钮，禁用启动按钮
                updateUI(true);

                // 弹出一个对话框，告诉用户数据库路径和当前记录数
                showDatabasePathDialog();
            } else {
                // 权限未授权，显示提示
                showToast("请先开启悬浮窗权限");

                // 启用权限申请按钮，让用户可以点击去授权
                btnRequestPermission.setEnabled(true);
            }
        });

        // 停止按钮的点击事件
        btnStop.setOnClickListener(v -> {
            // 调用服务类的静态方法停止服务
            GlobalTouchPassThroughService.stop(this);

            // 标记服务已停止
            isRunning = false;

            // 更新界面状态
            updateUI(false);

            // 显示"已停止"的提示
            showToast("已停止监听");
        });

        // 权限申请按钮的点击事件
        // 跳转到系统设置页面，让用户手动授权悬浮窗权限
        btnRequestPermission.setOnClickListener(v -> {
            // ACTION_MANAGE_OVERLAY_PERMISSION 是系统内置的悬浮窗权限设置页面
            android.content.Intent intent = new android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);

            // 设置要授权的包名（当前应用的包名）
            // 这样用户授权后，系统会自动回到我们的应用
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));

            // 跳转到设置页面
            startActivity(intent);
        });

        // 回放按钮的点击事件
        // 弹出一个确认对话框，询问用户是否要开始回放
        btnReplay.setOnClickListener(v -> showReplayConfirmDialog());
    }

    // ========== 显示数据库路径对话框 ==========
    // 告诉用户点击记录保存在哪里，以及当前有多少条记录
    private void showDatabasePathDialog() {
        // 获取数据库文件的绝对路径
        String dbPath = getDatabasePath();

        // 获取当前数据库中有多少条记录
        int recordCount = getRecordCount();

        // 创建并显示一个对话框
        new AlertDialog.Builder(this)
                .setTitle("✅ 已启动记录") // 对话框标题
                // 对话框内容：说明数据库位置、记录数量和工作原理
                .setMessage("点击记录已保存到数据库：\n\n" +
                        "📁 数据库路径：\n" + dbPath + "\n\n" +
                        "📊 当前记录数：" + recordCount + " 条\n\n" +
                        "点击任意位置将被记录，稍后会短暂放行 0.5 秒。")
                .setPositiveButton("知道了", null) // 只有"确定"按钮，null 表示不需要额外处理
                .show();
    }

    // ========== 获取数据库文件路径 ==========
    private String getDatabasePath() {
        // getDatabasePath("click_records.db") 返回应用私有目录下
        // 名为 click_records.db 的数据库文件路径
        File dbFile = getDatabasePath("click_records.db");

        // 返回文件的完整路径字符串
        return dbFile.getAbsolutePath();
    }

    // ========== 获取数据库中的记录数量 ==========
    private int getRecordCount() {
        // 创建数据库帮助类实例
        // 这是一个自定义类，用于封装数据库操作
        ClickRecordDatabaseHelper dbHelper = new ClickRecordDatabaseHelper(this);

        // 调用帮助类的方法获取记录总数
        int count = dbHelper.getRecordCount();

        // 关闭数据库连接，避免资源泄漏
        dbHelper.close();

        // 返回记录数量
        return count;
    }

    // ========== 显示回放确认对话框 ==========
    private void showReplayConfirmDialog() {
        // 先检查数据库中有多少条记录
        int recordCount = getRecordCount();

        // 如果没有记录，提示用户先记录一些点击
        if (recordCount == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("没有可回放的记录")
                    .setMessage("当前数据库中没有任何记录。\n\n请先启动服务并点击屏幕记录点击位置。")
                    .setPositiveButton("知道了", null)
                    .show();
            return; // 没有记录，直接返回，不再显示回放确认
        }

        // 有记录，显示回放确认对话框
        new AlertDialog.Builder(this)
                .setTitle("后台回放")
                .setMessage("即将开始后台回放：\n\n" +
                        "📁 数据库：click_records.db\n" +
                        "📊 记录数：" + recordCount + " 条\n" +
                        "⏱  间隔：500ms\n\n" +
                        "程序将切换到后台运行，回放过程中可随时查看通知栏停止。")
                // 用户点击"启动"时，开始后台回放
                .setPositiveButton("启动", (dialog, which) -> startBackgroundReplay())
                // 用户点击"取消"时，什么都不做
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 启动后台回放 ==========
    // 启动一个后台服务，让它按照数据库中的记录依次模拟点击
    private void startBackgroundReplay() {
        // 创建启动 BackgroundClickService 的 Intent
        Intent intent = new Intent(this, BackgroundClickService.class);

        // 设置动作为"开始回放"
        // BackgroundClickService 会根据这个 action 判断要执行什么操作
        intent.setAction(BackgroundClickService.ACTION_START);

        // 设置回放间隔：每 500 毫秒执行一次点击
        intent.putExtra(BackgroundClickService.EXTRA_DELAY, 500L);

        // 设置循环次数：1 表示只播放一遍
        intent.putExtra(BackgroundClickService.EXTRA_LOOP_COUNT, 1);

        // 启动后台服务
        // startService 会触发 Service 的 onStartCommand 方法
        startService(intent);

        // 显示回放已启动的提示对话框
        new AlertDialog.Builder(this)
                .setTitle("✅ 已启动后台回放")
                .setMessage("后台回放服务已在通知栏启动。\n\n" +
                        "请切换到需要回放的应用，模拟点击将自动执行。\n\n" +
                        "如需停止，点击通知栏中的「停止」。")
                .setPositiveButton("好的", (dialog, which) -> {
                    // 将 Activity 移动到后台
                    // 这样用户可以继续操作其他应用，而我们的回放服务在后台运行
                    moveTaskToBack(true);
                })
                .setCancelable(false) // 用户不能通过点击对话框外来关闭
                .show();
    }

    // ========== 更新界面状态 ==========
    // 根据服务是否运行、是否有悬浮窗权限，来更新界面上的文字和按钮状态
    private void updateUI(boolean running) {
        // 检查悬浮窗权限是否已授权
        boolean overlayEnabled = Settings.canDrawOverlays(this);

        if (running && overlayEnabled) {
            // 服务正在运行且权限正常的情况
            tvStatus.setText("✅ 放行记录运行中");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark)); // 绿色文字
            btnStart.setEnabled(false);      // 禁用启动按钮（服务已在运行）
            btnStop.setEnabled(true);        // 启用停止按钮
            btnRequestPermission.setEnabled(false); // 权限已经有了，不需要申请按钮
        } else {
            // 服务未运行或权限不足的情况
            // 根据是否有权限显示不同的提示文字
            tvStatus.setText(overlayEnabled ? "❌ 服务未启动" : "❌ 悬浮窗权限未开启");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark)); // 红色文字
            btnStart.setEnabled(overlayEnabled); // 只有权限够了才能点击启动
            btnStop.setEnabled(false);           // 服务没运行，不能停止
            btnRequestPermission.setEnabled(!overlayEnabled); // 没权限时显示申请按钮
        }
    }

    // ========== 显示 Toast 提示 ==========
    // Toast 是 Android 中一种简短的提示信息，会在屏幕底部出现几秒后自动消失
    private void showToast(String message) {
        // 如果之前有 Toast 在显示，先取消它
        // 这样可以避免多个 Toast 叠加在一起
        if (currentToast != null) {
            currentToast.cancel();
        }

        // 创建新的 Toast
        // makeText 参数：上下文（Activity）、提示文字、显示时长（SHORT = 2秒）
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);

        // 显示 Toast
        currentToast.show();
    }

    // ========== Activity 重新可见时调用 ==========
    // 比如用户从其他页面返回，或者从后台回到前台
    @Override
    protected void onResume() {
        // 必须先调用父类的 onResume
        super.onResume();

        // 重新检查权限状态和服务状态，更新界面
        // 这样可以确保用户从设置页面返回后，界面状态是正确的
        updateUI(isRunning);
    }

    // ========== Activity 销毁时调用 ==========
    // 比如用户按了返回键，或者应用被系统关闭
    @Override
    protected void onDestroy() {
        // 必须先调用父类的 onDestroy
        super.onDestroy();

        // 清除回调监听
        // 防止 Service 回调到一个已经不存在的 Activity
        GlobalTouchPassThroughService.setRecordListener(null);

        // 取消可能还在显示的 Toast
        // 如果 Activity 销毁时 Toast 还在，关闭 Activity 会导致 Toast 无法正常关闭
        if (currentToast != null) {
            currentToast.cancel();
        }
    }
}

package com.example.chapter07.golbal_view_advanced;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.getposition.BackgroundClickService;

public class GlobalTouchAdvancedActivity extends AppCompatActivity {

    // ========== 控件声明 ==========
    // 数据库名称输入框
    private EditText etDbName;

    // 当前使用的数据库显示
    private TextView tvCurrentDb;

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

    // 用户输入的数据库名称
    private String customDbName;

    // ========== 页面创建时调用 ==========
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 必须先调用父类的 onCreate，确保 Activity 正确初始化
        super.onCreate(savedInstanceState);

        // 设置当前页面的布局文件
        setContentView(R.layout.activity_global_touch_advanced);

        // 初始化界面上所有的控件
        initViews();

        // 给每个按钮设置点击事件
        setupListeners();

        // ========== 设置回调监听 ==========
        GlobalTouchAdvancedService.setRecordListener(new GlobalTouchAdvancedService.RecordListener() {

            // 当服务状态发生变化时调用
            @Override
            public void onStateChanged(String stateText) {
                runOnUiThread(() -> tvStatus.setText(stateText));
            }

            // 每当记录到一个新的点击时调用
            @Override
            public void onClickRecorded(int x, int y, long rowId, boolean passThroughMode) {
                runOnUiThread(() -> tvClickInfo.setText(
                        "最近一次记录：(" + x + ", " + y + ")\n" +
                                "记录ID: " + rowId + "\n" +
                                "当前状态：" + (passThroughMode ? "放行中" : "悬浮层拦截后已进入放行")
                ));
            }
        });

        // 初始化界面状态：服务未启动
        updateUI(false);
    }

    // ========== 初始化界面控件 ==========
    private void initViews() {
        etDbName = findViewById(R.id.et_db_name);
        tvCurrentDb = findViewById(R.id.tv_current_db);
        tvStatus = findViewById(R.id.tv_status);
        tvClickInfo = findViewById(R.id.tv_click_info);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRequestPermission = findViewById(R.id.btn_request_permission);
        btnReplay = findViewById(R.id.btn_replay);

        // 初始状态：服务未启动，显示红色叉号
        tvStatus.setText("❌ 服务未启动");
        tvCurrentDb.setText("当前数据库：未设置");

        // 初始说明文字
        tvClickInfo.setText("点击启动后，每次记录点击都会短暂放行 0.5 秒，再恢复悬浮窗模式。");
    }

    // ========== 设置按钮点击事件 ==========
    private void setupListeners() {
        // 启动按钮的点击事件
        btnStart.setOnClickListener(v -> {
            // 首先检查是否已经授权悬浮窗权限
            if (Settings.canDrawOverlays(this)) {
                // 权限已授权，检查数据库名称
                customDbName = etDbName.getText().toString().trim();

                if (TextUtils.isEmpty(customDbName)) {
                    showToast("请输入数据库名称");
                    return;
                }

                // 移除 .db 后缀（如果用户输入了的话）
                if (customDbName.endsWith(".db")) {
                    customDbName = customDbName.substring(0, customDbName.length() - 3);
                }

                // 检查是否包含非法字符
                if (!isValidDbName(customDbName)) {
                    showToast("数据库名称不能包含特殊字符");
                    return;
                }

                // 更新当前数据库显示
                tvCurrentDb.setText("当前数据库：" + customDbName + ".db");

                // 启动服务，传递自定义数据库名称
                startService();
            } else {
                // 权限未授权，显示提示并跳转到设置页面
                new AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("全局放行记录功能需要悬浮窗权限来拦截屏幕触摸事件。")
                        .setPositiveButton("去授权", (dialog, which) -> {
                            // 跳转到悬浮窗权限设置页面
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                            startActivity(intent);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // 停止按钮的点击事件
        btnStop.setOnClickListener(v -> {
            // 停止服务
            stopService();
        });

        // 权限申请按钮的点击事件
        btnRequestPermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(intent);
        });

        // 回放按钮的点击事件
        btnReplay.setOnClickListener(v -> {
            // 跳转到后台回放页面
            // 使用自定义数据库名称
            Intent intent = new Intent(this, BackgroundClickService.class);
            intent.putExtra("database_name", customDbName + ".db");
            startActivity(intent);
        });
    }

    // ========== 检查数据库名称是否合法 ==========
    private boolean isValidDbName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        // 只允许字母、数字、下划线
        return name.matches("^[a-zA-Z0-9_]+$");
    }

    // ========== 启动服务 ==========
    private void startService() {
        // 创建启动服务的 Intent
        Intent intent = new Intent(this, GlobalTouchAdvancedService.class);
        intent.setAction(GlobalTouchAdvancedService.ACTION_START);
        intent.putExtra("database_name", customDbName + ".db");

        // 启动前台服务
        startForegroundService(intent);

        // 显示提示
        showToast("服务已启动，记录将保存到：" + customDbName + ".db");

        // 更新 UI 状态
        updateUI(true);
    }

    // ========== 停止服务 ==========
    private void stopService() {
        // 创建停止服务的 Intent
        Intent intent = new Intent(this, GlobalTouchAdvancedService.class);
        intent.setAction(GlobalTouchAdvancedService.ACTION_STOP);

        // 停止服务
        startService(intent);

        // 显示提示
        showToast("服务已停止");

        // 更新 UI 状态
        updateUI(false);
    }

    // ========== 更新 UI 状态 ==========
    private void updateUI(boolean isRunning) {
        this.isRunning = isRunning;

        if (isRunning) {
            // 服务运行中
            tvStatus.setText("✅ 服务运行中");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            etDbName.setEnabled(false); // 运行期间不能修改数据库名称
        } else {
            // 服务未运行
            tvStatus.setText("❌ 服务未启动");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            etDbName.setEnabled(true); // 可以修改数据库名称
        }
    }

    // ========== Activity 重新可见时调用 ==========
    // 用于检查权限状态是否变化
    @Override
    protected void onResume() {
        super.onResume();

        // 检查权限是否已经授权
        if (Settings.canDrawOverlays(this)) {
            // 权限已授权，更新按钮文字
            btnRequestPermission.setText("悬浮窗权限已授权");
            btnRequestPermission.setEnabled(false);
        } else {
            // 权限未授权
            btnRequestPermission.setText("请求悬浮窗权限");
            btnRequestPermission.setEnabled(true);
        }

        // 检查服务是否还在运行
        if (GlobalTouchAdvancedService.isRunning()) {
            updateUI(true);
        }
    }

    // ========== 显示 Toast 提示 ==========
    private void showToast(String message) {
        // 取消之前的 Toast
        if (currentToast != null) {
            currentToast.cancel();
        }
        // 显示新的 Toast
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        currentToast.show();
    }
}

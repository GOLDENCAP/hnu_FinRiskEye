package com.example.chapter07.golbal_view;

import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickAccessibilityService;

public class GlobalTouchActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvClickInfo;
    private Button btnStart;
    private Button btnStop;
    private Button btnRequestPermission;

    private static Toast currentToast;
    private boolean isRunning = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_touch);

        initViews();
        setupListeners();
        updateUI(false);
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvClickInfo = findViewById(R.id.tv_click_info);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRequestPermission = findViewById(R.id.btn_request_permission);
    }

    private void setupListeners() {
        btnStart.setOnClickListener(v -> {
            if (ClickAccessibilityService.isAlive()) {
                showToast("无障碍服务已在运行");
                isRunning = true;
                updateUI(true);
            } else {
                showToast("请先开启无障碍服务");
                btnRequestPermission.setEnabled(true);
            }
        });

        btnStop.setOnClickListener(v -> {
            ClickAccessibilityService.setClickListener(null);
            isRunning = false;
            updateUI(false);
            showToast("已停止监听");
        });

        btnRequestPermission.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    private void updateUI(boolean running) {
        boolean accessibilityEnabled = ClickAccessibilityService.isAlive();
        if (running && accessibilityEnabled) {
            tvStatus.setText("✅ 服务运行中");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            btnRequestPermission.setEnabled(false);
            tvClickInfo.setText("正在监听无障碍点击事件\n\n" +
                    "点击任意可点击控件后，会把点击坐标写入数据库\n" +
                    "并在这里实时显示最近一次点击\n\n" +
                    "这不会拦截真实应用的交互。\n\n" +
                    "最近一次点击：" + getLatestClickText());
        } else {
            tvStatus.setText("❌ 服务未启动");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnStart.setEnabled(accessibilityEnabled);
            btnStop.setEnabled(false);
            btnRequestPermission.setEnabled(!accessibilityEnabled);
            tvClickInfo.setText("点击「启动监听」开始记录\n\n" +
                    "需要先开启无障碍服务\n\n" +
                    "最近一次点击：" + getLatestClickText());
        }
    }

    private String getLatestClickText() {
        ClickAccessibilityService.LatestClick latest = ClickAccessibilityService.getLatestClick();
        if (latest == null) {
            return "暂无";
        }
        return "(" + latest.x + ", " + latest.y + ")  来自 " + latest.source;
    }

    private void showToast(String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRunning = ClickAccessibilityService.isAlive();
        updateUI(isRunning);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentToast != null) {
            currentToast.cancel();
        }
    }
}

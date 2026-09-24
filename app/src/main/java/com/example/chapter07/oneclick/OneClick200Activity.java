package com.example.chapter07.oneclick;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickAccessibilityService;

public class OneClick200Activity extends AppCompatActivity {

    private static final long CLICK_DELAY_MS = 5000L;
    private static final int CLICK_X = 200;
    private static final int CLICK_Y = 200;

    private TextView tvStatus;
    private TextView tvHint;
    private Button btnEnableService;
    private Button btnStartDelayClick;
    private Button btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_click_200);

        initViews();
        setupListeners();
        updateServiceStatus();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvHint = findViewById(R.id.tv_hint);
        btnEnableService = findViewById(R.id.btn_enable_service);
        btnStartDelayClick = findViewById(R.id.btn_start_delay_click);
        btnBack = findViewById(R.id.btn_back);
    }

    private void setupListeners() {
        btnEnableService.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
        });

        btnStartDelayClick.setOnClickListener(v -> startDelayedClick());
        btnBack.setOnClickListener(v -> finish());
    }

    private void startDelayedClick() {
        if (!ensureAccessibilityReady()) {
            return;
        }

        tvHint.setText("已安排 0.5 秒后点击 (" + CLICK_X + ", " + CLICK_Y + ")");
        Toast.makeText(this, "0.5 秒后执行点击", Toast.LENGTH_SHORT).show();
        DelayedClickHelper.clickAfterDelay(CLICK_X, CLICK_Y, CLICK_DELAY_MS);
    }

    private boolean ensureAccessibilityReady() {
        if (ClickAccessibilityService.isAlive()) {
            return true;
        }

        new AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("这个功能依赖无障碍服务来执行全局点击。\n\n请先到设置里开启 Chapter07 的无障碍服务。")
                .setPositiveButton("去开启", (dialog, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("取消", null)
                .show();
        updateServiceStatus();
        return false;
    }

    private void updateServiceStatus() {
        if (ClickAccessibilityService.isAlive()) {
            tvStatus.setText("无障碍服务：已开启");
            btnStartDelayClick.setEnabled(true);
        } else {
            tvStatus.setText("无障碍服务：未开启");
            btnStartDelayClick.setEnabled(false);
        }
    }
}

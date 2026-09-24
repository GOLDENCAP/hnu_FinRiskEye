package com.example.chapter07.myclick;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickAccessibilityService;
import com.example.chapter07.oneclick.DelayedClickHelper;

public class myclick1 extends AppCompatActivity {
    private static final long CLICK_DELAY_MS = 5000L;
    private static final int CLICK_X = 200;
    private static final int CLICK_Y = 200;

    private TextView tvStatus;
    private TextView tvHint;
    private Button btnEnableService;
    private Button btnStartDelayClick;
    private Button btnBack;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_myclick1);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        initViews();
        setupListeners();


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

        btnStartDelayClick.setOnClickListener(v ->dodraw() );
        btnBack.setOnClickListener(v -> finish());
    }

    private void dodraw(){
        //用list来存要绘制的点的集合
        //Array
        //startDelayedClick();
    }
    private void startDelayedClick(int x, int y) {
        if (!ensureAccessibilityReady()) {
            return;
        }

        //tvHint.setText("已安排 0.5 秒后点击 (" + CLICK_X + ", " + CLICK_Y + ")");
        //Toast.makeText(this, "0.5 秒后执行点击", Toast.LENGTH_SHORT).show();
        MAIN_HANDLER.postDelayed(() -> {}, 5000);
        //DelayedClickHelper.clickAfterDelay(CLICK_X, CLICK_Y, CLICK_DELAY_MS);
        //MAIN_HANDLER.postDelayed(() -> ClickAccessibilityService.click(x, y), delayMs)
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
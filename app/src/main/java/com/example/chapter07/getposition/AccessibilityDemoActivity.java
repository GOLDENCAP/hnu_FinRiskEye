package com.example.chapter07.getposition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ComponentName;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import com.example.chapter07.R;
import java.util.List;

public class AccessibilityDemoActivity extends Activity {
    
    private TextView tvStatus;
    private TextView tvClickInfo;
    private TextView tvRecordCount;
    private Button btnEnableService;
    private Button btnClick200;
    private Button btnClick500;
    private Button btnLongPress;
    private Button btnSwipe;
    private Button btnViewRecords;
    private Button btnClearRecords;
    
    private ClickRecordDatabaseHelper dbHelper;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_demo);
        
        // 初始化数据库
        dbHelper = new ClickRecordDatabaseHelper(this);
        
        // 初始化控件
        initViews();
        
        // 设置点击事件
        setupListeners();
        
        // 更新UI状态
        updateServiceStatus();
        updateRecordCount();
    }
    
    private void initViews() {
        tvStatus = findViewById(R.id.tv_service_status);
        tvClickInfo = findViewById(R.id.tv_click_info);
        tvRecordCount = findViewById(R.id.tv_record_count);
        btnEnableService = findViewById(R.id.btn_enable_service);
        btnClick200 = findViewById(R.id.btn_click_200);
        btnClick500 = findViewById(R.id.btn_click_500);
        btnLongPress = findViewById(R.id.btn_long_press);
        btnSwipe = findViewById(R.id.btn_swipe);
        btnViewRecords = findViewById(R.id.btn_view_records);
        btnClearRecords = findViewById(R.id.btn_clear_records);
    }
    








    
    private void setupListeners() {
        // 开启无障碍服务
        btnEnableService.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请找到并开启 \"Chapter07\" 的无障碍服务", Toast.LENGTH_LONG).show();
        });
        
        // 点击 (200, 200)
        btnClick200.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                performSimulatedClick(200, 200);
            }
        });

        // 点击 (500, 800)
        btnClick500.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                performSimulatedClick(500, 800);
            }
        });

        // 长按测试
        btnLongPress.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                ClickAccessibilityService.longPress(300, 500, 1000);
                tvClickInfo.setText("长按模拟:\n坐标 (300, 500)\n持续时间 1000ms");
                dbHelper.insertClickRecord(300, 500);
                updateRecordCount();
                Toast.makeText(this, "已在 (300, 500) 处模拟长按", Toast.LENGTH_SHORT).show();
            }
        });

        // 滑动测试
        btnSwipe.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                ClickAccessibilityService.swipe(200, 500, 200, 200, 500);
                tvClickInfo.setText("滑动模拟:\n从 (200, 500) 到 (200, 200)");
                Toast.makeText(this, "已模拟滑动", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 查看记录
        btnViewRecords.setOnClickListener(v -> showRecordsDialog());
        
        // 清空记录
        btnClearRecords.setOnClickListener(v -> clearAllRecords());
    }
















    
    private boolean checkServiceAndClick() {
        if (ClickAccessibilityService.isAlive()) {
            return true;
        } else {
            showServiceNotEnabledDialog();
            return false;
        }
    }

    /**
     * 检查无障碍服务是否在设置中开启
     */
    private boolean checkServiceEnabled() {
        AccessibilityManager accessibilityManager =
            (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);

        if (accessibilityManager == null) return false;

        // 获取已启用的无障碍服务列表
        List<AccessibilityServiceInfo> enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        if (enabledServices == null) return false;

        // 检查我们的服务是否在列表中
        String serviceId = ClickAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getId() != null && info.getId().contains(serviceId)) {
                return true;
            }
        }

        // 如果服务进程已经在运行，直接返回 true
        if (ClickAccessibilityService.isAlive()) {
            return true;
        }

        // 服务已开启但尚未运行，提示用户尝试点击
        new AlertDialog.Builder(this)
            .setTitle("服务正在启动")
            .setMessage("无障碍服务已开启，正在启动中...\n\n请稍等1-2秒后再次点击按钮。\n\n如果多次尝试仍不工作，请尝试重启手机。")
            .setPositiveButton("我知道了", null)
            .show();
        return false;
    }

    private void performSimulatedClick(int x, int y) {
        ClickAccessibilityService.click(x, y);
        tvClickInfo.setText("模拟点击:\n坐标 (" + x + ", " + y + ")");
        dbHelper.insertClickRecord(x, y);
        updateRecordCount();
        Toast.makeText(this, "已在 (" + x + ", " + y + ") 处模拟点击", Toast.LENGTH_SHORT).show();
    }
    
    private void showServiceNotEnabledDialog() {
        new AlertDialog.Builder(this)
            .setTitle("无障碍服务未开启")
            .setMessage("请先开启无障碍服务，才能使用模拟点击功能。\n\n开启步骤：\n1. 点击「开启无障碍服务」按钮\n2. 找到并点击 \"Chapter07\"\n3. 开启服务开关")
            .setPositiveButton("去开启", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void updateServiceStatus() {
        if (ClickAccessibilityService.isAlive()) {
            tvStatus.setText("● 无障碍服务状态：已开启");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnEnableService.setEnabled(false);
            btnEnableService.setText("服务已开启");
        } else {
            tvStatus.setText("○ 无障碍服务状态：未开启");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnEnableService.setEnabled(true);
            btnEnableService.setText("开启无障碍服务");
        }
    }
    
    private void updateRecordCount() {
        int count = dbHelper.getRecordCount();
        tvRecordCount.setText("📊 总记录数：" + count);
    }
    
    private void showRecordsDialog() {
        List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
        
        if (records.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("点击记录")
                .setMessage("暂无任何点击记录\n\n点击屏幕或使用模拟点击按钮会产生记录")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        StringBuilder message = new StringBuilder();
        message.append("📝 共有 ").append(records.size()).append(" 条记录：\n\n");
        
        for (int i = 0; i < Math.min(records.size(), 30); i++) {
            ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
            message.append(i + 1).append(". ");
            message.append("📌 (").append(record.x).append(", ").append(record.y).append(")\n");
            message.append("   🕐 ").append(record.timestamp).append("\n\n");
        }
        
        if (records.size() > 30) {
            message.append("... 仅显示最近30条，共").append(records.size()).append("条");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("点击记录")
            .setMessage(message.toString())
            .setPositiveButton("确定", null)
            .setNeutralButton("复制", (dialog, which) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Click Records", message.toString());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
    
    private void clearAllRecords() {
        new AlertDialog.Builder(this)
            .setTitle("清空记录")
            .setMessage("确定要清空所有点击记录吗？此操作不可恢复。")
            .setPositiveButton("确定", (dialog, which) -> {
                dbHelper.clearAllRecords();
                updateRecordCount();
                tvClickInfo.setText("所有记录已清空");
                Toast.makeText(this, "已清空所有记录", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 每次返回页面时更新服务状态
        updateServiceStatus();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}
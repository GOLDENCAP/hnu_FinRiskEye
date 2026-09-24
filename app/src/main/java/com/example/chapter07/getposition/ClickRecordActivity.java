package com.example.chapter07.getposition;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.chapter07.R;

import java.util.List;

public class ClickRecordActivity extends AppCompatActivity {
    
    private static final String TAG = "ClickRecord";
    private static final int REQUEST_IGNORE_BATTERY = 1001;
    private static final int REQUEST_POST_NOTIFICATIONS = 1002;
    private static final int REQUEST_FOREGROUND_SERVICE = 1003;
    
    private TextView tvStatus;
    private TextView tvClickInfo;
    private Button btnStartRecord;
    private Button btnReset;
    private Button btnOpenAccessibility;
    
    private boolean isServiceEnabled = false;
    private Toast currentToast = null;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_click_record);
        
        initViews();
        setupListeners();
        checkAccessibilityService();
         Log.d(TAG, "已经执行到录制");
        setupRecordListener();
        
        // 请求所有必要权限
        checkAndRequestPermissions();
    }
    
    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvClickInfo = findViewById(R.id.tv_click_info);
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnReset = findViewById(R.id.btn_reset);
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);
    }
    
    private void setupListeners() {
        btnStartRecord.setOnClickListener(v -> {
            if (isServiceEnabled) {
                // 确保通知渠道已创建
                createNotificationChannel();
                ClickRecordService.startRecording();
                updateClickInfo(0, 0, 0);
                tvStatus.setText("✅ 服务运行中，点击屏幕任意位置将记录");
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
                showToast("开始记录，将记录5次点击");
                Log.d(TAG, "用户点击了开始记录按钮");
            } else {
                showToast("请先开启无障碍服务");
            }
        });
        
        btnReset.setOnClickListener(v -> {
            ClickRecordService.resetCount();
            updateClickInfo(0, 0, 0);
            tvStatus.setText("✅ 已重置计数");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            showToast("已重置记录次数，可以重新开始记录");
            Log.d(TAG, "用户点击了重置按钮");
        });
        
        btnOpenAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            showToast("请在列表中找到 \"" + getString(R.string.app_name) + "\" 并开启无障碍服务");
        });
    }
    
    private void showToast(String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        currentToast.show();
        Log.d(TAG, "Toast: " + message);
    }
    
    private void checkAccessibilityService() {
        String serviceName = getPackageName() + "/" + 
            ClickRecordService.class.getCanonicalName();
        
        AccessibilityManager am = (AccessibilityManager) 
            getSystemService(Context.ACCESSIBILITY_SERVICE);
        
        List<AccessibilityServiceInfo> enabledServices = 
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        
        isServiceEnabled = false;
        for (AccessibilityServiceInfo info : enabledServices) {
            String enabledServiceName = info.getResolveInfo().serviceInfo.packageName + "/" +
                                       info.getResolveInfo().serviceInfo.name;
            Log.d(TAG, "已启用的服务: " + enabledServiceName);
            if (serviceName.equals(enabledServiceName)) {
                isServiceEnabled = true;
                break;
            }
        }
        
        Log.d(TAG, "服务是否启用: " + isServiceEnabled);
        updateUIByServiceState();
    }
    
    private void updateUIByServiceState() {
        if (isServiceEnabled) {
            tvStatus.setText("✅ 无障碍服务已开启");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnOpenAccessibility.setEnabled(false);
            btnOpenAccessibility.setText("已开启");
            btnStartRecord.setEnabled(true);
            btnReset.setEnabled(true);
        } else {
            tvStatus.setText("❌ 无障碍服务未开启，请先开启");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnOpenAccessibility.setEnabled(true);
            btnOpenAccessibility.setText("开启无障碍服务");
            btnStartRecord.setEnabled(false);
            btnReset.setEnabled(false);
        }
    }
    
    private void setupRecordListener() {
        ClickRecordService.setOnRecordListener(new ClickRecordService.OnRecordListener() {
            @Override
            public void onRecord(int x, int y, int currentCount) {
                runOnUiThread(() -> {
                    updateClickInfo(x, y, currentCount);
                    String message = "记录 " + currentCount + "/5: (" + x + ", " + y + ")";
                    showToast(message);
                    Log.d(TAG, message);
                });
            }
            
            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    tvStatus.setText("🎉 已完成5次记录！点击\"开始记录\"继续");
                    tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                    showToast("已完成5次记录，如需继续请点击\"开始记录\"");
                    Log.d(TAG, "录制完成");
                });
            }
        });
    }
    
    private void updateClickInfo(int x, int y, int count) {
        if (count == 0) {
            tvClickInfo.setText("等待记录...\n点击屏幕任意View开始记录");
            return;
        }
        
        String info = "📝 已记录次数: " + count + " / 5\n";
        info += "📍 上次坐标: (" + x + ", " + y + ")\n";
        info += "⚠️ 注意：记录的是View中心点\n";
        if (count < 5) {
            info += "✨ 还需记录 " + (5 - count) + " 次";
        } else {
            info += "🎉 记录完成！";
        }
        tvClickInfo.setText(info);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        checkAccessibilityService();
        int currentCount = ClickRecordService.getClickCount();
        Log.d(TAG, "当前记录次数: " + currentCount);
        
        if (currentCount > 0 && currentCount < 5) {
            updateClickInfo(0, 0, currentCount);
            tvStatus.setText("⏳ 服务运行中，还剩 " + (5 - currentCount) + " 次");
            tvStatus.setTextColor(getColor(android.R.color.holo_blue_dark));
        } else if (currentCount >= 5) {
            updateClickInfo(0, 0, currentCount);
            tvStatus.setText("🎉 已完成5次记录，点击\"开始记录\"继续");
            tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        ClickRecordService.setOnRecordListener(null);
        if (currentToast != null) {
            currentToast.cancel();
        }
    }
    
    /**
     * 检查并请求所有必要权限
     */
    private void checkAndRequestPermissions() {
        // 1. 请求忽略电池优化（最重要）
        requestIgnoreBatteryOptimizations();
        
        // 2. Android 13+ 需要通知权限才能显示前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkAndRequestNotificationPermission();
        }
        
        // 3. Android 14+ 前台服务类型需要特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            checkAndRequestForegroundServicePermission();
        }
    }
    
    /**
     * 请求忽略电池优化权限
     */
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivityForResult(intent, REQUEST_IGNORE_BATTERY);
                
                Log.d(TAG, "请求忽略电池优化权限");
                showToast("建议允许忽略电池优化，以保证后台稳定运行");
            } else {
                Log.d(TAG, "已经忽略电池优化");
            }
        }
    }
    
    /**
     * Android 13+ 请求通知权限
     */
    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
                
                Log.d(TAG, "请求通知权限");
            }
        }
    }
    
    /**
     * Android 14+ 请求前台服务权限
     */
    private void checkAndRequestForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
                    != PackageManager.PERMISSION_GRANTED) {
                
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC},
                        REQUEST_FOREGROUND_SERVICE);
                
                Log.d(TAG, "请求前台服务权限");
            }
        }
    }
    
    /**
     * 创建通知渠道（前台服务必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "click_record_channel";
            String channelName = "点击记录服务";
            int importance = NotificationManager.IMPORTANCE_LOW;
            
            NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
            channel.setDescription("用于记录点击位置的前台服务");
            manager.createNotificationChannel(channel);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_IGNORE_BATTERY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                String packageName = getPackageName();
                
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d(TAG, "用户已允许忽略电池优化");
                    showToast("已获得电池优化豁免，应用可在后台稳定运行");
                } else {
                    Log.d(TAG, "用户拒绝了忽略电池优化");
                    showToast("未获得电池优化豁免，后台运行可能受限");
                }
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        switch (requestCode) {
            case REQUEST_POST_NOTIFICATIONS:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "通知权限已授予");
                    showToast("已获得通知权限");
                } else {
                    Log.d(TAG, "通知权限被拒绝");
                    showToast("通知权限被拒绝，前台服务通知可能无法显示");
                }
                break;
                
            case REQUEST_FOREGROUND_SERVICE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "前台服务权限已授予");
                } else {
                    Log.d(TAG, "前台服务权限被拒绝");
                }
                break;
        }
    }
}
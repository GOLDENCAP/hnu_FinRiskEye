package com.example.chapter07.getposition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.chapter07.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BackgroundRecordActivity extends Activity {

    private TextView tvServiceStatus;
    private TextView tvRecordCount;
    private TextView tvTodayCount;
    private TextView tvLog;
    private TextView tvCurrentSession;
    private Button btnSaveSession;
    private Button btnStartRecord;
    private Button btnStopRecord;
    private Button btnViewDates;
    private Button btnReturn;

    private Handler mainHandler;
    private boolean isRecording = false;
    private List<String> logLines = new ArrayList<>();
    private static final int MAX_LOG_LINES = 100;
    private String currentSessionTimestamp = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_record);

        mainHandler = new Handler(Looper.getMainLooper());
        initViews();
        setupListeners();
        updateServiceStatus();
        updateTodayCount();
        updateCurrentSessionDisplay();
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvRecordCount = findViewById(R.id.tv_record_count);
        tvTodayCount = findViewById(R.id.tv_today_count);
        tvLog = findViewById(R.id.tv_log);
        tvCurrentSession = findViewById(R.id.tv_current_session);
        btnSaveSession = findViewById(R.id.btn_save_session);
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);
        btnViewDates = findViewById(R.id.btn_view_dates);
        btnReturn = findViewById(R.id.btn_return);
    }

    private void setupListeners() {
        btnReturn.setOnClickListener(v -> finish());
        btnSaveSession.setOnClickListener(v -> saveSession());
        btnStartRecord.setOnClickListener(v -> {
            if (currentSessionTimestamp == null) {
                Toast.makeText(this, "请先点击「保存并开始记录」创建新会话", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checkAccessibilityService() && checkOverlayPermission()) {
                startAlternatingRecord();
            }
        });
        btnStopRecord.setOnClickListener(v -> stopRecording());
        btnViewDates.setOnClickListener(v -> showAllDates());
    }

    private boolean checkAccessibilityService() {
        if (ClickAccessibilityService.isAlive()) {
            return true;
        }

        new AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("后台记录需要无障碍服务支持。\n\n请先开启无障碍服务。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
        return false;
    }

    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("悬浮窗权限未开启")
                    .setMessage("第二次、第四次等悬浮窗模式记录需要悬浮窗权限。\n\n请授予「在其他应用的上层显示」权限。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return false;
        }
        return true;
    }

    private void startAlternatingRecord() {
        appendLog("开始交替记录...");
        appendLog("会话: " + currentSessionTimestamp);

        AlternatingRecordService.start(this, currentSessionTimestamp, AlternatingRecordService.MODE_ACCESSIBILITY);

        isRecording = true;
        btnSaveSession.setEnabled(false);
        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Toast.makeText(this, "开始交替记录：无障碍 -> 悬浮窗 -> 无障碍", Toast.LENGTH_LONG).show();
    }

    private void saveSession() {
        currentSessionTimestamp = DailyClickRecordDatabaseHelper.getCurrentTimestamp();
        DailyClickRecordDatabaseHelper dbHelper = new DailyClickRecordDatabaseHelper(this, currentSessionTimestamp);
        dbHelper.getWritableDatabase();
        dbHelper.close();

        updateCurrentSessionDisplay();
        updateRecordCountForSession();
        appendLog("已创建新会话: " + DailyClickRecordDatabaseHelper.getDisplayTimestamp(currentSessionTimestamp));
        Toast.makeText(this, "新会话已创建，可开始记录", Toast.LENGTH_SHORT).show();
    }

    private void updateCurrentSessionDisplay() {
        if (currentSessionTimestamp != null) {
            String display = DailyClickRecordDatabaseHelper.getDisplayTimestamp(currentSessionTimestamp);
            tvCurrentSession.setText("当前会话: " + display);
        } else {
            tvCurrentSession.setText("当前会话: 未创建");
        }
    }

    private void updateRecordCountForSession() {
        if (currentSessionTimestamp != null) {
            int count = DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, currentSessionTimestamp);
            tvRecordCount.setText("会话记录数: " + count + " 条");
        } else {
            tvRecordCount.setText("会话记录数: 0 条");
        }
    }

    private void stopRecording() {
        AlternatingRecordService.stop(this);
        BackgroundRecordService.stopRecording(this);
        TouchInterceptorService.stopIntercepting(this);

        isRecording = false;
        btnSaveSession.setEnabled(true);
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        appendLog("已停止记录");
        updateRecordCountForSession();
        updateTodayCount();
        Toast.makeText(this, "已停止记录", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) {
            stopRecording();
        }
    }

    @Override
    public void onBackPressed() {
        if (isRecording) {
            stopRecording();
        }
        super.onBackPressed();
    }

    private void updateServiceStatus() {
        if (ClickAccessibilityService.isAlive()) {
            tvServiceStatus.setText("● 无障碍服务：已开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnStartRecord.setEnabled(true);
        } else {
            tvServiceStatus.setText("○ 无障碍服务：未开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnStartRecord.setEnabled(false);
        }
    }

    private void updateTodayCount() {
        int count = 0;
        List<String> timestamps = DailyClickRecordDatabaseHelper.getAllTimestamps(this);
        for (String ts : timestamps) {
            count += DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, ts);
        }
        tvTodayCount.setText("今日记录: " + count + " 条");
    }

    private void showAllDates() {
        List<String> timestamps = DailyClickRecordDatabaseHelper.getAllTimestamps(this);
        if (timestamps.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("所有会话记录")
                    .setMessage("暂无任何记录。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        StringBuilder message = new StringBuilder();
        for (String ts : timestamps) {
            int count = DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, ts);
            String display = DailyClickRecordDatabaseHelper.getDisplayTimestamp(ts);
            message.append(display).append(" - ").append(count).append(" 条记录\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("所有会话记录")
                .setMessage(message.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String time = sdf.format(new Date());
        String logEntry = "[" + time + "] " + message;

        logLines.add(logEntry);
        if (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }

        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String line : logLines) {
                sb.append(line).append("\n");
            }
            tvLog.setText(sb.toString());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateTodayCount();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording) {
            stopRecording();
        }
    }
}

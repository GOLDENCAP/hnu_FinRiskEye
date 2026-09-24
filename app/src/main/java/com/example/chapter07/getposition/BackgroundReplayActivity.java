package com.example.chapter07.getposition;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 后台点击回放Activity
 * 用于配置和启动后台点击回放服务
 */
public class BackgroundReplayActivity extends AppCompatActivity {

    private TextView tvServiceStatus;
    private TextView tvRecordCount;
    private TextView tvProgress;
    private TextView tvCurrentClick;
    private ProgressBar progressBar;
    private EditText etDelay;
    private EditText etLoopCount;
    private Button btnStartReplay;
    private Button btnStopReplay;
    private Button btnReturn;
    private Button btnSelectDatabase;
    private TextView tvLog;
    private TextView tvSelectedDatabase;

    private Handler mainHandler;
    private boolean isReplaying = false;

    // 当前选择的数据库时间戳（null表示使用默认数据库）
    private String selectedTimestamp = null;

    // 日志相关
    private List<String> logLines = new ArrayList<>();
    private static final int MAX_LOG_LINES = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_replay);

        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupListeners();
        updateServiceStatus();
        updateRecordCount();
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvRecordCount = findViewById(R.id.tv_record_count);
        tvProgress = findViewById(R.id.tv_progress);
        tvCurrentClick = findViewById(R.id.tv_current_click);
        progressBar = findViewById(R.id.progress_bar);
        etDelay = findViewById(R.id.et_delay);
        etLoopCount = findViewById(R.id.et_loop_count);
        btnStartReplay = findViewById(R.id.btn_start_replay);
        btnStopReplay = findViewById(R.id.btn_stop_replay);
        btnReturn = findViewById(R.id.btn_return);
        tvLog = findViewById(R.id.tv_log);
        btnSelectDatabase = findViewById(R.id.btn_select_date);
        tvSelectedDatabase = findViewById(R.id.tv_selected_date);
    }

    private void setupListeners() {
        // 返回按钮
        btnReturn.setOnClickListener(v -> finish());

        // 启动回放
        btnStartReplay.setOnClickListener(v -> startBackgroundReplay());

        // 停止回放
        btnStopReplay.setOnClickListener(v -> stopBackgroundReplay());

        // 选择数据库
        btnSelectDatabase.setOnClickListener(v -> showDatabaseSelector());
    }

    private boolean checkAccessibilityService() {
        if (ClickAccessibilityService.isAlive()) {
            return true;
        }

        new AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("后台回放需要无障碍服务支持。\n\n请先开启无障碍服务。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
        return false;
    }

    /**
     * 显示数据库选择器
     */
    private void showDatabaseSelector() {
        List<String> timestamps = BackgroundClickService.getAllTimestamps(this);

        if (timestamps.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("选择数据库")
                    .setMessage("暂无历史记录数据库。\n\n将使用默认数据库回放。")
                    .setPositiveButton("使用默认数据库", (dialog, which) -> {
                        selectedTimestamp = null;
                        updateSelectedDatabaseDisplay();
                        updateRecordCountForTimestamp();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        // 构建选项列表
        String[] items = new String[timestamps.size()];
        for (int i = 0; i < timestamps.size(); i++) {
            String ts = timestamps.get(i);
            int count = DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, ts);
            String display = DailyClickRecordDatabaseHelper.getDisplayTimestamp(ts);
            items[i] = display + " (" + count + " 条记录)";
        }

        new AlertDialog.Builder(this)
                .setTitle("选择数据库（历史记录）")
                .setItems(items, (dialog, which) -> {
                    selectedTimestamp = timestamps.get(which);
                    updateSelectedDatabaseDisplay();
                    updateRecordCountForTimestamp();
                })
                .setPositiveButton("使用默认数据库", (dialog, which) -> {
                    selectedTimestamp = null;
                    updateSelectedDatabaseDisplay();
                    updateRecordCount();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSelectedDatabaseDisplay() {
        if (selectedTimestamp != null) {
            String display = DailyClickRecordDatabaseHelper.getDisplayTimestamp(selectedTimestamp);
            tvSelectedDatabase.setText("已选择: " + display + " 的记录");
            tvSelectedDatabase.setVisibility(View.VISIBLE);
        } else {
            tvSelectedDatabase.setText("使用默认数据库");
            tvSelectedDatabase.setVisibility(View.VISIBLE);
        }
    }

    private void updateRecordCountForTimestamp() {
        if (selectedTimestamp != null) {
            int count = DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, selectedTimestamp);
            tvRecordCount.setText("记录数：" + count + " 条");
        }
    }

    private void startBackgroundReplay() {
        // 检查无障碍服务
        if (!checkAccessibilityService()) {
            return;
        }

        // 检查记录
        int recordCount;
        if (selectedTimestamp != null) {
            recordCount = DailyClickRecordDatabaseHelper.getRecordCountForTimestamp(this, selectedTimestamp);
        } else {
            ClickRecordDatabaseHelper dbHelper = new ClickRecordDatabaseHelper(this);
            recordCount = dbHelper.getRecordCount();
            dbHelper.close();
        }

        if (recordCount == 0) {
            Toast.makeText(this, "没有可回放的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取参数
        int delay = 500;
        int loopCount = 1;

        String delayStr = etDelay.getText().toString().trim();
        if (!delayStr.isEmpty()) {
            try {
                delay = Integer.parseInt(delayStr);
            } catch (NumberFormatException ignored) {}
        }

        String loopStr = etLoopCount.getText().toString().trim();
        if (!loopStr.isEmpty()) {
            try {
                loopCount = Integer.parseInt(loopStr);
            } catch (NumberFormatException ignored) {}
        }

        final int finalDelay = delay;
        final int finalLoopCount = loopCount;
        final String dbInfo = selectedTimestamp != null
                ? DailyClickRecordDatabaseHelper.getDisplayTimestamp(selectedTimestamp) + " 的记录"
                : "默认数据库";

        // 确认启动
        new AlertDialog.Builder(this)
                .setTitle("确认启动")
                .setMessage("即将在后台开始回放：\n\n" +
                        "- 数据源：" + dbInfo + "\n" +
                        "- 记录数：" + recordCount + " 条\n" +
                        "- 循环次数：" + loopCount + " 次\n" +
                        "- 间隔：" + delay + " 毫秒\n\n" +
                        "程序将切换到后台运行，回放过程中可随时查看通知栏停止。")
                .setPositiveButton("启动", (dialog, which) -> doStartReplay(finalDelay, finalLoopCount))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doStartReplay(int delay, int loopCount) {
        // 清空日志
        clearLog();
        appendLog("开始后台回放...");
        appendLog("间隔: " + delay + "ms, 循环: " + loopCount + "次");
        if (selectedTimestamp != null) {
            appendLog("数据源: " + DailyClickRecordDatabaseHelper.getDisplayTimestamp(selectedTimestamp));
        } else {
            appendLog("数据源: 默认数据库");
        }

        // 设置日志回调，从服务接收日志
        BackgroundClickService.setLogCallback(this::appendLog);

        // 更新UI
        isReplaying = true;
        btnStartReplay.setEnabled(false);
        btnStopReplay.setEnabled(true);
        tvProgress.setText("正在启动后台回放...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvCurrentClick.setVisibility(View.VISIBLE);
        tvLog.setVisibility(View.VISIBLE);

        // 启动后台服务（传入选择的数据库时间戳）
        BackgroundClickService.startReplay(this, delay, loopCount, selectedTimestamp);

        // 进入后台
        moveTaskToBack(true);

        Toast.makeText(this, "后台回放已启动，请查看通知栏", Toast.LENGTH_LONG).show();
    }

    private void stopBackgroundReplay() {
        BackgroundClickService.stopReplay(this);

        isReplaying = false;
        btnStartReplay.setEnabled(true);
        btnStopReplay.setEnabled(false);
        tvProgress.setText("已停止回放");
        progressBar.setVisibility(View.GONE);
        tvCurrentClick.setVisibility(View.GONE);

        Toast.makeText(this, "已停止后台回放", Toast.LENGTH_SHORT).show();
    }

    private void updateServiceStatus() {
        if (ClickAccessibilityService.isAlive()) {
            tvServiceStatus.setText("● 无障碍服务：已开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnStartReplay.setEnabled(true);
        } else {
            tvServiceStatus.setText("○ 无障碍服务：未开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnStartReplay.setEnabled(false);
        }
    }

    private void updateRecordCount() {
        ClickRecordDatabaseHelper dbHelper = new ClickRecordDatabaseHelper(this);
        int count = dbHelper.getRecordCount();
        dbHelper.close();
        tvRecordCount.setText("记录数：" + count + " 条");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateRecordCount();
    }

    // ========== 日志相关 ==========

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

    private void clearLog() {
        logLines.clear();
        runOnUiThread(() -> tvLog.setText("等待回放开始..."));
    }
}

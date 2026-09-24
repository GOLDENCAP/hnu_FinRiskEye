package com.example.chapter07.getposition;

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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

import java.util.List;
import java.util.Locale;

public class ClickReplayActivity extends AppCompatActivity {

    private TextView tvServiceStatus;
    private TextView tvRecordCount;
    private TextView tvProgress;
    private TextView tvCurrentClick;
    private ProgressBar progressBar;
    private EditText etDelay;
    private EditText etLoopCount;
    private Button btnExportJson;
    private Button btnExportCsv;
    private Button btnExportTxt;
    private Button btnReplayAll;
    private Button btnCancelReplay;
    private Button btnReplayLoop;
    private Button btnReturn;

    private ClickReplayService replayService;
    private ClickRecordDatabaseHelper dbHelper;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_click_replay);

        mainHandler = new Handler(Looper.getMainLooper());
        dbHelper = new ClickRecordDatabaseHelper(this);

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
        btnExportJson = findViewById(R.id.btn_export_json);
        btnExportCsv = findViewById(R.id.btn_export_csv);
        btnExportTxt = findViewById(R.id.btn_export_txt);
        btnReplayAll = findViewById(R.id.btn_replay_all);
        btnCancelReplay = findViewById(R.id.btn_cancel_replay);
        btnReplayLoop = findViewById(R.id.btn_replay_loop);
        btnReturn = findViewById(R.id.btn_return);
    }

    private void setupListeners() {
        // 返回按钮
        btnReturn.setOnClickListener(v -> finish());

        // 导出JSON
        btnExportJson.setOnClickListener(v -> exportData("json"));

        // 导出CSV
        btnExportCsv.setOnClickListener(v -> exportData("csv"));

        // 导出TXT
        btnExportTxt.setOnClickListener(v -> exportData("txt"));

        // 全部回放
        btnReplayAll.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                startReplayAll();
            }
        });

        // 取消回放
        btnCancelReplay.setOnClickListener(v -> {
            if (replayService != null) {
                replayService.cancelReplay();
            }
        });

        // 循环回放
        btnReplayLoop.setOnClickListener(v -> {
            if (checkServiceEnabled()) {
                startReplayLoop();
            }
        });
    }

    private boolean checkServiceEnabled() {
        if (ClickAccessibilityService.isAlive()) {
            return true;
        }

        new AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("请先开启无障碍服务，才能使用模拟点击功能。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
        return false;
    }

    private void updateServiceStatus() {
        if (ClickAccessibilityService.isAlive()) {
            tvServiceStatus.setText("● 无障碍服务状态：已开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            tvServiceStatus.setText("○ 无障碍服务状态：未开启");
            tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }
    }

    private void updateRecordCount() {
        int count = dbHelper.getRecordCount();
        tvRecordCount.setText("📊 总记录数：" + count);
    }

    private void exportData(String format) {
        String fileName = "click_records_" + System.currentTimeMillis();
        final String finalFormat = format;

        tvProgress.setText("正在导出" + finalFormat.toUpperCase() + "...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        setButtonsEnabled(false);

        ClickReplayService.ExportCallback callback = new ClickReplayService.ExportCallback() {
            @Override
            public void onExportStart() {
                mainHandler.post(() -> {
                    tvProgress.setText("正在导出" + finalFormat.toUpperCase() + "...");
                });
            }

            @Override
            public void onExportProgress(int current, int total) {
                mainHandler.post(() -> {
                    int progress = (int) ((current / (float) total) * 100);
                    progressBar.setProgress(progress);
                    tvProgress.setText(String.format(Locale.getDefault(),
                            "导出进度: %d / %d", current, total));
                });
            }

            @Override
            public void onExportComplete(String filePath) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    setButtonsEnabled(true);
                    tvProgress.setText("导出完成！");
                    Toast.makeText(ClickReplayActivity.this,
                            "文件已保存到:\n" + filePath, Toast.LENGTH_LONG).show();

                    // 显示分享选项
                    showShareDialog(filePath);
                });
            }

            @Override
            public void onExportError(String error) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    setButtonsEnabled(true);
                    tvProgress.setText("导出失败: " + error);
                    Toast.makeText(ClickReplayActivity.this,
                            "导出失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        };

        // 由于ClickReplayService是AccessibilityService，需要特殊处理
        // 这里使用直接操作数据库的方式导出
        runExportInBackground(format, fileName, callback);
    }

    private void runExportInBackground(String format, String fileName,
                                       ClickReplayService.ExportCallback callback) {
        final String finalFormat = format;
        final String finalFileName = fileName;

        new Thread(() -> {
            try {
                mainHandler.post(() -> callback.onExportStart());

                List<ClickRecordDatabaseHelper.ClickRecord> records =
                        dbHelper.getAllClickRecords();
                int total = records.size();

                StringBuilder content = new StringBuilder();

                if ("json".equals(finalFormat)) {
                    content.append("[\n");
                    for (int i = 0; i < total; i++) {
                        ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                        content.append("  {\"id\": ").append(record.id)
                                .append(", \"x\": ").append(record.x)
                                .append(", \"y\": ").append(record.y)
                                .append(", \"timestamp\": \"").append(record.timestamp)
                                .append("\"}");
                        if (i < total - 1) content.append(",");
                        content.append("\n");

                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                    }
                    content.append("]");
                } else if ("csv".equals(finalFormat)) {
                    content.append("ID,X,Y,Timestamp\n");
                    for (int i = 0; i < total; i++) {
                        ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                        content.append(record.id).append(",")
                                .append(record.x).append(",")
                                .append(record.y).append(",\"")
                                .append(record.timestamp).append("\"\n");
                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                    }
                } else {
                    content.append("========== 点击记录导出 ==========\n");
                    content.append("总记录数: ").append(total).append("\n");
                    content.append("================================\n\n");
                    for (int i = 0; i < total; i++) {
                        ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                        content.append(String.format(Locale.getDefault(),
                                "[%04d] 坐标: (%d, %d)  时间: %s\n",
                                i + 1, record.x, record.y, record.timestamp));
                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                    }
                }

                // 保存文件
                String ext = "." + finalFormat;
                String fullFileName = finalFileName + ext;
                String filePath = saveExportFile(fullFileName, content.toString());

                mainHandler.post(() -> callback.onExportComplete(filePath));

            } catch (Exception e) {
                mainHandler.post(() -> callback.onExportError(e.getMessage()));
            }
        }).start();
    }

    private String saveExportFile(String fileName, String content) {
        java.io.File exportDir = new java.io.File(
                getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        java.io.File file = new java.io.File(exportDir, fileName);
        try {
            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.write(content);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return file.getAbsolutePath();
    }

    private void showShareDialog(String filePath) {
        final String finalFilePath = filePath;
        new AlertDialog.Builder(this)
                .setTitle("导出成功")
                .setMessage("文件已保存到:\n" + finalFilePath)
                .setPositiveButton("确定", null)
                .setNeutralButton("分享", (dialog, which) -> {
                    shareFile(finalFilePath);
                })
                .show();
    }

    private void shareFile(String filePath) {
        final java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "分享记录"));
    }

    private void startReplayAll() {
        int delay = 500;
        try {
            delay = Integer.parseInt(etDelay.getText().toString());
        } catch (NumberFormatException e) {
            delay = 500;
        }

        final int finalDelay = delay;
        tvProgress.setText("准备开始回放...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvCurrentClick.setVisibility(View.VISIBLE);
        btnCancelReplay.setEnabled(true);
        setReplayButtonsEnabled(false);

        List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
        if (records.isEmpty()) {
            Toast.makeText(this, "没有可回放的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        final int total = records.size();
        final int[] successCount = {0};
        final int[] failCount = {0};
        final boolean[] cancelled = {false};

        // 直接使用ClickAccessibilityService进行回放
        new Thread(() -> {
            for (int i = 0; i < total; i++) {
                if (cancelled[0]) {
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        tvProgress.setText("回放已取消");
                        tvCurrentClick.setVisibility(View.GONE);
                        btnCancelReplay.setEnabled(false);
                        setReplayButtonsEnabled(true);
                    });
                    return;
                }

                ClickRecordDatabaseHelper.ClickRecord record = records.get(i);

                // 使用ClickAccessibilityService模拟点击
                boolean success = ClickAccessibilityService.click(record.x, record.y);
                if (success) {
                    successCount[0]++;
                } else {
                    failCount[0]++;
                }

                int progress = (int) ((i + 1) / (float) total * 100);
                final int finalI = i;
                final int recordX = record.x;
                final int recordY = record.y;
                mainHandler.post(() -> {
                    progressBar.setProgress(progress);
                    tvProgress.setText(String.format(Locale.getDefault(),
                            "回放进度: %d / %d", finalI + 1, total));
                    tvCurrentClick.setText("当前点击: (" + recordX + ", " + recordY + ")");
                });

                if (i < total - 1 && finalDelay > 0) {
                    try {
                        Thread.sleep(finalDelay);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                tvCurrentClick.setVisibility(View.GONE);
                btnCancelReplay.setEnabled(false);
                setReplayButtonsEnabled(true);
                tvProgress.setText(String.format(Locale.getDefault(),
                        "回放完成! 成功: %d, 失败: %d", successCount[0], failCount[0]));
                Toast.makeText(ClickReplayActivity.this,
                        "回放完成!", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void startReplayLoop() {
        int loopCount = 3;
        int delay = 500;
        try {
            loopCount = Integer.parseInt(etLoopCount.getText().toString());
            delay = Integer.parseInt(etDelay.getText().toString());
        } catch (NumberFormatException e) {
            loopCount = 3;
            delay = 500;
        }

        final int finalLoopCount = loopCount;
        final int finalDelay = delay;
        tvProgress.setText(String.format(Locale.getDefault(), "准备开始循环回放 (%d次)...", loopCount));
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvCurrentClick.setVisibility(View.VISIBLE);
        btnCancelReplay.setEnabled(true);
        setReplayButtonsEnabled(false);

        List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
        if (records.isEmpty()) {
            Toast.makeText(this, "没有可回放的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        final int total = records.size();
        final int totalOperations = total * loopCount;
        final int[] successCount = {0};
        final int[] failCount = {0};
        final boolean[] cancelled = {false};

        new Thread(() -> {
            for (int loop = 0; loop < finalLoopCount; loop++) {
                if (cancelled[0]) {
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        tvProgress.setText("循环回放已取消");
                        tvCurrentClick.setVisibility(View.GONE);
                        btnCancelReplay.setEnabled(false);
                        setReplayButtonsEnabled(true);
                    });
                    return;
                }

                final int currentLoop = loop + 1;
                mainHandler.post(() -> {
                    tvProgress.setText(String.format(Locale.getDefault(),
                            "第 %d / %d 轮回放", currentLoop, finalLoopCount));
                });

                for (int i = 0; i < total; i++) {
                    if (cancelled[0]) {
                        break;
                    }

                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    boolean success = ClickAccessibilityService.click(record.x, record.y);
                    if (success) {
                        successCount[0]++;
                    } else {
                        failCount[0]++;
                    }

                    int currentOp = loop * total + i + 1;
                    int progress = (int) (currentOp / (float) totalOperations * 100);
                    final int finalI = i;
                    final int recordX = record.x;
                    final int recordY = record.y;
                    mainHandler.post(() -> {
                        progressBar.setProgress(progress);
                        tvCurrentClick.setText(String.format(Locale.getDefault(),
                                "第%d轮 [%d/%d] 点击: (%d, %d)",
                                currentLoop, finalI + 1, total, recordX, recordY));
                    });

                    if (finalDelay > 0) {
                        try {
                            Thread.sleep(finalDelay);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }

                // 轮次间隔
                if (loop < finalLoopCount - 1 && !cancelled[0]) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                tvCurrentClick.setVisibility(View.GONE);
                btnCancelReplay.setEnabled(false);
                setReplayButtonsEnabled(true);
                tvProgress.setText(String.format(Locale.getDefault(),
                        "循环回放完成! 成功: %d, 失败: %d", successCount[0], failCount[0]));
                Toast.makeText(ClickReplayActivity.this,
                        "循环回放完成!", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnExportJson.setEnabled(enabled);
        btnExportCsv.setEnabled(enabled);
        btnExportTxt.setEnabled(enabled);
        btnReplayAll.setEnabled(enabled);
        btnReplayLoop.setEnabled(enabled);
    }

    private void setReplayButtonsEnabled(boolean enabled) {
        btnReplayAll.setEnabled(enabled);
        btnReplayLoop.setEnabled(enabled);
        btnExportJson.setEnabled(enabled);
        btnExportCsv.setEnabled(enabled);
        btnExportTxt.setEnabled(enabled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateRecordCount();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}

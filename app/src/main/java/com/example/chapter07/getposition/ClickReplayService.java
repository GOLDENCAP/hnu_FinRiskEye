package com.example.chapter07.getposition;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.chapter07.base.BaseAccessibilityService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 点击回放服务
 * 功能：
 * 1. 从数据库导出点击记录（支持JSON、CSV、TXT格式）
 * 2. 根据数据库记录模拟点击回放
 * 3. 支持带延迟的连续点击回放
 */
public class ClickReplayService extends BaseAccessibilityService {

    private static final String TAG = "ClickReplayService";

    private ClickRecordDatabaseHelper dbHelper;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final AtomicBoolean isReplaying;

    public interface ReplayCallback {
        void onStart(int totalCount);
        void onProgress(int current, int total, int x, int y);
        void onComplete(int successCount, int failCount);
        void onError(String error);
        void onCancelled();
    }

    public interface ExportCallback {
        void onExportStart();
        void onExportProgress(int current, int total);
        void onExportComplete(String filePath);
        void onExportError(String error);
    }

    public ClickReplayService() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isReplaying = new AtomicBoolean(false);
        this.executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onServiceCreated() {
        Log.d(TAG, "ClickReplayService created");
    }

    @Override
    protected void onServiceDestroyed() {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        Log.d(TAG, "ClickReplayService destroyed");
    }

    /**
     * 初始化数据库Helper
     */
    public void initDatabase(Context context) {
        if (dbHelper == null) {
            dbHelper = new ClickRecordDatabaseHelper(context);
        }
    }

    /**
     * 取消回放
     */
    public void cancelReplay() {
        isReplaying.set(false);
    }

    // ========== 数据库导出功能 ==========

    /**
     * 导出所有记录为JSON格式
     */
    public void exportToJson(Context context, String fileName, ExportCallback callback) {
        final Context ctx = context;
        final String fName = fileName;
        initDatabase(ctx);
        executorService.execute(() -> {
            try {
                mainHandler.post(callback::onExportStart);

                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                StringBuilder jsonContent = new StringBuilder();
                jsonContent.append("[\n");
                for (int i = 0; i < total; i++) {
                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    jsonContent.append("  {\"id\": ").append(record.id)
                            .append(", \"x\": ").append(record.x)
                            .append(", \"y\": ").append(record.y)
                            .append(", \"timestamp\": \"").append(record.timestamp)
                            .append("\"}");
                    if (i < total - 1) jsonContent.append(",");
                    jsonContent.append("\n");

                    if (i % 100 == 0) {
                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                    }
                }
                jsonContent.append("]");

                String filePath = saveToFile(ctx, fName.endsWith(".json") ? fName : fName + ".json", jsonContent.toString());
                mainHandler.post(() -> callback.onExportComplete(filePath));

            } catch (Exception e) {
                Log.e(TAG, "Export to JSON failed", e);
                mainHandler.post(() -> callback.onExportError("导出失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 导出所有记录为CSV格式
     */
    public void exportToCsv(Context context, String fileName, ExportCallback callback) {
        final Context ctx = context;
        final String fName = fileName;
        initDatabase(ctx);
        executorService.execute(() -> {
            try {
                mainHandler.post(callback::onExportStart);

                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                StringBuilder csvContent = new StringBuilder();
                csvContent.append("ID,X,Y,Timestamp\n");

                for (int i = 0; i < total; i++) {
                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    csvContent.append(record.id)
                            .append(",")
                            .append(record.x)
                            .append(",")
                            .append(record.y)
                            .append(",\"")
                            .append(record.timestamp)
                            .append("\"\n");

                    if (i % 100 == 0) {
                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                    }
                }

                mainHandler.post(() -> callback.onExportProgress(total, total));

                String filePath = saveToFile(ctx, fName.endsWith(".csv") ? fName : fName + ".csv", csvContent.toString());
                mainHandler.post(() -> callback.onExportComplete(filePath));

            } catch (Exception e) {
                Log.e(TAG, "Export to CSV failed", e);
                mainHandler.post(() -> callback.onExportError("导出失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 导出所有记录为TXT格式
     */
    public void exportToTxt(Context context, String fileName, ExportCallback callback) {
        final Context ctx = context;
        final String fName = fileName;
        initDatabase(ctx);
        executorService.execute(() -> {
            try {
                mainHandler.post(callback::onExportStart);

                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                StringBuilder txtContent = new StringBuilder();
                txtContent.append("========== 点击记录导出 ==========\n");
                txtContent.append("导出时间: ").append(getCurrentTimeString()).append("\n");
                txtContent.append("总记录数: ").append(total).append("\n");
                txtContent.append("================================\n\n");

                for (int i = 0; i < total; i++) {
                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    txtContent.append(String.format(Locale.getDefault(), "[%04d] 坐标: (%d, %d)  时间: %s\n",
                            i + 1, record.x, record.y, record.timestamp));

                    if (i % 100 == 0) {
                        final int progress = i + 1;
                        mainHandler.post(() -> callback.onExportProgress(progress, total));
                        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                    }
                }

                mainHandler.post(() -> callback.onExportProgress(total, total));

                String filePath = saveToFile(ctx, fName.endsWith(".txt") ? fName : fName + ".txt", txtContent.toString());
                mainHandler.post(() -> callback.onExportComplete(filePath));

            } catch (Exception e) {
                Log.e(TAG, "Export to TXT failed", e);
                mainHandler.post(() -> callback.onExportError("导出失败: " + e.getMessage()));
            }
        });
    }

    // ========== 点击回放功能 ==========

    /**
     * 回放所有点击记录
     */
    public void replayAllClicks(Context context, long delayBetweenClicks, ReplayCallback callback) {
        final Context ctx = context;
        final long delay = delayBetweenClicks;
        initDatabase(ctx);
        isReplaying.set(true);

        executorService.execute(() -> {
            try {
                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                if (total == 0) {
                    mainHandler.post(() -> callback.onError("没有可回放的记录"));
                    return;
                }

                final int fTotal = total;
                mainHandler.post(() -> callback.onStart(fTotal));

                int[] successCount = {0};
                int[] failCount = {0};

                for (int i = 0; i < fTotal; i++) {
                    if (!isReplaying.get()) {
                        mainHandler.post(callback::onCancelled);
                        return;
                    }

                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    boolean success = simulateClick(record.x, record.y);

                    if (success) {
                        successCount[0]++;
                    } else {
                        failCount[0]++;
                    }

                    final int current = i + 1;
                    final int x = record.x;
                    final int y = record.y;
                    mainHandler.post(() -> callback.onProgress(current, fTotal, x, y));

                    if (i < fTotal - 1 && delay > 0) {
                        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                    }
                }

                mainHandler.post(() -> callback.onComplete(successCount[0], failCount[0]));

            } catch (Exception e) {
                Log.e(TAG, "Replay failed", e);
                mainHandler.post(() -> callback.onError("回放失败: " + e.getMessage()));
            } finally {
                isReplaying.set(false);
            }
        });
    }

    /**
     * 回放指定范围的点击记录
     */
    public void replayRange(Context context, int startIndex, int endIndex, long delayBetweenClicks, ReplayCallback callback) {
        final Context ctx = context;
        final long delay = delayBetweenClicks;
        initDatabase(ctx);
        isReplaying.set(true);

        executorService.execute(() -> {
            try {
                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                if (total == 0) {
                    mainHandler.post(() -> callback.onError("没有可回放的记录"));
                    return;
                }

                final int finalStartIndex = Math.max(0, startIndex);
                final int finalEndIndex = Math.min(total - 1, endIndex);

                if (finalStartIndex > finalEndIndex) {
                    mainHandler.post(() -> callback.onError("无效的索引范围"));
                    return;
                }

                int rangeSize = finalEndIndex - finalStartIndex + 1;
                final int fRangeSize = rangeSize;
                mainHandler.post(() -> callback.onStart(fRangeSize));

                int[] successCount = {0};
                int[] failCount = {0};

                for (int i = finalStartIndex; i <= finalEndIndex; i++) {
                    if (!isReplaying.get()) {
                        mainHandler.post(callback::onCancelled);
                        return;
                    }

                    ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                    boolean success = simulateClick(record.x, record.y);

                    if (success) {
                        successCount[0]++;
                    } else {
                        failCount[0]++;
                    }

                    final int current = i - finalStartIndex + 1;
                    final int x = record.x;
                    final int y = record.y;
                    mainHandler.post(() -> callback.onProgress(current, fRangeSize, x, y));

                    if (i < finalEndIndex && delay > 0) {
                        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                    }
                }

                mainHandler.post(() -> callback.onComplete(successCount[0], failCount[0]));

            } catch (Exception e) {
                Log.e(TAG, "Range replay failed", e);
                mainHandler.post(() -> callback.onError("范围回放失败: " + e.getMessage()));
            } finally {
                isReplaying.set(false);
            }
        });
    }

    /**
     * 循环回放所有记录
     */
    public void replayLoop(Context context, int loopCount, long delayBetweenClicks, ReplayCallback callback) {
        final Context ctx = context;
        final long delay = delayBetweenClicks;
        final int fLoopCount = loopCount;
        initDatabase(ctx);
        isReplaying.set(true);

        executorService.execute(() -> {
            try {
                List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
                int total = records.size();

                if (total == 0) {
                    mainHandler.post(() -> callback.onError("没有可回放的记录"));
                    return;
                }

                int totalOperations = total * fLoopCount;
                final int fTotalOps = totalOperations;
                final int fTotal = total;
                mainHandler.post(() -> callback.onStart(fTotalOps));

                int[] successCount = {0};
                int[] failCount = {0};

                for (int loop = 0; loop < fLoopCount; loop++) {
                    if (!isReplaying.get()) {
                        mainHandler.post(callback::onCancelled);
                        return;
                    }

                    for (int i = 0; i < fTotal; i++) {
                        if (!isReplaying.get()) {
                            mainHandler.post(callback::onCancelled);
                            return;
                        }

                        ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
                        boolean success = simulateClick(record.x, record.y);

                        if (success) {
                            successCount[0]++;
                        } else {
                            failCount[0]++;
                        }

                        final int current = loop * fTotal + i + 1;
                        final int x = record.x;
                        final int y = record.y;
                        mainHandler.post(() -> callback.onProgress(current, fTotalOps, x, y));

                        if (delay > 0) {
                            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        }
                    }

                    if (loop < fLoopCount - 1) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }

                mainHandler.post(() -> callback.onComplete(successCount[0], failCount[0]));

            } catch (Exception e) {
                Log.e(TAG, "Loop replay failed", e);
                mainHandler.post(() -> callback.onError("循环回放失败: " + e.getMessage()));
            } finally {
                isReplaying.set(false);
            }
        });
    }

    // ========== 辅助方法 ==========

    private String saveToFile(Context context, String fileName, String content) throws IOException {
        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String finalFileName = fileName.replace(".json", "_" + timeStamp + ".json")
                .replace(".csv", "_" + timeStamp + ".csv")
                .replace(".txt", "_" + timeStamp + ".txt");

        File file = new File(exportDir, finalFileName);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.flush();
        writer.close();

        return file.getAbsolutePath();
    }

    private String getCurrentTimeString() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    /**
     * 获取导出目录路径
     */
    public String getExportDirectory(Context context) {
        File exportDir = new File(context.getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        return exportDir.getAbsolutePath();
    }

    /**
     * 获取记录数量
     */
    public int getRecordCount(Context context) {
        initDatabase(context);
        return dbHelper.getRecordCount();
    }
}

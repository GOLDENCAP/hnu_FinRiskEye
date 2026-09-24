package com.example.chapter07.golbal_view;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class ClickCoordinateProvider extends ContentProvider {
    private static final String TAG = "ClickCoordProvider";
    private static final String AUTHORITY = "com.example.chapter07.clickprovider";

    public static final String PATH_LAST = "click";
    public static final String PATH_HISTORY = "history";
    public static final String PATH_INFO = "info";
    public static final String PATH_NEXT = "next";

    public static final Uri CONTENT_URI_LAST = Uri.parse("content://" + AUTHORITY + "/" + PATH_LAST);
    public static final Uri CONTENT_URI_HISTORY = Uri.parse("content://" + AUTHORITY + "/" + PATH_HISTORY);
    public static final Uri CONTENT_URI_INFO = Uri.parse("content://" + AUTHORITY + "/" + PATH_INFO);
    public static final Uri CONTENT_URI_NEXT = Uri.parse("content://" + AUTHORITY + "/" + PATH_NEXT);

    private static final int MATCH_LAST = 1;
    private static final int MATCH_HISTORY = 2;
    private static final int MATCH_INFO = 3;
    private static final int MATCH_NEXT = 4;

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, PATH_LAST, MATCH_LAST);
        sUriMatcher.addURI(AUTHORITY, PATH_HISTORY, MATCH_HISTORY);
        sUriMatcher.addURI(AUTHORITY, PATH_INFO, MATCH_INFO);
        sUriMatcher.addURI(AUTHORITY, PATH_NEXT, MATCH_NEXT);
    }

    @Override
    public boolean onCreate() {
        Log.i(TAG, "✅ ClickCoordinateProvider 已启动");
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        int match = sUriMatcher.match(uri);
        Log.d(TAG, "query: " + uri + " match=" + match);

        switch (match) {
            case MATCH_LAST:
                return queryLast(uri);
            case MATCH_HISTORY:
                return queryHistory();
            case MATCH_INFO:
                return queryInfo();
            case MATCH_NEXT:
                return queryNext(uri);
            default:
                Log.w(TAG, "未知 URI: " + uri);
                return null;
        }
    }

    // 查询最近一次点击，支持 wait 参数阻塞等待
    private Cursor queryLast(Uri uri) {
        long timeoutMs = parseTimeout(uri);

        if (timeoutMs > 0) {
            // 阻塞等待下一次点击
            return waitForNextClick(timeoutMs);
        }

        // 立即返回当前值
        return buildLastCursor(
                ClickBroadcastReceiver.getLastX(),
                ClickBroadcastReceiver.getLastY(),
                ClickBroadcastReceiver.getLastTimestamp(),
                ClickBroadcastReceiver.getLastSource()
        );
    }

    // 阻塞等待下一次点击
    private Cursor waitForNextClick(long timeoutMs) {
        Log.d(TAG, "⏳ 阻塞等待点击，超时 " + timeoutMs + "ms");

        long startMs = SystemClock.elapsedRealtime();
        long clickCounterBefore = ClickBroadcastReceiver.getClickCounter();

        ClickBroadcastReceiver.PendingRequest req =
                ClickBroadcastReceiver.waitForNextClick(timeoutMs);

        try {
            long remaining = timeoutMs - (SystemClock.elapsedRealtime() - startMs);
            if (remaining <= 0) remaining = 100;
            boolean gotIt = req.await(remaining);
            long elapsed = SystemClock.elapsedRealtime() - startMs;

            if (gotIt) {
                Log.d(TAG, String.format("🎯 收到点击: (%d, %d) 耗时 %dms",
                        req.resultX, req.resultY, elapsed));
                return buildLastCursor(
                        req.resultX, req.resultY,
                        req.resultTimestamp, req.resultSource
                );
            } else {
                Log.d(TAG, "⏰ 等待超时 " + elapsed + "ms，无新点击");
                // 返回最后已知的位置
                return buildLastCursor(
                        ClickBroadcastReceiver.getLastX(),
                        ClickBroadcastReceiver.getLastY(),
                        ClickBroadcastReceiver.getLastTimestamp(),
                        ClickBroadcastReceiver.getLastSource()
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "等待被中断");
            return buildLastCursor(
                    ClickBroadcastReceiver.getLastX(),
                    ClickBroadcastReceiver.getLastY(),
                    ClickBroadcastReceiver.getLastTimestamp(),
                    ClickBroadcastReceiver.getLastSource()
            );
        }
    }

    // 查询下一次点击（非阻塞，立即返回，不等待）
    private Cursor queryNext(Uri uri) {
        long timeoutMs = parseTimeout(uri);
        if (timeoutMs <= 0) timeoutMs = 1;

        return waitForNextClick(timeoutMs);
    }

    private MatrixCursor buildLastCursor(float x, float y, long timestamp, String source) {
        int xi = Math.round(x);
        int yi = Math.round(y);
        boolean isValid = xi >= 0 && yi >= 0;

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "x", "y", "timestamp", "source", "is_valid", "elapsed_ms"
        });
        cursor.addRow(new Object[]{
                xi, yi, timestamp, source, isValid ? 1 : 0, 0
        });
        return cursor;
    }

    private Cursor queryHistory() {
        List<ClickBroadcastReceiver.ClickRecord> records =
                ClickBroadcastReceiver.getHistorySnapshot();

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "index", "x", "y", "timestamp", "click_id", "source"
        });

        synchronized (records) {
            for (int i = 0; i < records.size(); i++) {
                ClickBroadcastReceiver.ClickRecord r = records.get(i);
                cursor.addRow(new Object[]{
                        i, r.x, r.y, r.timestamp, r.clickId, r.source
                });
            }
        }
        return cursor;
    }

    private Cursor queryInfo() {
        float x = ClickBroadcastReceiver.getLastX();
        float y = ClickBroadcastReceiver.getLastY();
        int historyCount = ClickBroadcastReceiver.getHistorySize();
        long clickCounter = ClickBroadcastReceiver.getClickCounter();
        long lastTs = ClickBroadcastReceiver.getLastTimestamp();
        boolean isValid = x >= 0 && y >= 0;

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "is_valid", "history_count", "last_x", "last_y",
                "last_timestamp", "total_clicks"
        });
        cursor.addRow(new Object[]{
                isValid ? 1 : 0,
                historyCount,
                Math.round(x),
                Math.round(y),
                lastTs,
                clickCounter
        });
        return cursor;
    }

    // 从 URI 查询参数中解析 timeout
    private long parseTimeout(Uri uri) {
        String t = uri.getQueryParameter("wait");
        if (t == null) t = uri.getQueryParameter("timeout");
        if (t == null) t = uri.getQueryParameter("timeout_ms");
        if (t == null) return 0;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case MATCH_LAST:
            case MATCH_NEXT:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".click";
            case MATCH_HISTORY:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".history";
            case MATCH_INFO:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".info";
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        int match = sUriMatcher.match(uri);
        if ((match == MATCH_LAST || match == MATCH_NEXT) && values != null) {
            Integer x = values.getAsInteger("x");
            Integer y = values.getAsInteger("y");
            String source = values.getAsString("source");
            if (x != null && y != null) {
                if (source == null) source = "adb_insert";
                ClickBroadcastReceiver.recordClick(x, y, source);
                Log.d(TAG, "📥 insert: (" + x + ", " + y + ") source=" + source);
                return uri;
            }
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        int match = sUriMatcher.match(uri);
        if (match == MATCH_LAST || match == MATCH_HISTORY) {
            int count = ClickBroadcastReceiver.getHistorySize();
            ClickBroadcastReceiver.clearHistory();
            Log.d(TAG, "🗑️ 清空 " + count + " 条记录");
            return count;
        }
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
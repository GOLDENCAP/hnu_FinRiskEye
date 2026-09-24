package com.example.chapter07.golbal_view;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ClickBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "ClickReceiver";

    public static final String ACTION_GET_CLICK = "com.example.chapter07.GET_CLICK";
    public static final String ACTION_CLEAR_HISTORY = "com.example.chapter07.CLEAR_CLICK_HISTORY";
    public static final String ACTION_GET_NEXT_CLICK = "com.example.chapter07.GET_NEXT_CLICK";

    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_IS_VALID = "is_valid";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_TIMEOUT_MS = "timeout_ms";

    private static final AtomicLong sRequestCounter = new AtomicLong(0);

    private static final List<PendingRequest> sPendingRequests =
            Collections.synchronizedList(new ArrayList<PendingRequest>());

    public static class PendingRequest {
        public final long id;
        public final long timeoutMs;
        public final long deadline;
        public volatile int resultX = -1;
        public volatile int resultY = -1;
        public volatile long resultTimestamp = 0;
        public volatile String resultSource = "unknown";
        public volatile boolean resultValid = false;
        public volatile boolean done = false;
        public final CountDownLatch latch = new CountDownLatch(1);

        PendingRequest(long id, long timeoutMs) {
            this.id = id;
            this.timeoutMs = timeoutMs;
            this.deadline = System.currentTimeMillis() + timeoutMs;
        }

        public boolean await(long ms) throws InterruptedException {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    private static volatile float sLastX = -1f;
    private static volatile float sLastY = -1f;
    private static volatile long sLastTimestamp = 0;
    private static volatile String sLastSource = "unknown";
    private static final AtomicLong sClickCounter = new AtomicLong(0);

    private static final int MAX_HISTORY = 100;
    private static final List<ClickRecord> sHistory =
            Collections.synchronizedList(new ArrayList<ClickRecord>());

    public static class ClickRecord {
        public final int x;
        public final int y;
        public final long timestamp;
        public final long clickId;
        public final String source;

        public ClickRecord(int x, int y, long timestamp, long clickId, String source) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
            this.clickId = clickId;
            this.source = source;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        switch (intent.getAction()) {
            case ACTION_GET_CLICK:
                handleGetClick();
                break;
            case ACTION_GET_NEXT_CLICK:
                handleGetNextClick(intent);
                break;
            case ACTION_CLEAR_HISTORY:
                handleClearHistory();
                break;
        }
    }

    private void handleGetClick() {
        boolean isValid = sLastX >= 0 && sLastY >= 0;
        Bundle resultData = new Bundle();
        resultData.putInt(EXTRA_X, Math.round(sLastX));
        resultData.putInt(EXTRA_Y, Math.round(sLastY));
        resultData.putLong(EXTRA_TIMESTAMP, sLastTimestamp);
        resultData.putString(EXTRA_SOURCE, sLastSource);
        resultData.putInt(EXTRA_IS_VALID, isValid ? 1 : 0);
        resultData.putInt(EXTRA_COUNT, sHistory.size());
        resultData.putLong("click_counter", sClickCounter.get());

        setResultExtras(resultData);
        setResultCode(Activity.RESULT_OK);
        abortBroadcast();

        Log.d(TAG, String.format("[GET_CLICK] (%d, %d) valid=%d counter=%d",
                Math.round(sLastX), Math.round(sLastY), isValid ? 1 : 0, sClickCounter.get()));
    }

    private void handleGetNextClick(Intent intent) {
        notifyAllPendingRequests();

        Bundle resultData = new Bundle();
        resultData.putInt(EXTRA_X, Math.round(sLastX));
        resultData.putInt(EXTRA_Y, Math.round(sLastY));
        resultData.putLong(EXTRA_TIMESTAMP, sLastTimestamp);
        resultData.putString(EXTRA_SOURCE, sLastSource);
        resultData.putInt(EXTRA_IS_VALID, (sLastX >= 0 && sLastY >= 0) ? 1 : 0);

        setResultExtras(resultData);
        setResultCode(Activity.RESULT_OK);
        abortBroadcast();

        Log.d(TAG, String.format("[GET_NEXT_CLICK] → (%d, %d) valid=%d",
                Math.round(sLastX), Math.round(sLastY), (sLastX >= 0 && sLastY >= 0) ? 1 : 0));
    }

    private void handleClearHistory() {
        int cleared = sHistory.size();
        sHistory.clear();
        sLastX = -1f;
        sLastY = -1f;
        sLastTimestamp = 0;
        sLastSource = "unknown";

        Bundle resultData = new Bundle();
        resultData.putInt(EXTRA_COUNT, cleared);
        setResultExtras(resultData);
        setResultCode(Activity.RESULT_OK);
        abortBroadcast();

        Log.d(TAG, "历史已清空，共清除 " + cleared + " 条记录");
    }

    public static void recordClick(float x, float y, String source) {
        int xi = Math.round(x);
        int yi = Math.round(y);
        long ts = System.currentTimeMillis();
        long id = sClickCounter.incrementAndGet();

        sLastX = x;
        sLastY = y;
        sLastTimestamp = ts;
        sLastSource = source;

        synchronized (sHistory) {
            sHistory.add(new ClickRecord(xi, yi, ts, id, source));
            if (sHistory.size() > MAX_HISTORY) {
                sHistory.remove(0);
            }
        }

        notifyAllPendingRequests();

        Log.d(TAG, String.format("✅ recordClick: id=%d, (%d, %d), source=%s",
                id, xi, yi, source));
    }

    private static void notifyAllPendingRequests() {
        List<PendingRequest> toNotify;
        synchronized (sPendingRequests) {
            if (sPendingRequests.isEmpty()) return;
            toNotify = new ArrayList<>(sPendingRequests);
            sPendingRequests.clear();
        }

        for (PendingRequest req : toNotify) {
            if (req.done) continue;
            req.resultX = Math.round(sLastX);
            req.resultY = Math.round(sLastY);
            req.resultTimestamp = sLastTimestamp;
            req.resultSource = sLastSource;
            req.resultValid = sLastX >= 0 && sLastY >= 0;
            req.done = true;
            req.latch.countDown();
            Log.d(TAG, String.format("🔔 唤醒 req=%d → (%d, %d)", req.id, req.resultX, req.resultY));
        }
    }

    public static PendingRequest waitForNextClick(long timeoutMs) {
        long requestId = sRequestCounter.incrementAndGet();
        PendingRequest req = new PendingRequest(requestId, timeoutMs);

        cleanExpiredRequests();

        synchronized (sPendingRequests) {
            if (sLastTimestamp > 0 && (System.currentTimeMillis() - sLastTimestamp) < 1000) {
                req.resultX = Math.round(sLastX);
                req.resultY = Math.round(sLastY);
                req.resultTimestamp = sLastTimestamp;
                req.resultSource = sLastSource;
                req.resultValid = sLastX >= 0 && sLastY >= 0;
                req.done = true;
                Log.d(TAG, String.format("[WAIT] req=%d 直接命中 (%d, %d)",
                        requestId, req.resultX, req.resultY));
                return req;
            }
            sPendingRequests.add(req);
        }

        Log.d(TAG, "[WAIT] req=" + requestId + " 等待中，超时 " + timeoutMs + "ms");
        return req;
    }

    private static void cleanExpiredRequests() {
        synchronized (sPendingRequests) {
            long now = System.currentTimeMillis();
            sPendingRequests.removeIf(req -> req.done || now > req.deadline);
        }
    }

    public static float getLastX() { return sLastX; }
    public static float getLastY() { return sLastY; }
    public static long getLastTimestamp() { return sLastTimestamp; }
    public static String getLastSource() { return sLastSource; }
    public static long getClickCounter() { return sClickCounter.get(); }

    public static List<ClickRecord> getHistorySnapshot() {
        synchronized (sHistory) {
            return new ArrayList<>(sHistory);
        }
    }

    public static int getHistorySize() { return sHistory.size(); }

    public static void clearHistory() {
        synchronized (sHistory) {
            sHistory.clear();
        }
        sLastX = -1f;
        sLastY = -1f;
        sLastTimestamp = 0;
        sLastSource = "unknown";
    }
}
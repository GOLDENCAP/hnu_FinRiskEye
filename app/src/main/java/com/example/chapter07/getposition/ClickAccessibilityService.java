package com.example.chapter07.getposition;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.example.chapter07.base.BaseAccessibilityService;

public class ClickAccessibilityService extends BaseAccessibilityService {

    private static ClickAccessibilityService instance;
    private static ClickListener clickListener;
    private static volatile LatestClick latestClick;
    private DailyClickRecordDatabaseHelper dbHelper;
    private String sessionTimestamp;

    public interface ClickListener {
        void onClick(int x, int y);
    }

    public static class LatestClick {
        public final int x;
        public final int y;
        public final String source;
        public final long recordId;

        public LatestClick(int x, int y, String source, long recordId) {
            this.x = x;
            this.y = y;
            this.source = source;
            this.recordId = recordId;
        }
    }

    @Override
    protected void onServiceCreated() {
        super.onServiceCreated();
        instance = this;
        sessionTimestamp = DailyClickRecordDatabaseHelper.getCurrentTimestamp();
        dbHelper = new DailyClickRecordDatabaseHelper(this, sessionTimestamp);
        dbHelper.getWritableDatabase();
        android.util.Log.d("Accessibility", "无障碍服务已启动, session=" + sessionTimestamp);
    }

    @Override
    protected void onAccessibilityEventReceived(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            int x = -1;
            int y = -1;
            String source = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";

            AccessibilityNodeInfo node = event.getSource();
            if (node != null) {
                BundleExtrasHolder holder = new BundleExtrasHolder(node);
                x = holder.x;
                y = holder.y;
            }

            if (x < 0 || y < 0) {
                AccessibilityNodeInfo nodeInfo = event.getSource();
                if (nodeInfo != null) {
                    Rect rect = new Rect();
                    nodeInfo.getBoundsInScreen(rect);
                    x = rect.centerX();
                    y = rect.centerY();
                }
            }

            if (x >= 0 && y >= 0 && dbHelper != null) {
                long id = dbHelper.insertClickRecord(x, y, x, y, eventType, source);
                latestClick = new LatestClick(x, y, source, id);
                notifyScreenClick(x, y);
                if (clickListener != null) {
                    clickListener.onClick(x, y);
                }
                android.util.Log.d("Accessibility", "已记录点击: (" + x + ", " + y + ") source=" + source + ", id=" + id);
            }
        }
    }

    private static final class BundleExtrasHolder {
        final int x;
        final int y;

        BundleExtrasHolder(AccessibilityNodeInfo node) {
            int tmpX = -1;
            int tmpY = -1;
            android.os.Bundle extras = node.getExtras();
            if (extras != null) {
                tmpX = extras.getInt("x", -1);
                tmpY = extras.getInt("y", -1);
            }
            this.x = tmpX;
            this.y = tmpY;
        }
    }

    @Override
    protected void onServiceInterrupted() {
        super.onServiceInterrupted();
        android.util.Log.d("Accessibility", "无障碍服务中断");
    }

    @Override
    protected void onServiceDestroyed() {
        super.onServiceDestroyed();
        if (dbHelper != null) {
            dbHelper.close();
            dbHelper = null;
        }
        instance = null;
        android.util.Log.d("Accessibility", "无障碍服务已销毁");
    }

    public static ClickAccessibilityService getInstance() {
        return instance;
    }

    public static void setClickListener(ClickListener listener) {
        clickListener = listener;
    }

    public static boolean isAlive() {
        return instance != null;
    }

    public static LatestClick getLatestClick() {
        return latestClick;
    }

    public static boolean click(int x, int y) {
        if (instance != null) {
            return instance.performClick(x, y);
        }
        android.util.Log.e("Accessibility", "无障碍服务未运行，无法模拟点击");
        return false;
    }

    private void notifyClick(int x, int y) {
        if (clickListener != null) {
            clickListener.onClick(x, y);
        }
    }

    public static void longPress(int x, int y, long duration) {
        if (instance != null) {
            instance.performLongPress(x, y, duration);
        }
    }

    public static void swipe(int startX, int startY, int endX, int endY, long duration) {
        if (instance != null) {
            instance.performSwipe(startX, startY, endX, endY, duration);
        }
    }
}

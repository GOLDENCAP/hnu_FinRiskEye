package com.example.chapter07.getposition;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 全局触摸捕获无障碍服务
 * 功能：
 * 1. 检测窗口切换（知道用户在哪个应用）
 * 2. 使用 dispatchGesture 模拟触摸来验证坐标
 * 3. 与触摸反馈服务配合获取触摸坐标
 */
public class GlobalTouchAccessibilityService extends AccessibilityService {

    private static final String TAG = "GlobalTouchAccessibility";
    private static GlobalTouchAccessibilityService instance;
    
    // 触摸监听器
    public interface OnGlobalTouchListener {
        void onTouch(int x, int y);
    }
    private static OnGlobalTouchListener touchListener;

    // 当前窗口包名
    private String currentPackageName = "";
    
    // 最后一次模拟触摸的时间
    private long lastSimulatedTouchTime = 0;
    private static final long TOUCH_COOLDOWN = 100; // 100ms 防抖

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "GlobalTouchAccessibilityService 已连接");
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 50;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? 
                    event.getPackageName().toString() : "";
            if (!packageName.equals(currentPackageName)) {
                currentPackageName = packageName;
                Log.d(TAG, "窗口切换到: " + packageName);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "GlobalTouchAccessibilityService 中断");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "GlobalTouchAccessibilityService 销毁");
    }

    /**
     * 获取服务实例
     */
    public static GlobalTouchAccessibilityService getInstance() {
        return instance;
    }

    /**
     * 检查服务是否运行
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 设置触摸监听器
     */
    public static void setOnGlobalTouchListener(OnGlobalTouchListener listener) {
        touchListener = listener;
    }

    /**
     * 获取当前应用包名
     */
    public static String getCurrentPackage() {
        return instance != null ? instance.currentPackageName : "";
    }

    /**
     * 模拟一次触摸（用于验证触摸坐标）
     * 注意：AccessibilityService 无法直接获取触摸坐标
     * 这个方法主要用于辅助验证
     */
    public boolean simulateTouchForVerification(int x, int y, GestureResultCallback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 10));
        
        return dispatchGesture(builder.build(), callback, null);
    }

    /**
     * 内部方法：触发触摸回调（需要触摸反馈服务配合）
     */
    private void triggerTouchCallback(int x, int y) {
        if (touchListener != null) {
            touchListener.onTouch(x, y);
        }
    }

    /**
     * 检查触摸冷却（防止抖动）
     */
    public static boolean canProcessTouch() {
        if (instance == null) return false;
        long now = System.currentTimeMillis();
        if (now - instance.lastSimulatedTouchTime < TOUCH_COOLDOWN) {
            return false;
        }
        instance.lastSimulatedTouchTime = now;
        return true;
    }
}

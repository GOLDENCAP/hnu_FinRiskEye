package com.example.chapter07.base;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * 通用无障碍服务基类
 * 任何继承这个类的子类都能自动获得无障碍服务能力
 */
public abstract class BaseAccessibilityService extends AccessibilityService {
    
    private static BaseAccessibilityService instance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        onServiceCreated();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 子类可以重写此方法来处理无障碍事件
        onAccessibilityEventReceived(event);
    }
    
    @Override
    public void onInterrupt() {
        onServiceInterrupted();
    }
    
    @Override
    public void onDestroy() {
        instance = null;
        onServiceDestroyed();
        super.onDestroy();
    }
    
    // ========== 提供给子类的回调方法 ==========
    
    /**
     * 服务创建时调用
     */
    protected void onServiceCreated() {}
    
    /**
     * 接收到无障碍事件时调用
     */
    protected void onAccessibilityEventReceived(AccessibilityEvent event) {}
    
    /**
     * 服务中断时调用
     */
    protected void onServiceInterrupted() {}
    
    /**
     * 服务销毁时调用
     */
    protected void onServiceDestroyed() {}
    
    // ========== 公共API方法 ==========
    
    /**
     * 获取服务实例（用于子类）
     */
    protected static BaseAccessibilityService getInstance() {
        return instance;
    }
    
    /**
     * 检查服务是否正在运行
     */
    public static boolean isServiceRunning() {
        return instance != null;
    }
    
    /**
     * 模拟点击指定坐标
     * @param x X坐标
     * @param y Y坐标
     * @return 是否成功执行
     */
    public static boolean simulateClick(int x, int y) {
        if (instance == null) return false;
        return instance.performClick(x, y);
    }
    
    /**
     * 模拟长按
     * @param x X坐标
     * @param y Y坐标
     * @param duration 长按时间（毫秒）
     * @return 是否成功执行
     */
    public static boolean simulateLongPress(int x, int y, long duration) {
        if (instance == null) return false;
        return instance.performLongPress(x, y, duration);
    }
    
    /**
     * 模拟滑动
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 滑动时间（毫秒）
     * @return 是否成功执行
     */
    public static boolean simulateSwipe(int startX, int startY, int endX, int endY, long duration) {
        if (instance == null) return false;
        return instance.performSwipe(startX, startY, endX, endY, duration);
    }
    
    /**
     * 模拟多点触控
     * @param points 坐标点数组
     * @param duration 持续时间
     * @return 是否成功执行
     */
    public static boolean simulateMultiTouch(int[][] points, long duration) {
        if (instance == null || points == null || points.length < 2) return false;
        return instance.performMultiTouch(points, duration);
    }
    
    // ========== 内部执行方法 ==========
    
    /**
     * 执行点击
     */
    protected boolean performClick(int x, int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        
        return dispatchGesture(gestureBuilder.build(), null, null);
    }
    
    /**
     * 执行长按
     */
    protected boolean performLongPress(int x, int y, long duration) {
        Path longPressPath = new Path();
        longPressPath.moveTo(x, y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(longPressPath, 0, duration));
        
        return dispatchGesture(gestureBuilder.build(), null, null);
    }
    
    /**
     * 执行滑动
     */
    protected boolean performSwipe(int startX, int startY, int endX, int endY, long duration) {
        Path swipePath = new Path();
        swipePath.moveTo(startX, startY);
        swipePath.lineTo(endX, endY);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, duration));
        
        return dispatchGesture(gestureBuilder.build(), null, null);
    }
    
    /**
     * 执行多点触控（Android 7.0+）
     */
    protected boolean performMultiTouch(int[][] points, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        
        for (int i = 0; i < points.length; i++) {
            Path path = new Path();
            path.moveTo(points[i][0], points[i][1]);
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        }
        
        return dispatchGesture(gestureBuilder.build(), null, null);
    }

    /**
     * 监听屏幕点击
     * 通过AccessibilityService的TYPE_VIEW_CLICKED事件获取点击位置
     * 注意：这只能获取有clickable=true的View的点击
     * 对于精确坐标获取，需要在performClick成功后触发回调
     */
    private OnScreenClickListener onScreenClickListener;

    public interface OnScreenClickListener {
        void onScreenClick(int x, int y);
    }

    public void setOnScreenClickListener(OnScreenClickListener listener) {
        this.onScreenClickListener = listener;
    }

    protected void notifyScreenClick(int x, int y) {
        if (onScreenClickListener != null) {
            onScreenClickListener.onScreenClick(x, y);
        }
    }
}
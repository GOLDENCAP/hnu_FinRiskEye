package com.example.chapter07.getposition;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class ClickRecordService extends AccessibilityService {
    
    private static final String TAG = "ClickRecordService";
    private static int clickCount = 0;
    private static int lastX = 0;
    private static int lastY = 0;
    private static OnRecordListener listener = null;
    private static boolean isRecording = false;
    private static int eventCount = 0; // 添加事件计数器
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "服务已连接");
        
        // 配置服务信息
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | 
                          AccessibilityEvent.TYPE_VIEW_LONG_CLICKED |
                          AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; // 添加窗口变化监听
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100; // 改回100ms
        setServiceInfo(info);
        
        Log.d(TAG, "服务配置完成，监听事件类型: " + info.eventTypes);
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        eventCount++;
        Log.d(TAG, "收到事件 #" + eventCount + ", 类型: " + event.getEventType() + 
              ", 包名: " + event.getPackageName());
        Log.d(TAG,"AccessibilityEvent.TYPE_VIEW_CLICKED==="+AccessibilityEvent.TYPE_VIEW_CLICKED);
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                Log.d(TAG, "✅ 检测到点击事件");
                recordClick(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
                Log.d(TAG, "✅ 检测到长按事件");
                recordClick(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                Log.d(TAG, "窗口状态变化: " + event.getClassName());
                break;
            default:
                Log.d(TAG, "其他事件类型: " + event.getEventType());
                break;
        }
    }
    
    private void recordClick(AccessibilityEvent event) {
        Log.d(TAG, "recordClick 被调用, isRecording=" + isRecording + 
              ", clickCount=" + clickCount + "/5");
        
        // 检查是否在录制状态
        if (!isRecording) {
            Log.d(TAG, "不在录制状态，忽略点击");
            return;
        }
        
        // 检查是否已达到上限
        if (clickCount >= 5) {
            Log.d(TAG, "已达到5次记录上限，停止录制");
            isRecording = false;
            if (listener != null) {
                listener.onComplete();
            }
            return;
        }
        
        // 获取点击坐标
        android.view.accessibility.AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            try {
                android.graphics.Rect rect = new android.graphics.Rect();
                source.getBoundsInScreen(rect);
                lastX = rect.centerX();
                lastY = rect.centerY();
                
                clickCount++;
                Log.d(TAG, "========== 点击记录 " + clickCount + "/5 ==========");
                Log.d(TAG, "View边界: " + rect.toString());
                Log.d(TAG, "中心坐标: (" + lastX + ", " + lastY + ")");
                Log.d(TAG, "View内容: " + source.getText());
                
                // 通知Activity更新UI
                if (listener != null) {
                    Log.d(TAG, "通知Activity更新UI");
                    listener.onRecord(lastX, lastY, clickCount);
                } else {
                    Log.w(TAG, "listener 为 null，无法通知Activity");
                }
                
                // 如果达到5次，停止录制
                if (clickCount >= 5) {
                    isRecording = false;
                    if (listener != null) {
                        listener.onComplete();
                    }
                    Log.d(TAG, "录制完成，服务继续运行但停止记录");
                } else {
                    Log.d(TAG, "继续等待下一次点击，还剩 " + (5 - clickCount) + " 次");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "处理点击事件时出错", e);
            } finally {
                source.recycle();
            }
        } else {
            Log.w(TAG, "无法获取点击坐标，source 为 null");
        }
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "服务销毁");
        listener = null;
    }
    
    // 公开方法供Activity调用
    public static void startRecording() {
        isRecording = true;
        clickCount = 0;
        eventCount = 0;
        Log.d(TAG, "开始录制，isRecording=" + isRecording);
    }
    
    public static void resetCount() {
        clickCount = 0;
        lastX = 0;
        lastY = 0;
        isRecording = true;
        eventCount = 0;
        if (listener != null) {
            listener.onComplete();
        }
        Log.d(TAG, "重置计数，准备重新录制");
    }
    
    public static int getClickCount() {
        return clickCount;
    }
    
    public static void setOnRecordListener(OnRecordListener l) {
        listener = l;
        Log.d(TAG, "设置监听器: " + (l != null ? "已设置" : "已清除"));
    }
    
    public interface OnRecordListener {
        void onRecord(int x, int y, int currentCount);
        void onComplete();
    }
}
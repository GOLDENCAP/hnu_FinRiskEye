package com.example.chapter07.getposition;

import android.content.Context;

/**
 * 悬浮窗交替记录控制器
 */
public final class FloatingTouchRecordController {

    public interface TouchRecordCallback {
        void onTouchRecorded(int x, int y);
        void onTouchFailed(String reason);
    }

    private FloatingTouchRecordController() {
    }

    public static void recordSingleTouch(Context context, TouchRecordCallback callback) {
        if (callback != null) {
            callback.onTouchFailed("悬浮窗单次记录暂未接入真实实现");
        }
    }

    public static void stop(Context context) {
    }
}

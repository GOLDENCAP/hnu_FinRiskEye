package com.example.chapter07.oneclick;

import android.os.Handler;
import android.os.Looper;

import com.example.chapter07.getposition.ClickAccessibilityService;

public final class DelayedClickHelper {

    private static final long DEFAULT_DELAY_MS = 500L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private DelayedClickHelper() {
    }

    public static void clickAfterDelay(int x, int y) {
        clickAfterDelay(x, y, DEFAULT_DELAY_MS);
    }

    public static void clickAfterDelay(int x, int y, long delayMs) {
        MAIN_HANDLER.postDelayed(() -> ClickAccessibilityService.click(x, y), delayMs);
    }
}

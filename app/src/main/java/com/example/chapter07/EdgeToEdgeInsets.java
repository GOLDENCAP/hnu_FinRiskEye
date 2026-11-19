package com.example.chapter07;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15+（targetSdk 35+）强制 edge-to-edge 后的系统栏适配工具。
 *
 * <p>背景：工程 targetSdk = 36，在 Android 15/16 上系统强制应用内容绘制到状态栏/
 * 导航栏后面，且 AppCompat/MaterialComponents 的普通 ActionBar 不会自动为内容留出
 * 状态栏高度，导致页面第一行控件被标题栏/状态栏遮住（小米 14 / Android 16 实测）。
 * 本工具在需要适配的 Activity 上为根布局追加系统栏 padding，保证内容完整可见。</p>
 *
 * <p>兼容策略：Android 15 以下保持系统默认布局（不主动改成 edge-to-edge），因此
 * 不影响平板 Android 12 等旧系统上的现有表现。</p>
 */
public final class EdgeToEdgeInsets {

    /** Android 15 = API 35，从此版本开始系统对 targetSdk 35+ 强制 edge-to-edge。 */
    private static final int EDGE_TO_EDGE_MIN_SDK = 35;

    private EdgeToEdgeInsets() {
    }

    /**
     * 在 edge-to-edge 生效的系统上，为 root 应用系统栏/刘海安全区 padding。
     *
     * @param activity 当前 Activity（用于取 Window 与夜间模式配置）
     * @param root     页面根布局；应尽量是铺满屏幕的容器（如 ScrollView/LinearLayout 根节点）
     */
    public static void apply(Activity activity, View root) {
        if (activity == null || root == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < EDGE_TO_EDGE_MIN_SDK) {
            // Android 15 以下系统默认不强制 edge-to-edge，保持原有表现，避免平板等设备出现双重留白。
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom
            );
            return WindowInsetsCompat.CONSUMED;
        });

        // 状态栏/导航栏背景为页面背景色：浅色模式下用深色图标，深色模式下用浅色图标。
        int nightMode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(activity.getWindow(), root)
                .setAppearanceLightStatusBars(lightBars);
        WindowCompat.getInsetsController(activity.getWindow(), root)
                .setAppearanceLightNavigationBars(lightBars);
    }
}

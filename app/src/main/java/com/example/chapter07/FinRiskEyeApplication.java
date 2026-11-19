package com.example.chapter07;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * FinRiskEye 全局 Application：为一、二级遗留 Activity 做 Android 15+ edge-to-edge 兜底。
 *
 * <p>背景：工程 targetSdk = 36，Android 15/16 会强制所有页面绘制到状态栏/导航栏后面。
 * 主页面与第三级页面已在上一轮用 {@link EdgeToEdgeInsets} + NoActionBar 主题单独适配，
 * 但一、二级还有大量遗留 Activity（peer、OpenCV 各页、getposition 各页等）仍使用
 * 默认的 DarkActionBar 主题，在 Android 15+ 上标题栏/首行内容会被状态栏遮挡。</p>
 *
 * <p>做法：不改每个遗留 Activity，而是在 Application 里注册
 * {@link ActivityLifecycleCallbacks}，对所有“未适配”的 Activity 的 DecorView 追加
 * systemBars insets padding。这样 ActionBar 会整体下移，不再被状态栏/刘海遮挡。</p>
 *
 * <p>兼容策略：只在 Android 15（API 35）及以上生效；API &lt; 35 完全不做任何事，
 * 保证旧平板（Android 12）上的现有表现不变。</p>
 */
public class FinRiskEyeApplication extends Application {

    /** Android 15 = API 35，系统从此版本开始对 targetSdk 35+ 强制 edge-to-edge。 */
    private static final int EDGE_TO_EDGE_MIN_SDK = 35;

    /** 已在页面内自行调用 {@link EdgeToEdgeInsets#apply} 的 Activity，Application 不再重复加 padding。 */
    private static final Set<String> ADAPTED_ACTIVITY_NAMES = new HashSet<>(Arrays.asList(
            "com.example.chapter07.MainActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelDemoActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelProtectedDemoActivity"
    ));

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < EDGE_TO_EDGE_MIN_SDK) {
            // 旧系统（如平板 Android 12）不强制 edge-to-edge，保持原有布局行为。
            return;
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity,
                                          @Nullable Bundle savedInstanceState) {
                applyLegacyEdgeToEdgeFallback(activity);
            }

            // 其余生命周期回调本类不关心，保持空实现（接口无默认实现，需要全部覆盖）。
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                    @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /**
     * 给未适配的遗留 Activity 追加系统栏 padding。
     *
     * <p>base padding 只在安装 listener 时读取一次，之后每次 insets 变化都基于 base 重算，
     * 不会因为反复收到 insets 而叠加 padding。</p>
     */
    private static void applyLegacyEdgeToEdgeFallback(Activity activity) {
        if (activity == null || ADAPTED_ACTIVITY_NAMES.contains(activity.getClass().getName())) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);

        final View decorView = window.getDecorView();
        if (decorView == null) {
            return;
        }
        // 原始 padding 只读取一次，避免 listener 多次触发时重复累加。
        final int baseLeft = decorView.getPaddingLeft();
        final int baseTop = decorView.getPaddingTop();
        final int baseRight = decorView.getPaddingRight();
        final int baseBottom = decorView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(decorView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom
            );
            return WindowInsetsCompat.CONSUMED;
        });

        // 与页面夜间模式一致：浅色模式用深色系统栏图标，深色模式用浅色图标。
        int nightMode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(window, decorView)
                .setAppearanceLightStatusBars(lightBars);
        WindowCompat.getInsetsController(window, decorView)
                .setAppearanceLightNavigationBars(lightBars);

        // listener 安装后主动请求一次分发，保证首帧就带上 padding。
        decorView.requestApplyInsets();
    }
}

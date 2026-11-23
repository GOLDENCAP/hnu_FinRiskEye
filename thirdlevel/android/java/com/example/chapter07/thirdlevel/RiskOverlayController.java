package com.example.chapter07.thirdlevel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.example.chapter07.R;

/**
 * 只占据屏幕右上角的小型悬浮窗，避免遮挡目标视频软件。
 */
public final class RiskOverlayController {

    /** 与 ScreenRiskService 共用同一日志 tag，便于真机 logcat 取证。 */
    private static final String TAG = "ThirdLevelRisk";

    private final Context context;
    private final Handler mainHandler;
    private final Runnable stopAction;
    private WindowManager windowManager;
    private View overlayView;
    private TextView titleView;
    private TextView detailView;
    private boolean attached;

    public RiskOverlayController(Context context, Handler mainHandler, Runnable stopAction) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.stopAction = stopAction;
    }

    public void show() {
        mainHandler.post(() -> {
            if (attached) {
                return;
            }
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return;
            }
            overlayView = LayoutInflater.from(context)
                    .inflate(R.layout.view_third_level_risk_overlay, null);
            titleView = overlayView.findViewById(R.id.overlayRiskTitle);
            detailView = overlayView.findViewById(R.id.overlayRiskDetail);
            Button stopButton = overlayView.findViewById(R.id.overlayStopButton);
            stopButton.setOnClickListener(v -> stopAction.run());

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            // 手机屏幕通常只有 360~420dp 宽：固定 420dp 的悬浮窗在手机上会超出屏幕被裁切，
            // 这里按“420dp 与 屏幕宽度-32dp 的较小值”自适应（平板上仍是 420dp）。
            // 手机（<600dp）上进一步收窄为 300dp，并让悬浮窗“不可触摸”，避免覆盖区域
            // 拦截目标 App 的点击（手机端通过通知里的“停止”或 App 内按钮停止检测）。
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int screenWidthDp = Math.round(metrics.widthPixels / metrics.density);
            boolean compactPhoneOverlay = screenWidthDp < 600;
            int overlayWidth = compactPhoneOverlay
                    ? Math.min(dp(300), metrics.widthPixels - dp(32))
                    : Math.min(dp(420), metrics.widthPixels - dp(32));
            overlayWidth = Math.max(overlayWidth, dp(240));
            int overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SECURE;
            if (compactPhoneOverlay) {
                overlayFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                stopButton.setVisibility(View.GONE);
            }
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    overlayWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    overlayFlags,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = dp(16);
            params.y = dp(64);

            try {
                windowManager.addView(overlayView, params);
                attached = true;
                Log.i(TAG, "overlay shown, width=" + overlayWidth + "px, compactPhone="
                        + compactPhoneOverlay + ", notTouchable=" + compactPhoneOverlay);
            } catch (Exception ignored) {
                overlayView = null;
            }
        });
    }

    public void update(String title, String detail, int color) {
        mainHandler.post(() -> {
            if (!attached || titleView == null || detailView == null) {
                return;
            }
            titleView.setText(title);
            titleView.setTextColor(color);
            detailView.setText(detail == null ? "" : detail);
            // 悬浮窗本身带 FLAG_SECURE（避免把风险提示再次截屏识别），无法用截图取证，
            // 因此把标题与详情写入 logcat，供真机验收/联调抓取（不含画面/音频内容）。
            String oneLine = (detail == null ? "" : detail).replace('\n', ' ');
            Log.i(TAG, "overlay update: " + title + " | " + oneLine);
        });
    }

    public void hide() {
        mainHandler.post(() -> {
            if (!attached || overlayView == null || windowManager == null) {
                return;
            }
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            attached = false;
            overlayView = null;
            titleView = null;
            detailView = null;
        });
    }

    public static int colorNormal() {
        return Color.rgb(24, 128, 56);
    }

    public static int colorWarning() {
        return Color.rgb(207, 65, 42);
    }

    public static int colorNeutral() {
        return Color.WHITE;
    }

    public static int colorLimited() {
        return Color.rgb(232, 143, 32);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

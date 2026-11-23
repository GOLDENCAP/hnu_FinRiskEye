package com.example.chapter07.thirdlevel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.chapter07.R;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第三级跨应用画面检测服务。
 *
 * 数据流：
 * MediaProjection -> ImageReader -> 中文OCR -> 文字去重
 * -> /api/v1/risk/screen 或 /api/v1/risk/audio -> 小型悬浮窗。
 *
 * 该服务只处理第三级屏幕文字，不复用前两级的 WebRTC 音视频链路。
 */
public class ScreenRiskService extends Service {

    public interface StatusListener {
        void onStatusChanged(boolean running, String title, String detail);
    }

    public static final String ACTION_START =
            "com.example.chapter07.thirdlevel.action.START_SCREEN_RISK";
    public static final String ACTION_STOP =
            "com.example.chapter07.thirdlevel.action.STOP_SCREEN_RISK";
    public static final String EXTRA_RESULT_CODE = "media_projection_result_code";
    public static final String EXTRA_RESULT_DATA = "media_projection_result_data";
    public static final String EXTRA_ENABLE_AUDIO = "enable_audio_capture";

    private static final String TAG = "ThirdLevelRisk";
    private static final String CHANNEL_ID = "third_level_screen_risk";
    private static final int NOTIFICATION_ID = 31001;
    private static final long SAMPLE_INTERVAL_MS = 2_000L;
    private static final int MAX_OCR_IMAGE_EDGE = 2600;
    private static final int MAX_VISION_IMAGE_EDGE = 1600;
    private static final int VISION_JPEG_QUALITY = 65;

    // OCR 看门狗：ML Kit recognize 的回调长时间不返回时降级为“无 OCR 文本、仅图片分析”，
    // 避免 processing 一直为 true 导致后续帧永远不再分析。真机调优只需改这一处常量。
    private static final long OCR_TIMEOUT_MS = 8_000L;

    // 悬浮窗权限检查间隔：与采样频率一致，Settings.canDrawOverlays 调用开销很小，
    // 用于发现用户在系统设置里中途撤销悬浮窗权限（12/8 验收项）。
    private static final long OVERLAY_PERMISSION_CHECK_INTERVAL_MS = 2_000L;

    // MIUI 等定制系统上 Settings.canDrawOverlays 偶发误判（真机出现过一次“权限已撤销”导致检测被误停），
    // 因此连续 3 次（约 6 秒）都检测不到权限才停止检测；只失败 1-2 次时仅提示、继续检测。
    private static final int OVERLAY_PERMISSION_MISSING_SHUTDOWN_THRESHOLD = 3;
    private static final String OVERLAY_PERMISSION_WARNING =
            "悬浮窗权限可能已撤销，风险提示可能不可见";

    // 黑屏/受保护画面限制说明：12 月 8 日验收要求“采集受限”必须有明确状态，
    // 不能把黑屏当成普通画面返回 none 后显示“无风险”。
    private static final String FRAME_LIMITED_BLACK_HINT =
            "画面：黑屏或受保护画面（可能被目标应用限制）";
    private static final String FRAME_LIMITED_BLANK_HINT =
            "画面：空白或纯色画面，未采集到可分析内容";
    private static final String FRAME_LIMITED_SUMMARY = "画面：检测受限";

    private static volatile StatusListener statusListener;
    private static volatile boolean running;
    private static volatile StatusSnapshot lastStatus =
            new StatusSnapshot(false, "尚未启动", "");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean audioProcessing = new AtomicBoolean(false);
    private final AtomicBoolean audioSilenceReported = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final ImageDeduplicator imageDeduplicator = new ImageDeduplicator();
    private final FrameQualityDetector frameQualityDetector = new FrameQualityDetector();
    private final FrameLimitedGate frameLimitedGate = new FrameLimitedGate();

    // 在途 OCR 尝试：用一次性标记保证 ML Kit 回调与看门狗超时只收尾一次（位图只回收一次）。
    private volatile OcrAttempt activeOcrAttempt;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ScreenTextRecognizer textRecognizer;
    private RiskApiClient apiClient;
    private RiskApiClient audioApiClient;
    private AppAudioCapture audioCapture;
    private RiskOverlayController overlayController;
    private String sessionId;
    private long lastSampleAtMs;
    private boolean captureScheduled;
    private boolean screenRiskActive;
    private int screenClearResults;
    private String screenRiskLevel = "";
    private String screenRiskDetail = "";
    private boolean audioRiskActive;
    private int audioClearResults;
    private String audioRiskLevel = "";
    private String audioRiskDetail = "";
    private boolean audioEnabled;
    private boolean audioHasAnalyzed;
    private String screenSummary = "画面：等待分析";
    private String audioSummary = "语音：未启用";
    private String screenError = "";
    private boolean screenFrameLimited;
    private String screenFrameLimitedHint = "";
    private String audioError = "";
    private String lastOcrText = "";

    // 12/8 验收“记录延迟”与本次会话统计：最近一次分析时间/耗时、分析次数、风险次数。
    // 时间基准统一用 SystemClock.elapsedRealtime()，格式化逻辑在 AnalysisStatsFormatter。
    private long lastAnalysisAtMs;
    private int lastLatencyMs = -1;
    private int analysisCount;
    private int riskCount;
    private long lastOverlayPermissionCheckAtMs;
    // 连续多少次检查不到悬浮窗权限（用于 MIUI 偶发误判的迟滞判定）。
    private int consecutiveOverlayPermissionMisses;
    // 悬浮窗权限“疑似撤销”时的提示（1-2 次失败时显示；恢复后清空）。
    private String overlayPermissionWarning = "";

    public static void setStatusListener(@Nullable StatusListener listener) {
        statusListener = listener;
    }

    public static boolean isRunning() {
        return running;
    }

    public static StatusSnapshot getLastStatus() {
        return lastStatus;
    }

    public static final class StatusSnapshot {
        public final boolean running;
        public final String title;
        public final String detail;

        StatusSnapshot(boolean running, String title, String detail) {
            this.running = running;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** 一次在途 OCR 尝试：回调、看门狗超时、shutdown 三条路径通过 finished 标记只收尾一次。 */
    private static final class OcrAttempt {
        final Bitmap bitmap;
        final String imageBase64;
        final boolean imageChanged;
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeoutTask;

        OcrAttempt(Bitmap bitmap, String imageBase64, boolean imageChanged) {
            this.bitmap = bitmap;
            this.imageBase64 = imageBase64;
            this.imageChanged = imageChanged;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown(true, "检测已停止");
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        startForegroundNotification("正在准备屏幕检测");
        if (running) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = getProjectionIntent(intent);
        if (resultData == null) {
            shutdown(false, "屏幕授权数据无效，请重新授权");
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            shutdown(false, "缺少悬浮窗权限");
            return START_NOT_STICKY;
        }

        boolean enableAudio = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO, false);
        startScreenSession(resultCode, resultData, enableAudio);
        return START_NOT_STICKY;
    }

    private void startScreenSession(int resultCode, Intent resultData, boolean enableAudio) {
        try {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager == null) {
                shutdown(false, "设备不支持屏幕采集");
                return;
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                shutdown(false, "屏幕授权创建失败，请重新授权");
                return;
            }

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager =
                    (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null || windowManager.getDefaultDisplay() == null) {
                shutdown(true, "无法获取屏幕尺寸");
                return;
            }
            windowManager.getDefaultDisplay().getRealMetrics(metrics);

            captureThread = new HandlerThread("ThirdLevelScreenCapture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
            imageReader = ImageReader.newInstance(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    PixelFormat.RGBA_8888,
                    2
            );

            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // 系统撤销屏幕共享（用户主动停止、切后台被系统回收等）时给出明确状态，
                    // 而不是静默停止，便于验收确认“权限撤销”场景。
                    shutdown(false, "屏幕共享授权已撤销，请重新授权后开启检测");
                }
            };
            mediaProjection.registerCallback(projectionCallback, mainHandler);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ThirdLevelRiskCapture",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );
            if (virtualDisplay == null) {
                shutdown(true, "屏幕采集启动失败");
                return;
            }

            textRecognizer = new ScreenTextRecognizer();

            sessionId = "session-" + new SimpleDateFormat(
                    "yyyyMMddHHmmss",
                    Locale.US
            ).format(new Date());
            screenRiskActive = false;
            screenClearResults = 0;
            screenRiskLevel = "";
            screenRiskDetail = "";
            audioRiskActive = false;
            audioClearResults = 0;
            audioRiskLevel = "";
            audioRiskDetail = "";
            this.audioEnabled = enableAudio;
            audioHasAnalyzed = false;
            screenSummary = "画面：等待分析";
            audioSummary = enableAudio ? "语音：等待采集" : "语音：未启用";
            screenError = "";
            screenFrameLimited = false;
            screenFrameLimitedHint = "";
            audioError = "";
            audioSilenceReported.set(false);
            lastOcrText = "";
            // 新会话重置迟滞门控与延迟/会话统计。
            frameLimitedGate.reset();
            lastAnalysisAtMs = 0L;
            lastLatencyMs = -1;
            analysisCount = 0;
            riskCount = 0;
            lastOverlayPermissionCheckAtMs = SystemClock.elapsedRealtime();
            consecutiveOverlayPermissionMisses = 0;
            overlayPermissionWarning = "";

            String serviceUrl = ThirdLevelActivity.loadServerUrl(this);
            String serviceToken = ThirdLevelActivity.loadApiToken(this);
            apiClient = new RiskApiClient(serviceUrl, serviceToken);
            audioApiClient = new RiskApiClient(serviceUrl, serviceToken);
            overlayController = new RiskOverlayController(
                    this,
                    mainHandler,
                    () -> shutdown(true, "检测已停止")
            );
            overlayController.show();
            overlayController.update(
                    "第三级检测已启动",
                    "每2秒检查一次屏幕变化",
                    RiskOverlayController.colorNormal()
            );

            running = true;
            updateOverlay("检测中",
                    screenSummary + (enableAudio ? "\n" + audioSummary : ""),
                    RiskOverlayController.colorNeutral());
            if (enableAudio) {
                startAudioCapture();
            }
            captureScheduled = true;
            // 刚授权时系统“屏幕共享”对话框仍在退场动画中，若立即抓帧会把对话框文案
            // （含“密码/泄露”等提示）送去风险分析，导致启动瞬间误报高风险。
            // 这里把首帧时间基准设为当前时刻，首个分析延迟一个采样周期（约 2 秒）。
            lastSampleAtMs = SystemClock.elapsedRealtime();
            captureHandler.post(captureTick);
            Log.i(TAG, "screen risk session started, " + metrics.widthPixels
                    + "x" + metrics.heightPixels);
        } catch (Throwable error) {
            Log.e(TAG, "startScreenSession failed", error);
            shutdown(true, "屏幕检测启动失败：" + safeMessage(error));
        }
    }

    private final Runnable captureTick = new Runnable() {
        @Override
        public void run() {
            if (!running || shuttingDown.get() || imageReader == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            // 采集期间定期检查悬浮窗权限：权限真的撤销后悬浮窗会被系统移除，
            // 继续采集只会“静默无提示”；但 MIUI 等系统偶发误判，因此连续多次失败才停止。
            if (now - lastOverlayPermissionCheckAtMs >= OVERLAY_PERMISSION_CHECK_INTERVAL_MS) {
                lastOverlayPermissionCheckAtMs = now;
                if (!Settings.canDrawOverlays(ScreenRiskService.this)) {
                    consecutiveOverlayPermissionMisses++;
                    Log.w(TAG, "overlay permission check failed, consecutive="
                            + consecutiveOverlayPermissionMisses);
                    if (consecutiveOverlayPermissionMisses
                            >= OVERLAY_PERMISSION_MISSING_SHUTDOWN_THRESHOLD) {
                        // 连续 3 次（约 6 秒）都拿不到权限才认为确实被撤销，避免偶发误判误停检测。
                        shutdown(true, "悬浮窗权限已撤销，请重新授权后开启检测");
                        return;
                    }
                    // 只失败 1-2 次：把可能的风险写进状态，检测继续跑，不停止服务。
                    updateOverlayPermissionWarning(true);
                } else if (consecutiveOverlayPermissionMisses > 0) {
                    // 权限检查恢复正常：清除误判计数与提示，继续正常检测。
                    consecutiveOverlayPermissionMisses = 0;
                    updateOverlayPermissionWarning(false);
                }
            }
            if (!processing.get() && now - lastSampleAtMs >= SAMPLE_INTERVAL_MS) {
                Image image = null;
                try {
                    image = imageReader.acquireLatestImage();
                    if (image != null) {
                        lastSampleAtMs = now;
                        processing.set(true);
                        Bitmap bitmap = imageToBitmap(image);
                        image.close();
                        image = null;
                        FrameQualityDetector.Quality quality = evaluateFrameQuality(bitmap);
                        // 迟滞门控：单帧瞬态黑屏不刷“检测受限”，连续 2 帧受限才进入。
                        boolean limited = frameLimitedGate.onFrame(quality);
                        if (quality == FrameQualityDetector.Quality.OK) {
                            // 从黑屏/受保护画面恢复时强制重新分析，避免沿用受限前的旧状态。
                            boolean recovered = releaseFrameLimitedState();
                            boolean imageChanged = imageDeduplicator.shouldAnalyze(bitmap);
                            handleCapturedBitmap(bitmap, imageChanged || recovered);
                        } else {
                            // 黑屏/受保护画面不发送风险请求，也不清除已有风险提示；
                            // 迟滞未满时只丢弃这一帧，保持原状态不变（不刷屏）。
                            bitmap.recycle();
                            processing.set(false);
                            if (limited) {
                                markFrameLimited(quality);
                            } else {
                                Log.d(TAG, "single limited frame ignored by hysteresis: "
                                        + quality);
                            }
                        }
                    }
                } catch (Throwable error) {
                    processing.set(false);
                    Log.e(TAG, "screen frame processing failed", error);
                    if (!screenFrameLimited) {
                        screenError = "画面处理失败：" + safeMessage(error);
                    }
                    renderCurrentState();
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }
            if (running && captureScheduled) {
                captureHandler.postDelayed(this, 500L);
            }
        }
    };

    /**
     * 判断当前画面是否为黑屏或近乎纯色/空白。
     *
     * <p>只做本地亮度统计，不发送任何数据；先降采样到 64×64 再取像素，
     * 保证在 2 秒一次的采样频率下开销很小。检测过程本身失败时按正常画面继续，
     * 避免检测器误报导致风险分析被错误停掉。</p>
     */
    private FrameQualityDetector.Quality evaluateFrameQuality(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return FrameQualityDetector.Quality.OK;
        }
        Bitmap sample = null;
        try {
            int size = FrameQualityDetector.SAMPLE_SIZE;
            sample = Bitmap.createScaledBitmap(bitmap, size, size, true);
            int[] pixels = new int[size * size];
            sample.getPixels(pixels, 0, size, 0, 0, size, size);
            return frameQualityDetector.detect(pixels, size, size);
        } catch (Throwable error) {
            Log.w(TAG, "frame quality check failed, continue analysis", error);
            return FrameQualityDetector.Quality.OK;
        } finally {
            if (sample != null && sample != bitmap) {
                sample.recycle();
            }
        }
    }

    /**
     * 记录黑屏/受保护画面状态，悬浮窗走“检测受限”橙色分支。
     * 同一类受限画面持续存在时不重复刷新通知和界面，避免每 2 秒刷屏。
     */
    private void markFrameLimited(FrameQualityDetector.Quality quality) {
        String hint = quality == FrameQualityDetector.Quality.BLACK
                ? FRAME_LIMITED_BLACK_HINT
                : FRAME_LIMITED_BLANK_HINT;
        if (screenFrameLimited && hint.equals(screenFrameLimitedHint)) {
            return;
        }
        screenFrameLimited = true;
        screenFrameLimitedHint = hint;
        screenError = hint;
        if (!screenRiskActive) {
            screenSummary = FRAME_LIMITED_SUMMARY;
        }
        Log.w(TAG, "frame quality limited: " + quality);
        renderCurrentState();
    }

    /**
     * 画面恢复正常后清除黑屏/受保护状态。
     * 已有风险提示继续保留；没有风险时先显示“检测中”，避免重新分析完成前误报“无风险”。
     *
     * @return 是否从受限状态恢复，用于强制重新分析恢复正常后的第一帧
     */
    private boolean releaseFrameLimitedState() {
        if (!screenFrameLimited) {
            return false;
        }
        screenFrameLimited = false;
        screenFrameLimitedHint = "";
        screenError = "";
        screenSummary = "画面：等待分析";
        if (hasActiveRisk()) {
            renderCurrentState();
        } else {
            String detail = screenSummary + (audioEnabled ? "\n" + audioSummary : "");
            updateOverlay("检测中", detail, RiskOverlayController.colorNeutral());
            notifyStatus(true, "检测中", detail);
        }
        Log.i(TAG, "frame quality recovered, continue analysis");
        return true;
    }

    private void handleCapturedBitmap(Bitmap bitmap, boolean imageChanged) {
        if (bitmap == null) {
            processing.set(false);
            screenError = "画面：未获取到有效画面";
            renderCurrentState();
            return;
        }
        final String imageBase64 = encodeVisionImage(bitmap);
        if (imageBase64.isEmpty()) {
            bitmap.recycle();
            processing.set(false);
            screenError = "画面：无法生成待分析图片";
            renderCurrentState();
            return;
        }
        startOcrWithWatchdog(bitmap, imageBase64, imageChanged);
    }

    /**
     * 带看门狗的 OCR：ML Kit 的回调在极端情况下可能长时间不返回，
     * 因此 8 秒内没有成功或失败回调时，主动回收位图、降级为“无 OCR 文本、仅图片分析”。
     *
     * <p>并发约束：ML Kit 回调与看门狗超时可能竞争，两者都通过
     * {@link #finishOcrAttempt(OcrAttempt)} 的一次性 {@link AtomicBoolean} 收尾，
     * 保证 bitmap 只回收一次、后续分析只触发一次。</p>
     */
    private void startOcrWithWatchdog(Bitmap bitmap, String imageBase64, boolean imageChanged) {
        ScreenTextRecognizer recognizer = textRecognizer;
        if (recognizer == null) {
            // 服务正在停止/识别器已关闭：直接降级为仅图片分析，避免空指针。
            recycleQuietly(bitmap);
            processRecognizedText("", imageBase64, imageChanged);
            return;
        }
        final OcrAttempt attempt = new OcrAttempt(bitmap, imageBase64, imageChanged);
        attempt.timeoutTask = () -> handleOcrTimeout(attempt);
        activeOcrAttempt = attempt;
        mainHandler.postDelayed(attempt.timeoutTask, OCR_TIMEOUT_MS);
        try {
            recognizer.recognize(bitmap, new ScreenTextRecognizer.Callback() {
                @Override
                public void onRecognized(String text) {
                    if (!finishOcrAttempt(attempt)) {
                        // 看门狗已按超时降级处理，这里忽略迟到的回调。
                        return;
                    }
                    Log.d(TAG, "OCR completed, textLength="
                            + (text == null ? 0 : text.length()));
                    processRecognizedText(text, attempt.imageBase64, attempt.imageChanged);
                }

                @Override
                public void onError(String message) {
                    if (!finishOcrAttempt(attempt)) {
                        return;
                    }
                    Log.w(TAG, "OCR failed, continue with screen analysis: " + message);
                    processRecognizedText("", attempt.imageBase64, attempt.imageChanged);
                }
            });
        } catch (Throwable error) {
            // OCR 同步抛异常（例如服务停止时识别器已关闭）时回收位图，
            // 仍用图片继续分析，避免画面采集链路卡死或位图泄漏。
            if (finishOcrAttempt(attempt)) {
                Log.e(TAG, "OCR start failed, continue with screen analysis", error);
                processRecognizedText("", attempt.imageBase64, attempt.imageChanged);
            }
        }
    }

    /** OCR 看门狗超时：回收位图并降级为“无 OCR 文本、仅图片分析”。 */
    private void handleOcrTimeout(OcrAttempt attempt) {
        if (!finishOcrAttempt(attempt)) {
            return;
        }
        Log.w(TAG, "OCR watchdog timeout after " + OCR_TIMEOUT_MS
                + "ms, degrade to image-only analysis");
        processRecognizedText("", attempt.imageBase64, attempt.imageChanged);
    }

    /**
     * OCR 尝试的唯一收尾点：取消看门狗、回收位图，并标记已完成。
     *
     * @return true 表示当前调用方是唯一收尾者，可以继续后续分析；
     * false 表示该尝试已被另一条路径（回调/超时/shutdown）收尾，调用方必须直接返回
     */
    private boolean finishOcrAttempt(OcrAttempt attempt) {
        if (attempt == null || !attempt.finished.compareAndSet(false, true)) {
            return false;
        }
        mainHandler.removeCallbacks(attempt.timeoutTask);
        if (activeOcrAttempt == attempt) {
            activeOcrAttempt = null;
        }
        recycleQuietly(attempt.bitmap);
        return true;
    }

    /** shutdown 时清理在途 OCR：移除超时器并回收位图，避免服务退出后看门狗仍触发。 */
    private void cancelActiveOcrAttempt() {
        OcrAttempt attempt = activeOcrAttempt;
        activeOcrAttempt = null;
        if (attempt == null) {
            return;
        }
        mainHandler.removeCallbacks(attempt.timeoutTask);
        if (attempt.finished.compareAndSet(false, true)) {
            recycleQuietly(attempt.bitmap);
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
            // 位图已被其他路径回收时忽略，保证收尾路径不崩溃。
        }
    }

    private void processRecognizedText(String text, String imageBase64, boolean imageChanged) {
        if (!running || shuttingDown.get()) {
            processing.set(false);
            return;
        }
        String trimmed = text == null ? "" : text.trim();
        String normalizedOcrText = TextDeduplicator.normalize(trimmed);
        boolean textChanged = !normalizedOcrText.equals(lastOcrText);
        lastOcrText = normalizedOcrText;
        if (!imageChanged && !textChanged) {
            processing.set(false);
            return;
        }
        // shutdown 可能在本线程判断 running 之后、发请求之前把 apiClient 置空，
        // 这里取本地引用并判空，避免极窄竞态窗口内 NPE 导致采集回调线程崩溃。
        RiskApiClient client = apiClient;
        if (client == null) {
            processing.set(false);
            return;
        }

        String capturedAt = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.US
        ).format(new Date());
        RiskModels.RiskRequest request = new RiskModels.RiskRequest(
                sessionId,
                UUID.randomUUID().toString(),
                "screen",
                capturedAt,
                trimmed
        );

        Log.d(TAG, "sending risk request, requestId=" + request.requestId);
        if (!hasActiveRisk()) {
            updateOverlay("检测中", screenSummary + "\n" + audioSummary,
                    RiskOverlayController.colorNeutral());
        }
        client.analyzeScreen(request, imageBase64, result -> mainHandler.post(() -> {
            processing.set(false);
            if (!running || shuttingDown.get()) {
                return;
            }
            recordAnalysis(result);
            if (!result.success) {
                Log.w(TAG, "risk request failed: " + result.errorCode);
                handleAnalysisError(false, result);
                return;
            }
            Log.i(TAG, "screen risk result: " + result.riskLevel
                    + ", latencyMs=" + result.latencyMs);
            applyRiskResult(false, result);
        }));
    }

    private void startAudioCapture() {
        if (mediaProjection == null) {
            return;
        }
        audioCapture = new AppAudioCapture(mediaProjection, new AppAudioCapture.Listener() {
            @Override
            public void onChunk(byte[] wavBytes, int durationMs) {
                audioSilenceReported.set(false);
                handleAudioChunk(wavBytes, durationMs);
            }

            @Override
            public void onSilence() {
                if (audioSilenceReported.compareAndSet(false, true)) {
                    mainHandler.post(() -> {
                        if (!running || hasActiveRisk()) {
                            return;
                        }
                        audioSummary = audioHasAnalyzed
                                ? "语音：当前未捕获到播放音频（上次分析正常）"
                                : "语音：未捕获到应用播放音频";
                        renderCurrentState();
                    });
                }
            }

            @Override
            public void onUnavailable(String message) {
                Log.w(TAG, "application audio unavailable: " + message);
                mainHandler.post(() -> {
                    if (!running) {
                        return;
                    }
                    audioSummary = "语音：采集不可用";
                    audioError = message;
                    renderCurrentState();
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "application audio error: " + message);
                mainHandler.post(() -> {
                    if (!running) {
                        return;
                    }
                    audioSummary = "语音：采集失败";
                    audioError = message;
                    renderCurrentState();
                });
            }
        });
        audioCapture.start();
    }

    private void handleAudioChunk(byte[] wavBytes, int durationMs) {
        if (!running || shuttingDown.get() || audioApiClient == null
                || !audioProcessing.compareAndSet(false, true)) {
            return;
        }
        try {
            String audioBase64 = android.util.Base64.encodeToString(
                    wavBytes,
                    android.util.Base64.NO_WRAP
            );
            String capturedAt = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    Locale.US
            ).format(new Date());
            RiskModels.RiskRequest request = new RiskModels.RiskRequest(
                    sessionId,
                    UUID.randomUUID().toString(),
                    "audio",
                    capturedAt,
                    "",
                    durationMs
            );
            mainHandler.post(() -> {
                if (running && !hasActiveRisk()) {
                    updateOverlay("检测中",
                            screenSummary + "\n" + audioSummary,
                            RiskOverlayController.colorNeutral());
                }
            });
            audioApiClient.analyzeAudio(request, audioBase64, result -> mainHandler.post(() -> {
                audioProcessing.set(false);
                if (!running || shuttingDown.get()) {
                    return;
                }
                recordAnalysis(result);
                if (!result.success) {
                    if ("TRANSCRIPTION_EMPTY".equals(result.errorCode)) {
                        RiskModels.RiskResult emptyResult = RiskModels.RiskResult.success(
                                "none", "", "未识别到可分析语音");
                        // 保留服务端真实耗时，便于悬浮窗统计展示。
                        emptyResult.latencyMs = result.latencyMs;
                        applyRiskResult(true, emptyResult);
                    } else {
                        handleAnalysisError(true, result);
                    }
                    return;
                }
                Log.i(TAG, "audio risk result: " + result.riskLevel
                        + ", latencyMs=" + result.latencyMs);
                applyRiskResult(true, result);
            }));
        } catch (Throwable error) {
            audioProcessing.set(false);
            Log.e(TAG, "audio chunk encode failed", error);
        }
    }

    private void applyRiskResult(boolean fromAudio, RiskModels.RiskResult result) {
        String detail = joinNonEmpty(result.evidence, result.reason);
        if (result.shouldAlert()) {
            if (fromAudio) {
                audioRiskActive = true;
                audioClearResults = 0;
                audioRiskLevel = result.riskLevel;
                audioRiskDetail = detail;
                audioHasAnalyzed = true;
                audioSummary = "语音：发现风险";
                audioError = "";
            } else {
                screenRiskActive = true;
                screenClearResults = 0;
                screenRiskLevel = result.riskLevel;
                screenRiskDetail = detail;
                screenSummary = "画面：发现风险";
                if (!screenFrameLimited) {
                    screenError = "";
                }
            }
            renderCurrentState();
            return;
        }

        if (!fromAudio && screenFrameLimited) {
            // 黑屏/受保护画面期间，旧画面在途的结果可能返回 none；
            // 此时不能清除已有风险，保持受限状态等待画面恢复后重新分析。
            renderCurrentState();
            return;
        }

        int clearCount;
        boolean sourceActive;
        if (fromAudio) {
            audioClearResults++;
            clearCount = audioClearResults;
            sourceActive = audioRiskActive;
        } else {
            screenClearResults++;
            clearCount = screenClearResults;
            sourceActive = screenRiskActive;
        }
        if (sourceActive && clearCount < 2) {
            renderCurrentState();
            return;
        }

        if (fromAudio) {
            audioRiskActive = false;
            audioClearResults = 0;
            audioHasAnalyzed = true;
            audioSummary = "未识别到可分析语音".equals(result.reason)
                    ? "语音：未识别到有效内容"
                    : "语音：未发现风险";
            audioError = "";
        } else {
            screenRiskActive = false;
            screenClearResults = 0;
            screenSummary = "画面：未发现风险";
            screenError = "";
        }
        renderCurrentState();
    }

    private void handleAnalysisError(boolean fromAudio, RiskModels.RiskResult result) {
        if (fromAudio) {
            audioError = "分析失败：" + result.message;
            audioSummary = "语音：分析失败";
        } else if (!screenFrameLimited) {
            // 黑屏/受保护画面期间保留受限说明，不被在途请求的失败信息覆盖。
            screenError = "分析失败：" + result.message;
            screenSummary = "画面：分析失败";
        }
        renderCurrentState();
    }

    private void renderCurrentState() {
        // 12/8 验收“记录延迟”：无论有风险/无风险/检测受限，详情末尾都统一追加一行统计，
        // 追加在最后不改变已有分行结构。
        String statsLine = formatAnalysisStats();
        if (screenRiskActive || audioRiskActive) {
            String level = riskRank(audioRiskLevel) >= riskRank(screenRiskLevel)
                    ? audioRiskLevel
                    : screenRiskLevel;
            StringBuilder detail = new StringBuilder()
                    .append("风险等级：")
                    .append(chineseRiskLevel(level));
            if (screenRiskActive) {
                detail.append("\n\n画面分析：\n").append(screenRiskDetail);
            } else {
                detail.append("\n\n").append(screenSummary);
            }
            if (audioRiskActive) {
                detail.append("\n\n语音分析：\n").append(audioRiskDetail);
            } else if (audioEnabled) {
                detail.append("\n\n").append(audioSummary);
            }
            if (!screenError.isEmpty()) {
                detail.append("\n\n").append(screenError);
            }
            if (!audioError.isEmpty()) {
                detail.append("\n\n").append(audioError);
            }
            if (!overlayPermissionWarning.isEmpty()) {
                // 悬浮窗权限偶发误判：只提示，不停检测（连续 3 次失败才会 shutdown）。
                detail.append("\n\n").append(overlayPermissionWarning);
            }
            detail.append("\n\n").append(statsLine);
            updateOverlay("存在风险", detail.toString(), RiskOverlayController.colorWarning());
            notifyStatus(true, "存在风险", detail.toString());
            return;
        }

        StringBuilder detail = new StringBuilder(screenSummary);
        if (audioEnabled) {
            detail.append("\n").append(audioSummary);
        }
        if (!screenError.isEmpty()) {
            detail.append("\n").append(screenError);
        }
        if (!audioError.isEmpty()) {
            detail.append("\n").append(audioError);
        }
        if (!overlayPermissionWarning.isEmpty()) {
            // 悬浮窗权限偶发误判：只提示，不停检测（连续 3 次失败才会 shutdown）。
            detail.append("\n").append(overlayPermissionWarning);
        }
        detail.append("\n").append(statsLine);

        boolean limited = screenFrameLimited
                || !screenError.isEmpty()
                || !audioError.isEmpty()
                || (audioEnabled && audioSummary.contains("不可用"));
        String title = limited ? "检测受限" : "无风险";
        int color = limited
                ? RiskOverlayController.colorLimited()
                : RiskOverlayController.colorNormal();
        updateOverlay(title, detail.toString(), color);
        notifyStatus(true, title, detail.toString());
    }

    /**
     * 悬浮窗权限“疑似撤销”提示的显示/清除。
     *
     * <p>仅当提示状态变化时才刷新悬浮窗/通知，避免每 2 秒重复刷屏；
     * 该提示不会停止检测（连续 3 次检查失败才会走 shutdown）。</p>
     */
    private void updateOverlayPermissionWarning(boolean warning) {
        String newWarning = warning ? OVERLAY_PERMISSION_WARNING : "";
        if (newWarning.equals(overlayPermissionWarning)) {
            return;
        }
        overlayPermissionWarning = newWarning;
        if (warning) {
            Log.w(TAG, "overlay permission missing once or twice, keep detecting: " + newWarning);
        } else {
            Log.i(TAG, "overlay permission check recovered, clear warning");
        }
        renderCurrentState();
    }

    /**
     * 记录一次已完成的风险分析（网络/服务失败也计入“分析次数”，便于验收观察延迟与稳定性）。
     * 只在主线程回调里调用，不需要额外同步。
     */
    private void recordAnalysis(RiskModels.RiskResult result) {
        if (result == null) {
            return;
        }
        lastAnalysisAtMs = SystemClock.elapsedRealtime();
        lastLatencyMs = result.latencyMs;
        analysisCount++;
        if (result.shouldAlert()) {
            riskCount++;
        }
    }

    /**
     * 生成“最近分析 + 本次会话”统计行。
     * SystemClock 只在 Android 侧使用；格式化逻辑在纯 Java 的 AnalysisStatsFormatter 中，便于单测。
     */
    private String formatAnalysisStats() {
        long elapsedSinceLastAnalysis = analysisCount > 0
                ? Math.max(0L, SystemClock.elapsedRealtime() - lastAnalysisAtMs)
                : -1L;
        return AnalysisStatsFormatter.formatLine(
                elapsedSinceLastAnalysis,
                lastLatencyMs,
                analysisCount,
                riskCount
        );
    }

    private boolean hasActiveRisk() {
        return screenRiskActive || audioRiskActive;
    }

    private static int riskRank(String level) {
        if ("high".equals(level)) {
            return 3;
        }
        if ("medium".equals(level)) {
            return 2;
        }
        if ("low".equals(level)) {
            return 1;
        }
        return 0;
    }

    private Bitmap imageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
        );
        padded.copyPixelsFromBuffer(buffer);

        Bitmap cropped;
        if (padded.getWidth() == width) {
            cropped = padded;
        } else {
            cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            padded.recycle();
        }

        // 去掉状态栏（时间/电量/“屏幕共享中”等系统提示）与底部导航条：
        // 系统文案可能被视觉模型误判为风险（例如“屏幕共享中”被判为高风险操作），
        // 同时也能减少 OCR 噪声；目标应用内容区域基本不受影响。
        int statusBarHeight = getSystemBarHeight("status_bar_height");
        int navigationBarHeight = getSystemBarHeight("navigation_bar_height");
        if (statusBarHeight > 0 || navigationBarHeight > 0) {
            int cropTop = Math.min(statusBarHeight, cropped.getHeight() / 5);
            int cropBottom = Math.min(navigationBarHeight, cropped.getHeight() / 5);
            int cropHeight = cropped.getHeight() - cropTop - cropBottom;
            if (cropHeight > 100) {
                Bitmap content = Bitmap.createBitmap(
                        cropped, 0, cropTop, cropped.getWidth(), cropHeight);
                if (content != cropped) {
                    cropped.recycle();
                    cropped = content;
                }
            }
        }

        int maxEdge = Math.max(cropped.getWidth(), cropped.getHeight());
        if (maxEdge <= MAX_OCR_IMAGE_EDGE) {
            return cropped;
        }
        float scale = (float) MAX_OCR_IMAGE_EDGE / maxEdge;
        Bitmap scaled = Bitmap.createScaledBitmap(
                cropped,
                Math.round(cropped.getWidth() * scale),
                Math.round(cropped.getHeight() * scale),
                true
        );
        if (scaled != cropped) {
            cropped.recycle();
        }
        return scaled;
    }

    private int getSystemBarHeight(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private String encodeVisionImage(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int maxEdge = Math.max(width, height);
        Bitmap output = source;
        if (maxEdge > MAX_VISION_IMAGE_EDGE) {
            float scale = (float) MAX_VISION_IMAGE_EDGE / maxEdge;
            output = Bitmap.createScaledBitmap(
                    source,
                    Math.round(width * scale),
                    Math.round(height * scale),
                    true
            );
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            output.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, stream);
            return android.util.Base64.encodeToString(
                    stream.toByteArray(),
                    android.util.Base64.NO_WRAP
            );
        } catch (Exception ignored) {
            return "";
        } finally {
            if (output != source) {
                output.recycle();
            }
        }
    }

    private void updateOverlay(String title, String detail, int color) {
        if (overlayController != null) {
            overlayController.update(title, detail, color);
        }
        updateNotification(title + (detail == null || detail.isEmpty() ? "" : "：" + detail));
    }

    private void notifyStatus(boolean isRunning, String title, String detail) {
        lastStatus = new StatusSnapshot(isRunning, title, detail);
        StatusListener listener = statusListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onStatusChanged(isRunning, title, detail));
        }
    }

    private synchronized void shutdown(boolean stopProjection, String message) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        running = false;
        // 取消在途 OCR 看门狗与位图，保证 shutdown 后超时器不会再次触发；
        // 同时复位 processing，避免停止后采集 tick 仍被视为“正在处理”。
        processing.set(false);
        cancelActiveOcrAttempt();
        if (audioCapture != null) {
            try {
                audioCapture.stop();
            } catch (Exception ignored) {
            }
            audioCapture = null;
        }
        if (audioApiClient != null) {
            audioApiClient.cancel();
            audioApiClient = null;
        }
        audioProcessing.set(false);
        if (imageReader != null) {
            try {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            try {
                if (projectionCallback != null) {
                    mediaProjection.unregisterCallback(projectionCallback);
                }
                if (stopProjection) {
                    mediaProjection.stop();
                }
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }
        if (apiClient != null) {
            apiClient.cancel();
            apiClient = null;
        }
        if (textRecognizer != null) {
            try {
                textRecognizer.close();
            } catch (Exception ignored) {
            }
            textRecognizer = null;
        }
        if (overlayController != null) {
            overlayController.hide();
            overlayController = null;
        }
        if (captureThread != null) {
            captureScheduled = false;
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }

        notifyStatus(false, "检测已停止", message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Log.i(TAG, "screen risk session stopped: " + message);
    }

    private Intent getProjectionIntent(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return source.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void startForegroundNotification(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, ThirdLevelActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, ScreenRiskService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("第三级跨应用风险检测")
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "第三级屏幕风险检测",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("第三级屏幕画面和可采集应用音频风险检测");
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static String chineseRiskLevel(String level) {
        if ("high".equals(level)) {
            return "高风险";
        }
        if ("medium".equals(level)) {
            return "中风险";
        }
        if ("low".equals(level)) {
            return "低风险";
        }
        return "无风险";
    }

    private static String joinNonEmpty(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + "\n" + b;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown.get()) {
            shutdown(false, "检测服务已关闭");
        }
        super.onDestroy();
    }
}

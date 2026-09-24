package com.example.chapter07.get_picture_around;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.chapter07.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 核心功能：
 * 用户点击屏幕任意位置后，以点击坐标为中心，截取周围 50×50 像素范围的图像，
 * 并保存为带时间戳的 PNG 文件到应用内部存储目录。
 *
 * 完整运行链路：
 * 1. onCreate → 检查存储权限 → 初始化控件
 * 2. 用户点击“申请截屏授权”按钮
 * 3. 系统弹出 MediaProjection 授权对话框，用户同意
 * 4. onActivityResult 返回授权结果
 * 5. 如果授权成功 → 创建 ImageReader + 开始屏幕捕获
 * 6. 系统开始持续捕获屏幕图像（存储在 ImageReader 中）
 * 7. 用户在任意界面点击任意位置
 * 8. onGlobalClick → 记录点击坐标 → 调用 cutAroundPixel(cropX, cropY)
 * 9. cutAroundPixel 从 ImageReader 最新图像中裁剪出 100×100 区域
 * 10. 将裁剪图像保存为 /data/data/<package>/files/picture_around_<timestamp>.png
 * 11. Toast 提示保存路径
 *
 * 依赖权限：
 * - WRITE_EXTERNAL_STORAGE  (API < 29)
 * - READ_EXTERNAL_STORAGE   (API < 29)
 * - MediaProjection         (无需 manifest 声明，仅运行时授权)
 */
public class GetPictureAroundActivity extends AppCompatActivity {

    private static final String TAG = "GetPictureAround";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    // 截取范围：以点击坐标为中心，上下左右各 50 像素，即总宽高 100×100 像素
    private static final int HALF_CROP_SIZE = 50;
    private static final int CROP_SIZE = HALF_CROP_SIZE * 2;

    // ========== 控件 ==========
    private TextView tvStatus;
    private TextView tvClickInfo;
    private Button btnRequestProjection;
    private Button btnClearLog;

    // ========== 截图核心组件 ==========
    // 截图管理类，负责管理 MediaProjection 生命周期
    private MediaProjectionManager projectionManager;
    // 捕获授权结果后拿到的 MediaProjection 实例，用于读取屏幕内容
    private MediaProjection mediaProjection;
    // 图像读取器，内部持有独立 Surface，可直接读取当前屏幕像素帧
    private ImageReader imageReader;
    // 标记截图服务是否已经成功启动（授权且初始化完毕）
    private boolean isProjectionStarted = false;
    // 标记当前是否有可用的截图帧（ImageReader 中已有最新帧）
    private boolean hasAvailableFrame = false;

    // ========== 点击坐标（由全局点击服务写入，主线程读取） ==========
    // 注意：实际项目中这里应由 AccessibilityService 或全局悬浮窗写入
    // 此处简化为直接由 Activity 自身 view 点击来模拟
    private volatile int lastClickX = -1;
    private volatile int lastClickY = -1;

    // ========== 日志输出 ==========
    private final StringBuilder logBuilder = new StringBuilder();

    // ========== 主线程 Handler ==========
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========================================================================
    // Activity 生命周期
    // ========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_get_picture_around);

        logStep("=== onCreate ===");

        initViews();
        checkStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        logStep("onResume | projectionStarted=" + isProjectionStarted);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logStep("onDestroy");
        stopProjection();
    }

    // ========================================================================
    // 控件初始化
    // ========================================================================

    private void initViews() {
        tvStatus = findViewById(R.id.tv_pa_status);
        tvClickInfo = findViewById(R.id.tv_pa_click_info);
        btnRequestProjection = findViewById(R.id.btn_pa_request_projection);
        btnClearLog = findViewById(R.id.btn_pa_clear_log);

        // 点击"申请截屏授权"按钮 → 弹出系统授权对话框
        btnRequestProjection.setOnClickListener(v -> {
            logStep("用户点击申请截屏授权按钮");
            startProjectionRequest();
        });

        // 点击"清除日志"按钮 → 清空页面调试日志
        btnClearLog.setOnClickListener(v -> {
            logBuilder.setLength(0);
            renderLog();
            tvClickInfo.setText("点击坐标：无");
        });

        // 整个页面被点击时，模拟"收到全局点击坐标"
        // 实际项目中这里应由 AccessibilityService 或悬浮窗服务写入 lastClickX/Y
        View touchInterceptor = findViewById(R.id.layout_pa_root);
        touchInterceptor.setOnClickListener(v -> {
            // 由于是示例，这里用虚拟坐标；真实场景中 lastClickX/Y 由全局服务写入
            logStep("页面 OnClick（模拟全局点击）");
            onGlobalClick(500, 800);
        });

        renderLog();
    }

    // ========================================================================
    // 存储权限检查（API < 29 时需要）
    // ========================================================================

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                logStep("存储权限未授予，申请权限");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            } else {
                logStep("存储权限已授予");
            }
        } else {
            logStep("Android Q+ 无需外部存储权限");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logStep("存储权限授予成功");
            } else {
                logStep("存储权限被拒绝，无法保存图片");
                Toast.makeText(this, "存储权限被拒绝，将使用应用内部目录", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ========================================================================
    // MediaProjection 授权流程
    // ========================================================================

    private void startProjectionRequest() {
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // 弹出系统授权对话框，用户同意后结果回调到 onActivityResult
        Intent permissionIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, REQUEST_MEDIA_PROJECTION);
        logStep("已发送 MediaProjection 授权请求");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                logStep("用户授权成功 resultCode=" + resultCode);
                startScreenCapture(resultCode, data);
            } else {
                logStep("用户拒绝授权或数据为空 resultCode=" + resultCode);
                tvStatus.setText("截屏授权失败，请重试");
            }
        }
    }

    // ========================================================================
    // 启动截图服务
    // ========================================================================

    private void startScreenCapture(int resultCode, Intent data) {
        try {
            // 创建 MediaProjection 实例（需要 RESULT_OK 和 data）
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                logStep("mediaProjection 创建失败");
                return;
            }
            logStep("mediaProjection 创建成功");

            // 获取屏幕分辨率
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int screenDensity = metrics.densityDpi;
            logStep("屏幕分辨率: " + screenWidth + "x" + screenHeight + " dpi=" + screenDensity);

            // 创建 ImageReader：
            // - 宽高与屏幕分辨率一致，保证截取坐标与屏幕像素坐标一一对应
            // - PixelFormat.RGBA_8888 保证每个像素 4 字节，方便后续裁剪
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            logStep("ImageReader 创建成功 " + screenWidth + "x" + screenHeight);

            // 设置截图完成回调：每次 SurfaceFlinger 送来新帧时触发
            imageReader.setOnImageAvailableListener(reader -> {
                // 从 ImageReader 队列中取出最新帧
                Image image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                // 标记当前有可用帧（供裁剪时使用）
                hasAvailableFrame = true;
                // 处理完后立即关闭 Image，释放内部帧缓冲，避免队列堆积
                image.close();
            }, mainHandler);

            // 创建截图虚拟显示器（DISPLAY_FLAG_PRIVATE：仅当前应用可见）
            Display display = getWindowManager().getDefaultDisplay();
            int displayId = display.getDisplayId();

            // 通过 MediaProjection 启动截图，并将画面输出到 ImageReader 的 Surface
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    logStep("MediaProjection 已停止（用户可能撤销了授权）");
                    isProjectionStarted = false;
                    mainHandler.post(() -> tvStatus.setText("截屏已停止，请重新授权"));
                }
            }, mainHandler);

            // 关键调用：让系统开始持续捕获屏幕内容并推送到 ImageReader Surface
            mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            isProjectionStarted = true;
            tvStatus.setText("截屏服务运行中，请点击屏幕任意位置");
            logStep("截屏服务启动成功，已开始持续捕获屏幕");
            renderLog();

        } catch (Exception e) {
            logStep("启动截图服务异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            Log.e(TAG, "startScreenCapture error", e);
        }
    }

    // ========================================================================
    // 停止截图服务
    // ========================================================================

    private void stopProjection() {
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
            logStep("MediaProjection 已停止");
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
            logStep("ImageReader 已关闭");
        }
        isProjectionStarted = false;
        hasAvailableFrame = false;
    }

    // ========================================================================
    // 收到全局点击坐标后的处理入口
    // ========================================================================

    /**
     * 当收到全局点击坐标时调用此方法。
     * 参数 x, y 为点击位置在屏幕像素坐标系中的坐标（原点在屏幕左上角）。
     *
     * @param x 点击位置的屏幕 X 坐标（像素）
     * @param y 点击位置的屏幕 Y 坐标（像素）
     */
    public void onGlobalClick(int x, int y) {
        logStep("收到全局点击坐标: (" + x + ", " + y + ")");
        lastClickX = x;
        lastClickY = y;
        tvClickInfo.setText("点击坐标：(" + x + ", " + y + ")");

        // 如果截图服务尚未启动，提示用户先授权
        if (!isProjectionStarted || imageReader == null) {
            logStep("截屏服务未启动，无法截图");
            Toast.makeText(this, "请先点击\"申请截屏授权\"按钮", Toast.LENGTH_SHORT).show();
            return;
        }

        // 截图可能尚未就绪，等待一帧后执行裁剪
        mainHandler.postDelayed(() -> cutAroundPixel(x, y), 100);
    }

    // ========================================================================
    // 核心裁剪逻辑：从 ImageReader 中裁剪出点击点附近 100×100 像素区域
    // ========================================================================

    private void cutAroundPixel(int centerX, int centerY) {
        // 从 ImageReader 取出最新帧
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            logStep("cutAroundPixel 失败：ImageReader 中暂无可用帧");
            Toast.makeText(this, "截图未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int width = image.getWidth();
            int height = image.getHeight();
            logStep("当前帧尺寸: " + width + "x" + height);

            int left = centerX - HALF_CROP_SIZE;
            int top = centerY - HALF_CROP_SIZE;
            int right = centerX + HALF_CROP_SIZE;
            int bottom = centerY + HALF_CROP_SIZE;

            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(width, right);
            bottom = Math.min(height, bottom);

            int cropWidth = right - left;
            int cropHeight = bottom - top;
            logStep("裁剪区域: left=" + left + " top=" + top + " w=" + cropWidth + " h=" + cropHeight);

            if (cropWidth <= 0 || cropHeight <= 0) {
                logStep("裁剪失败：裁剪区域无效");
                return;
            }

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            fullBitmap.copyPixelsFromBuffer(buffer);

            Bitmap croppedBitmap = Bitmap.createBitmap(fullBitmap, left, top, cropWidth, cropHeight);
            logStep("裁剪完成: " + cropWidth + "x" + cropHeight);

            String savedPath = saveBitmapToAppFiles(croppedBitmap);
            if (savedPath != null) {
                Toast.makeText(this, "图片已保存: " + savedPath, Toast.LENGTH_LONG).show();
                logStep("图片保存成功: " + savedPath);
            } else {
                Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show();
                logStep("图片保存失败");
            }

            croppedBitmap.recycle();
            fullBitmap.recycle();

        } catch (Exception e) {
            logStep("裁剪异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            Log.e(TAG, "cutAroundPixel error", e);
        } finally {
            // 重要：每次从 ImageReader 取出的 Image 使用完毕后必须关闭
            // 否则内部帧缓冲区会持续堆积，最终导致截图停止
            image.close();
        }
    }

    // ========================================================================
    // 保存 Bitmap 到应用内部存储目录
    // ========================================================================

    /**
     * 将 Bitmap 保存到应用内部 files 目录（不需要存储权限，API 21+ 通用）。
     * 文件名格式：picture_around_<yyyyMMdd_HHmmss_SSS>.png
     */
    private String saveBitmapToAppFiles(Bitmap bitmap) {
        // 生成带时间戳的文件名
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String fileName = "picture_around_" + timestamp + ".png";

        // 使用应用内部 files 目录，不需要任何存储权限
        File outputDir = getFilesDir();
        File outputFile = new File(outputDir, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outputFile);
            // PNG 格式：无损压缩，适合截图表格和文字
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            return outputFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToAppFiles error", e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ========================================================================
    // 调试日志工具
    // ========================================================================

    private void logStep(String message) {
        // 同时输出到 Logcat（方便在 Android Studio 中过滤查看）
        Log.d(TAG, message);
        // 追加到页面日志缓冲区，每条日志之间用换行分隔
        logBuilder.append(message).append("\n");
        // 立即刷新到页面 TextView
        renderLog();
    }

    private void renderLog() {
        if (tvClickInfo != null) {
            // 如果日志过长，截取最后 3000 字符避免 TextView 过长导致卡顿
            String log = logBuilder.toString();
            if (log.length() > 3000) {
                log = log.substring(log.length() - 3000);
            }
            tvClickInfo.setText(log);
        }
    }
}

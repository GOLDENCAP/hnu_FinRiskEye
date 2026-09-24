package com.example.chapter07.rtsp_player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.chapter07.R;

/**
 * RTSP Player Activity - 播放RTSP视频流的Activity
 *
 * 功能：
 * 1. 通过FFmpeg接收RTSP视频流
 * 2. 使用OpenGL渲染到SurfaceView
 * 3. 支持暂停/继续/停止播放
 * 4. 根据推流视频尺寸自动调整容器大小（限制最大高度）
 */
public class RtspPlayerActivity extends AppCompatActivity {

    private static final String TAG = "RtspPlayerActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    private FrameLayout videoContainer;
    private SurfaceView surfaceView;
    private EditText rtspUrlEditText;
    private TextView statusText;
    private Button btnPlay;
    private Button btnStop;
    private Button btnBack;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isPlaying = false;

    private RtspPlayerNative nativePlayer;

    // 视频尺寸
    private int videoWidth = 0;
    private int videoHeight = 0;

    // 默认容器高度（16:9 比例时的近似高度）
    private int defaultContainerHeight = 0;

    // 最大容器高度（屏幕高度的40%，确保按钮可见）
    private int maxContainerHeight = 0;

    // 容器宽度
    private int containerWidth = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rtsp_player);

        initViews();
        setupListeners();
        checkPermissions();

        nativePlayer = new RtspPlayerNative();

        // 设置视频尺寸回调
        nativePlayer.setOnVideoSizeListener((width, height) -> {
            videoWidth = width;
            videoHeight = height;
            updateStatus("获取到视频尺寸: " + width + "x" + height);
            adjustContainerSize(width, height);
        });
    }

    private void initViews() {
        videoContainer = findViewById(R.id.video_container);
        surfaceView = findViewById(R.id.rtsp_surface_view);
        rtspUrlEditText = findViewById(R.id.rtsp_url_edit_text);
        statusText = findViewById(R.id.rtsp_status_text);
        btnPlay = findViewById(R.id.rtsp_btn_play);
        btnStop = findViewById(R.id.rtsp_btn_stop);
        btnBack = findViewById(R.id.rtsp_btn_back);

        rtspUrlEditText.setText("rtsp://180.76.247.74:8554/stream1");

        btnStop.setEnabled(false);
        statusText.setText("请输入RTSP地址后点击播放");

        // 计算默认和最大容器高度
        calculateContainerHeights();
    }

    private void calculateContainerHeights() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        // 最大高度为屏幕高度的40%（确保按钮始终可见）
        maxContainerHeight = (int) (metrics.heightPixels * 0.4);

        // 默认容器高度（假设16:9比例）
        defaultContainerHeight = (int) (metrics.widthPixels * 9.0 / 16.0);

        // 限制默认高度不超过最大值
        if (defaultContainerHeight > maxContainerHeight) {
            defaultContainerHeight = maxContainerHeight;
        }
    }

    private void setupListeners() {
        btnPlay.setOnClickListener(v -> startPlayback());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnBack.setOnClickListener(v -> finish());

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                updateStatus("Surface已创建");
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                updateStatus("Surface尺寸: " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                updateStatus("Surface已销毁，停止播放");
                stopPlayback();
            }
        });
    }

    private void checkPermissions() {
        String[] permissions = {
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        };

        boolean needRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                updateStatus("权限已获取");
            } else {
                updateStatus("权限不足，可能无法播放网络视频");
                Toast.makeText(this, "网络权限不足", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 根据视频尺寸调整容器大小
     * @param videoW 视频宽度
     * @param videoH 视频高度
     */
    private void adjustContainerSize(int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0) {
            return;
        }

        mainHandler.post(() -> {
            // 获取容器宽度
            if (containerWidth <= 0) {
                containerWidth = videoContainer.getWidth();
            }

            // 如果容器宽度为0，等待布局完成
            if (containerWidth <= 0) {
                videoContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            videoContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            containerWidth = videoContainer.getWidth();
                            doAdjustContainerSize(videoW, videoH);
                        }
                    }
                );
                return;
            }

            doAdjustContainerSize(videoW, videoH);
        });
    }

    /**
     * 实际执行容器尺寸调整
     */
    private void doAdjustContainerSize(int videoW, int videoH) {
        if (containerWidth <= 0) {
            containerWidth = videoContainer.getWidth();
        }

        if (containerWidth <= 0) {
            return;
        }

        // 计算视频宽高比
        float videoRatio = (float) videoW / videoH;

        // 根据视频宽高比计算容器高度
        int calculatedHeight = (int) (containerWidth / videoRatio);

        // 限制最大高度，确保按钮始终可见
        int finalHeight = Math.min(calculatedHeight, maxContainerHeight);

        // 限制最小高度
        if (finalHeight < 100) {
            finalHeight = 100;
        }

        // 调整容器大小
        LinearLayout.LayoutParams containerParams = (LinearLayout.LayoutParams) videoContainer.getLayoutParams();
        containerParams.height = finalHeight;
        videoContainer.setLayoutParams(containerParams);

        // SurfaceView 填满容器
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        surfaceView.setLayoutParams(surfaceParams);

        updateStatus("容器调整: " + videoW + "x" + videoH + " -> 高度限制为 " + finalHeight + "px");

        // 请求布局更新
        videoContainer.requestLayout();
    }

    private void startPlayback() {
        if (isPlaying) {
            return;
        }

        String rtspUrl = rtspUrlEditText.getText().toString().trim();
        if (rtspUrl.isEmpty()) {
            updateStatus("请输入RTSP地址");
            Toast.makeText(this, "请输入RTSP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!surfaceView.getHolder().getSurface().isValid()) {
            updateStatus("Surface未就绪，等待中...");
            waitForSurface();
        }

        isPlaying = true;
        updateStatus("正在连接: " + rtspUrl);

        btnPlay.setEnabled(false);
        btnStop.setEnabled(true);

        // 获取初始容器宽度
        containerWidth = videoContainer.getWidth();

        new Thread(() -> {
            try {
                int result = nativePlayer.playRtspStream(
                    rtspUrl,
                    surfaceView.getHolder().getSurface()
                );

                mainHandler.post(() -> {
                    if (result == 0) {
                        updateStatus("播放结束");
                    } else {
                        updateStatus("播放错误: " + result);
                    }
                    isPlaying = false;
                    btnPlay.setEnabled(true);
                    btnStop.setEnabled(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    updateStatus("播放异常: " + e.getMessage());
                    isPlaying = false;
                    btnPlay.setEnabled(true);
                    btnStop.setEnabled(false);
                });
            }
        }).start();
    }

    private void stopPlayback() {
        if (!isPlaying) {
            return;
        }

        isPlaying = false;
        nativePlayer.stopPlayback();
        updateStatus("已停止播放");

        btnPlay.setEnabled(true);
        btnStop.setEnabled(false);

        // 重置容器大小
        mainHandler.post(() -> {
            // 重置视频尺寸
            videoWidth = 0;
            videoHeight = 0;
            containerWidth = 0;

            // 重置容器高度为默认高度
            LinearLayout.LayoutParams containerParams = (LinearLayout.LayoutParams) videoContainer.getLayoutParams();
            containerParams.height = defaultContainerHeight > 0 ? defaultContainerHeight : LinearLayout.LayoutParams.WRAP_CONTENT;
            videoContainer.setLayoutParams(containerParams);

            // SurfaceView 填满容器
            FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            surfaceView.setLayoutParams(surfaceParams);

            videoContainer.requestLayout();
        });
    }

    private void waitForSurface() {
        for (int i = 0; i < 50 && !surfaceView.getHolder().getSurface().isValid(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void updateStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}

package com.example.chapter07;  // 声明类所在的包名

// 自定义渲染修改部分的标识：// mymymy

// ========== Android 框架相关 ==========
import android.content.Context;                              // Android 上下文
import android.os.Bundle;                                   // Bundle
import android.os.Environment;                              // Environment（外部存储）
import android.util.Log;                                    // 日志
import android.view.View;                                   // View 基类
import android.widget.Button;                               // Button 控件
import android.widget.LinearLayout;                         // LinearLayout 容器
import android.widget.SeekBar;                              // SeekBar 拖动条
import android.widget.TextView;                             // TextView 控件
import android.widget.Toast;                                // Toast 提示

// ========== BuildConfig（gradle 注入的 OpenAI API Key 字段） ==========
// mymymy: OpenAI Key 通过 app/build.gradle.kts 的 buildConfigField 注入，
// 不再硬编码占位符。运行时由 resolveApiKey() 读取并校验。

// ========== AndroidX ==========
import androidx.activity.EdgeToEdge;                        // 沉浸式（边到边）
import androidx.appcompat.app.AppCompatActivity;            // AppCompatActivity 基类
import androidx.core.graphics.Insets;                       // Insets 边距
import androidx.core.view.ViewCompat;                        // ViewCompat 工具
import androidx.core.view.WindowInsetsCompat;                // 窗口 Insets

// ========== WebRTC ==========
import org.json.JSONException;                              // JSON 异常
import org.json.JSONObject;                                 // JSON 对象
import org.webrtc.AudioSource;                              // 音频源
import org.webrtc.AudioTrack;                               // 音频轨道
import org.webrtc.Camera1Enumerator;                        // Camera1 枚举器
import org.webrtc.Camera2Enumerator;                        // Camera2 枚举器
import org.webrtc.CameraEnumerator;                         // 通用枚举器接口
import org.webrtc.DataChannel;                              // 数据通道
import org.webrtc.DefaultVideoDecoderFactory;               // 默认视频解码器工厂
import org.webrtc.DefaultVideoEncoderFactory;               // 默认视频编码器工厂
import org.webrtc.EglBase;                                  // EGL 封装
import org.webrtc.IceCandidate;                             // ICE 候选
import org.webrtc.Logging;                                  // WebRTC 日志
import org.webrtc.MediaConstraints;                         // 媒体约束
import org.webrtc.MediaStream;                              // 媒体流（旧版 API）
import org.webrtc.MediaStreamTrack;                         // 媒体流轨道
import org.webrtc.PeerConnection;                           // PeerConnection
import org.webrtc.PeerConnectionFactory;                    // PeerConnection 工厂
import org.webrtc.RendererCommon;                           // 渲染通用接口
import org.webrtc.RtpReceiver;                              // RTP 接收器
import org.webrtc.SdpObserver;                              // SDP 观察者
import org.webrtc.SessionDescription;                       // SDP 会话描述
import org.webrtc.SurfaceTextureHelper;                     // SurfaceTexture 辅助
import org.webrtc.SurfaceViewRenderer;                      // SurfaceView 渲染器
import org.webrtc.VideoCapturer;                            // 视频采集器
import org.webrtc.VideoDecoderFactory;                      // 解码器工厂接口
import org.webrtc.VideoEncoderFactory;                      // 编码器工厂接口
import org.webrtc.VideoSource;                              // 视频源
import org.webrtc.VideoTrack;                               // 视频轨道

// ========== Java 标准库 ==========
import java.util.Collections;                               // Collections 工具
import java.util.LinkedList;                                // LinkedList 链表
import java.util.List;                                      // List 接口

/**
 * peer：音视频通话主 Activity。
 *
 * 功能：
 *   - WebRTC 端到端音视频通话（基于信令服务器交换 SDP/ICE）
 *   - 本地暖色调特效 + 远端 Shader 渲染
 *   - 本地视频 / 远端视频 / 本端音频 / 远端音频 四路录制
 *   - 通过反射获取远端 PCM 用于录制
 */
public class peer extends AppCompatActivity {

    // 静态代码块：加载 WebRTC native 库
    static {
        System.loadLibrary("jingle_peerconnection_so");  // 加载 libjingle_peerconnection_so.so
    }

    // ========== 视频采集配置常量 ==========
    private static final int VIDEO_RESOLUTION_WIDTH = 1280;   // 采集宽度 1280
    private static final int VIDEO_RESOLUTION_HEIGHT = 720;   // 采集高度 720
    private static final int VIDEO_FPS = 30;                  // 帧率 30

    // 通话状态机字符串
    private String mState = "init";

    // 日志显示 TextView
    private TextView mLogcatView;

    // mymymy Shader UI 控制
    private Button mShaderToggleBtn;                          // Shader 开关按钮
    private Button mRecordToggleBtn;                          // 本地录制按钮
    private Button mRecordRemoteToggleBtn;                    // 远端录制按钮
    private Button mRecordRemoteAudioToggleBtn;               // 远端音频录制按钮
    private Button mLocalWarmEffectToggleBtn;                 // 本地暖色调按钮
    private LinearLayout mControlPanel;                       // Shader 控制面板
    private SeekBar mSaturationSeekBar;                       // 饱和度 SeekBar
    private SeekBar mWarmthSeekBar;                          // 暖色调 SeekBar
    private TextView mSaturationValue;                        // 饱和度值显示
    private TextView mWarmthValue;                            // 暖色调值显示

    // mymymy 本地暖色调特效 UI 控制
    private LinearLayout mLocalWarmControlPanel;              // 本地暖色调面板
    private SeekBar mLocalWarmthSeekBar;                      // 本地暖色调 SeekBar
    private SeekBar mLocalSaturationSeekBar;                  // 本地饱和度 SeekBar
    private TextView mLocalWarmthValue;                       // 本地暖色调值
    private TextView mLocalSaturationValue;                   // 本地饱和度值

    // mymymy 视频录制相关
    private VideoRecorder mVideoRecorder;                     // 本地视频录制器
    private boolean mIsRecording = false;                     // 是否正在录制本地视频

    // mymymy 远端视频录制相关
    private RemoteVideoRecorder mRemoteVideoRecorder;         // 远端视频录制器
    private boolean mIsRemoteRecording = false;               // 是否正在录制远端视频

    // mymymy 远端音频录制相关
    private RemoteAudioRecorder mRemoteAudioRecorder;         // 远端音频录制器
    private boolean mIsRemoteAudioRecording = false;          // 是否正在录制远端音频
    private AudioTrack mRemoteAudioTrack;                     // 远端音频轨道引用

    // mymymy 通过反射获取远端 PCM 的工具（替代麦克风录音）
    private WebRtcAudioTrackReflector mRemoteAudioReflector;  // 远端音频反射器

    // mymymy 本端音频录制相关（独立类，通过 JavaAudioDeviceModule 公开回调 API 拿 PCM）
    private WebRtcAudioRecordSamplesBridge mLocalAudioBridge; // 本端音频桥接器
    private LocalAudioRecorder mLocalAudioRecorder;           // 本端音频录制器
    private boolean mIsLocalAudioRecording = false;            // 是否正在录制本端音频
    private Button mRecordLocalAudioToggleBtn;                // 本端音频录制按钮

    // mymymy 金融诈骗检测相关
    private FraudDetectionAnalyzer mFraudDetectionAnalyzer;    // 诈骗检测分析器
    private Button mFraudDetectionBtn;                        // 诈骗检测按钮
    private boolean mIsFraudDetectionRunning = false;         // 是否正在检测

    // mymymy 金融诈骗视频检测相关
    private FraudVideoAnalyzer mFraudVideoAnalyzer;           // 视频诈骗分析器
    private Button mFraudVideoDetectionBtn;                   // 视频诈骗检测按钮
    private boolean mIsFraudVideoDetectionRunning = false;     // 是否正在视频检测

    // mymymy 音频转文字 + 诈骗检测相关
    private FraudAudioTranscriptionAnalyzer mFraudAudioAnalyzer;  // 音频转文字 + 诈骗分析器
    private Button mFraudAudioTranscriptionBtn;                  // 音频转文字诈骗检测按钮
    private boolean mIsFraudAudioRunning = false;                // 是否正在录音分析

    // mymymy 本地视频特效相关
    private YuvFrameProcessorWithEffect mLocalEffectProcessor; // 本地视频特效处理器
    private boolean mLocalWarmEffectEnabled = false;          // 本地暖色调是否启用

    private static final String TAG = "CallActivity";         // 日志 TAG

    // Track ID 常量
    public static final String VIDEO_TRACK_ID = "1";          // 视频轨道 ID
    public static final String AUDIO_TRACK_ID = "2";          // 音频轨道 ID

    // ========== WebRTC 核心对象 ==========
    private PeerConnection mPeerConnection;                   // 当前 PeerConnection
    private PeerConnectionFactory mPeerConnectionFactory;    // PeerConnection 工厂

    private EglBase mRootEglBase;                             // 共享 EGL Base

    private SurfaceTextureHelper mSurfaceTextureHelper;        // 视频采集的 SurfaceTexture 辅助

    // SurfaceView 渲染器
    private SurfaceViewRenderer mLocalSurfaceView;            // 本地预览视图
    private SurfaceViewRenderer mRemoteSurfaceView;           // 远端默认渲染视图
    // mymymy OpenGL Shader 渲染
    private YuvGLSurfaceView mRemoteGLSurfaceView;            // 远端自定义 GL 渲染视图
    private boolean mShaderEnabled = false;                   // Shader 模式开关
    // mymymy 维护远端视频 track 引用，用于热切换渲染目标
    private VideoTrack mRemoteVideoTrack;                     // 远端视频轨道

    private VideoTrack mVideoTrack;                           // 本地视频轨道
    private AudioTrack mAudioTrack;                           // 本地音频轨道

    private VideoCapturer mVideoCapturer;                     // 摄像头采集器

    /**
     * Activity 创建生命周期回调
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);                  // 父类初始化
        EdgeToEdge.enable(this);                             // 启用沉浸式
        setContentView(R.layout.activity_peer);              // 加载布局
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            // 适配系统栏边距
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mLogcatView = findViewById(R.id.LogcatView);          // 日志显示控件

        mRootEglBase = EglBase.create();                     // 创建共享 EGL 上下文

        mLocalSurfaceView = findViewById(R.id.LocalSurfaceView);   // 本地预览
        mRemoteSurfaceView = findViewById(R.id.RemoteSurfaceView); // 远端默认
        // mymymy 初始化自定义 OpenGL 渲染器
        mRemoteGLSurfaceView = findViewById(R.id.RemoteGLSurfaceView); // 远端 GL

        // 初始化本地 SurfaceView（共享 EGL Context）
        mLocalSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
        mLocalSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); // 铺满（裁剪）
        mLocalSurfaceView.setMirror(true);                    // 前置摄像头镜像
        mLocalSurfaceView.setEnableHardwareScaler(false);     // 关闭硬件缩放

        // 初始化远端 SurfaceView
        mRemoteSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
        mRemoteSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        mRemoteSurfaceView.setMirror(true);
        mRemoteSurfaceView.setEnableHardwareScaler(true);     // 远端开启硬件缩放

        // 本地视频需要设置 zOrderMediaOverlay 使其显示在远程视频之上
        mLocalSurfaceView.setZOrderMediaOverlay(true);

        // mymymy 初始化 Shader 渲染控件
        initShaderControls();

        // mymymy 初始化视频录制控件
        initRecordControls();

        // 创建 factory，pc 是从 factory 里获得的
        mPeerConnectionFactory = createPeerConnectionFactory(this);

        // NOTE: this _must_ happen while PeerConnectionFactory is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);  // 启用 WebRTC verbose 日志

        mVideoCapturer = createVideoCapturer();              // 创建摄像头采集器

        // ========== 视频采集与渲染通道建立 ==========
        mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mRootEglBase.getEglBaseContext());

        VideoSource videoSource = mPeerConnectionFactory.createVideoSource(false); // screencam=false
        mVideoCapturer.initialize(mSurfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        mVideoTrack = mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource); // 创建视频轨道
        mVideoTrack.setEnabled(true);                        // 启用

        // mymymy 本地预览使用特效处理器（支持灰度和暖色调效果）
        mLocalEffectProcessor = new YuvFrameProcessorWithEffect(mLocalSurfaceView);
        mLocalEffectProcessor.setGrayscaleEnabled(true);     // 默认启用灰度效果
        mVideoTrack.addSink(mLocalEffectProcessor);          // 把特效处理器作为 sink

        // 创建音频源和轨道
        AudioSource audioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        mAudioTrack = mPeerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        mAudioTrack.setEnabled(true);

        // 设置信令事件监听器
        SignalClient.getInstance().setSignalEventListener(mOnSignalEventListener);

        // 取出 Intent 中的服务器地址和房间名
        String serverAddr = getIntent().getStringExtra("ServerAddr");
        String roomName = getIntent().getStringExtra("RoomName");

        // 调试日志
        Log.i(TAG, "========== DEBUG INFO ==========");
        Log.i(TAG, "Raw ServerAddr: " + serverAddr);
        Log.i(TAG, "RoomName: " + roomName);

        // 处理服务器地址（为空时用默认值）
        if (serverAddr == null || serverAddr.isEmpty()) {
            serverAddr = "https://192.168.31.234";           // 默认 HTTPS 地址
            Log.i(TAG, "Using default server: " + serverAddr);
        }
        // 移除末尾斜杠
        if (serverAddr.endsWith("/")) {
            serverAddr = serverAddr.substring(0, serverAddr.length() - 1);
        }

        Log.i(TAG, "Final ServerAddr: " + serverAddr);
        Log.i(TAG, "================================");

        // 连接到信令服务器并加入房间
        SignalClient.getInstance().joinRoom(serverAddr, roomName);
    }

    // mymymy 初始化 Shader 渲染控件（远端）
    private void initShaderControls() {
        mShaderToggleBtn = findViewById(R.id.ShaderToggleBtn);
        mControlPanel = findViewById(R.id.ControlPanel);
        mSaturationSeekBar = findViewById(R.id.SaturationSeekBar);
        mWarmthSeekBar = findViewById(R.id.WarmthSeekBar);
        mSaturationValue = findViewById(R.id.SaturationValue);
        mWarmthValue = findViewById(R.id.WarmthValue);

        // Shader 开关按钮：切换远端渲染模式
        mShaderToggleBtn.setOnClickListener(v -> toggleShaderRendering());

        // 饱和度拖动条：实时改变远端 Shader 饱和度
        mSaturationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sat = progress / 100.0f;
                mSaturationValue.setText(String.format("%.2f", sat));
                if (mRemoteGLSurfaceView != null) {
                    mRemoteGLSurfaceView.setSaturation(sat);  // 改变 GL 视图饱和度
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 暖色调拖动条：实时改变远端 Shader 暖色调
        mWarmthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float warmth = progress / 100.0f;
                mWarmthValue.setText(String.format("%.2f", warmth));
                if (mRemoteGLSurfaceView != null) {
                    mRemoteGLSurfaceView.setWarmth(warmth);    // 改变 GL 视图暖色调
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // mymymy 初始化录制控件
    private void initRecordControls() {
        mRecordToggleBtn = findViewById(R.id.RecordToggleBtn);
        mRecordRemoteToggleBtn = findViewById(R.id.RecordRemoteToggleBtn);
        mRecordRemoteAudioToggleBtn = findViewById(R.id.RecordRemoteAudioToggleBtn);
        mRecordLocalAudioToggleBtn = findViewById(R.id.RecordLocalAudioToggleBtn);

        // 本地视频录制开关
        mRecordToggleBtn.setOnClickListener(v -> {
            if (!mIsRecording) {
                startRecording();                              // 开始录制
            } else {
                stopRecording();                               // 停止录制
            }
        });

        // mymymy 远端视频录制开关
        mRecordRemoteToggleBtn.setOnClickListener(v -> {
            if (!mIsRemoteRecording) {
                startRemoteRecording();
            } else {
                stopRemoteRecording();
            }
        });

        // mymymy 远端音频录制开关
        mRecordRemoteAudioToggleBtn.setOnClickListener(v -> {
            if (!mIsRemoteAudioRecording) {
                startRemoteAudioRecording();
            } else {
                stopRemoteAudioRecording();
            }
        });

        // mymymy 本端音频录制开关（独立类 LocalAudioRecorder，通过 SamplesReadyCallback 拿 PCM）
        mRecordLocalAudioToggleBtn.setOnClickListener(v -> {
            if (!mIsLocalAudioRecording) {
                startLocalAudioRecording();
            } else {
                stopLocalAudioRecording();
            }
        });

        // mymymy 初始化本地暖色调特效按钮
        mLocalWarmEffectToggleBtn = findViewById(R.id.LocalWarmEffectToggleBtn);
        mLocalWarmEffectToggleBtn.setOnClickListener(v -> toggleLocalWarmEffect());

        // mymymy 初始化本地暖色调特效控制面板
        initLocalWarmEffectControls();

        // mymymy 初始化金融诈骗检测按钮
        initFraudDetectionControls();

        // mymymy 初始化金融诈骗视频检测按钮
        initFraudVideoControls();

        // mymymy 初始化音频转文字 + 诈骗检测按钮
        initFraudAudioTranscriptionControls();
    }

    // mymymy 初始化金融诈骗检测功能
    private void initFraudDetectionControls() {
        mFraudDetectionBtn = findViewById(R.id.FraudDetectionBtn);

        // mymymy: API Key 走 BuildConfig（gradle.properties → buildConfigField），不再硬编码占位符
        String apiKey = resolveOpenAiApiKey();
        // 创建诈骗检测分析器
        mFraudDetectionAnalyzer = new FraudDetectionAnalyzer(this, apiKey);

        // 设置回调监听器
        mFraudDetectionAnalyzer.setFraudDetectionListener(new FraudDetectionAnalyzer.FraudDetectionListener() {
            @Override
            public void onFraudRiskDetected(FraudDetectionAnalyzer.FraudRiskResult result) {
                // 处理诈骗风险检测结果
                String riskEmoji = "";
                switch (result.riskLevel) {
                    case "high":
                        riskEmoji = "⚠️ 高风险";
                        break;
                    case "medium":
                        riskEmoji = "⚡ 中风险";
                        break;
                    case "low":
                        riskEmoji = "📍 低风险";
                        break;
                    default:
                        riskEmoji = "✅ 无风险";
                        break;
                }

                String features = "";
                if (result.detectedFeatures != null && result.detectedFeatures.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("检测到以下特征：\n");
                    for (String feature : result.detectedFeatures) {
                        sb.append("- ").append(feature).append("\n");
                    }
                    features = sb.toString();
                }

                String message = riskEmoji + " 风险等级: " + result.riskLevel +
                        "\n风险评分: " + result.riskScore +
                        "\n" + features +
                        "建议: " + result.advice;

                logcatOnUI("【诈骗检测】" + result.summary);
                Toast.makeText(peer.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onTranscription(String text) {
                // 处理转录文本
                Log.i(TAG, "【实时转录】" + text);
            }

            @Override
            public void onError(String error) {
                // 处理错误
                logcatOnUI("【诈骗检测错误】" + error);
                Toast.makeText(peer.this, error, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onStatusUpdate(String status) {
                // 处理状态更新
                logcatOnUI("【诈骗检测】" + status);
            }
        });

        // 诈骗检测按钮点击事件
        mFraudDetectionBtn.setOnClickListener(v -> {
            if (!mIsFraudDetectionRunning) {
                // 开始诈骗检测
                if (mRemoteAudioReflector == null) {
                    // 如果远端音频反射器未初始化，提示用户
                    Toast.makeText(this, "请先建立通话连接", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 设置音频反射器
                mFraudDetectionAnalyzer.setAudioReflector(mRemoteAudioReflector);

                // 开始检测
                mFraudDetectionAnalyzer.start();
                // mymymy: 只有底层真正进入录制状态才翻 UI，避免反射器被中途释放后 UI 与真实状态脱节
                if (!mFraudDetectionAnalyzer.isRunning()) {
                    Toast.makeText(this, "诈骗检测启动失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                mIsFraudDetectionRunning = true;
                mFraudDetectionBtn.setText("停止诈骗检测");
                logcatOnUI("开始金融诈骗实时检测...");
                Toast.makeText(this, "开始金融诈骗检测，请保持通话", Toast.LENGTH_SHORT).show();
            } else {
                // 停止诈骗检测
                mFraudDetectionAnalyzer.stop();
                mIsFraudDetectionRunning = false;
                mFraudDetectionBtn.setText("金融诈骗检测");
                logcatOnUI("停止金融诈骗检测");
                Toast.makeText(this, "诈骗检测已停止，正在分析剩余音频...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // mymymy 初始化金融诈骗视频检测功能
    private void initFraudVideoControls() {
        mFraudVideoDetectionBtn = findViewById(R.id.FraudVideoDetectionBtn);

        // mymymy: API Key 走 BuildConfig
        String apiKey = resolveOpenAiApiKey();
        // 创建视频诈骗分析器
        mFraudVideoAnalyzer = new FraudVideoAnalyzer(this, apiKey);
        mFraudVideoAnalyzer.setSampleIntervalMs(5_000L); // 每 5 秒采样一帧

        // 设置回调
        mFraudVideoAnalyzer.setFraudVideoListener(new FraudVideoAnalyzer.FraudVideoListener() {
            @Override
            public void onFraudVideoDetected(FraudVideoAnalyzer.FraudVideoResult result) {
                String riskEmoji;
                switch (result.riskLevel == null ? "" : result.riskLevel) {
                    case "high":
                        riskEmoji = "⚠️ 高风险";
                        break;
                    case "medium":
                        riskEmoji = "⚡ 中风险";
                        break;
                    case "low":
                        riskEmoji = "📍 低风险";
                        break;
                    case "none":
                        riskEmoji = "✅ 无风险";
                        break;
                    default:
                        riskEmoji = "❓ 未知";
                        break;
                }
                StringBuilder featSb = new StringBuilder();
                if (result.detectedFeatures != null && result.detectedFeatures.length > 0) {
                    featSb.append("检测到特征：\n");
                    for (String f : result.detectedFeatures) featSb.append("- ").append(f).append("\n");
                }
                String message = riskEmoji + "  风险评分: " + result.riskScore + "\n"
                        + featSb + "建议: " + result.advice;
                Log.i(TAG, "【视频诈骗检测】" + result.summary);
                Toast.makeText(peer.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "【视频诈骗检测错误】" + error);
                Toast.makeText(peer.this, "视频检测: " + error, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onStatusUpdate(String status) {
                Log.i(TAG, "【视频诈骗检测】" + status);
            }
        });

        // 按钮点击切换
        mFraudVideoDetectionBtn.setOnClickListener(v -> {
            if (!mIsFraudVideoDetectionRunning) {
                if (mRemoteVideoTrack == null) {
                    Toast.makeText(this, "请先建立通话，等待远端视频...", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 启动 + 挂载到远端 VideoTrack
                mFraudVideoAnalyzer.setup();
                try {
                    mFraudVideoAnalyzer.attachToVideoTrack(mRemoteVideoTrack);
                } catch (Throwable t) {
                    // mymymy: 挂载失败时回滚 setup + UI 状态，避免"按钮显示已开启但底层未挂载"
                    try { mFraudVideoAnalyzer.teardown(); } catch (Throwable ignore) { }
                    Toast.makeText(this, "视频检测挂载失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    logcatOnUI("视频检测挂载失败: " + t.getMessage());
                    return;
                }
                // mymymy: 只有底层真正进入采样才翻 UI
                if (!mFraudVideoAnalyzer.isRunning()) {
                    try { mFraudVideoAnalyzer.detachFromVideoTrack(mRemoteVideoTrack); } catch (Throwable ignore) { }
                    Toast.makeText(this, "视频检测启动失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                mIsFraudVideoDetectionRunning = true;
                mFraudVideoDetectionBtn.setText("停止视频检测");
                Toast.makeText(this, "视频诈骗检测已开启，将每 5 秒抓取一帧分析", Toast.LENGTH_SHORT).show();
                logcatOnUI("开始金融诈骗视频实时检测");
            } else {
                mFraudVideoAnalyzer.teardown();
                mFraudVideoAnalyzer.detachFromVideoTrack(mRemoteVideoTrack);
                mIsFraudVideoDetectionRunning = false;
                mFraudVideoDetectionBtn.setText("视频诈骗检测");
                Toast.makeText(this, "视频诈骗检测已停止", Toast.LENGTH_SHORT).show();
                logcatOnUI("停止金融诈骗视频实时检测");
            }
        });
    }

    // mymymy 初始化音频转文字 + 诈骗检测功能
    private void initFraudAudioTranscriptionControls() {
        mFraudAudioTranscriptionBtn = findViewById(R.id.FraudAudioTranscriptionBtn);

        // mymymy: API Key 走 BuildConfig
        String apiKey = resolveOpenAiApiKey();
        // 创建分析器
        mFraudAudioAnalyzer = new FraudAudioTranscriptionAnalyzer(this, apiKey);

        mFraudAudioAnalyzer.setFraudAudioListener(new FraudAudioTranscriptionAnalyzer.FraudAudioListener() {
            @Override
            public void onTranscriptionAnalyzed(FraudAudioTranscriptionAnalyzer.FraudTextResult result) {
                String riskEmoji;
                switch (result.riskLevel == null ? "" : result.riskLevel) {
                    case "high":
                        riskEmoji = "⚠️ 高风险";
                        break;
                    case "medium":
                        riskEmoji = "⚡ 中风险";
                        break;
                    case "low":
                        riskEmoji = "📍 低风险";
                        break;
                    case "none":
                        riskEmoji = "✅ 无风险";
                        break;
                    default:
                        riskEmoji = "❓ 未知";
                        break;
                }
                StringBuilder featSb = new StringBuilder();
                if (result.detectedFeatures != null && result.detectedFeatures.length > 0) {
                    featSb.append("检测到特征：\n");
                    for (String f : result.detectedFeatures) featSb.append("- ").append(f).append("\n");
                }
                String message = riskEmoji + "  风险评分: " + result.riskScore + "\n"
                        + featSb + "建议: " + result.advice + "\n原文: " + result.transcriptText;
                Log.i(TAG, "【文字诈骗检测】" + result.summary);
                Toast.makeText(peer.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onTranscription(String text) {
                Log.i(TAG, "【实时转录】" + text);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "【音频转录错误】" + error);
                Toast.makeText(peer.this, "音频转录: " + error, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onStatusUpdate(String status) {
                Log.i(TAG, "【音频转录】" + status);
            }

            @Override
            public void onTranscriptSaved(java.io.File file, String appendedText) {
                Log.i(TAG, "【已保存到本地】" + file.getAbsolutePath() + "  " + appendedText);
            }
        });

        // 按钮点击切换
        mFraudAudioTranscriptionBtn.setOnClickListener(v -> {
            if (!mIsFraudAudioRunning) {
                if (mRemoteAudioReflector == null) {
                    Toast.makeText(this, "请先建立通话，等待远端音频...", Toast.LENGTH_SHORT).show();
                    return;
                }
                mFraudAudioAnalyzer.setAudioReflector(mRemoteAudioReflector);
                mFraudAudioAnalyzer.start();
                // mymymy: 只有底层真正进入录制才翻 UI
                if (!mFraudAudioAnalyzer.isRunning()) {
                    Toast.makeText(this, "音频检测启动失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                mIsFraudAudioRunning = true;
                mFraudAudioTranscriptionBtn.setText("停止音频检测");
                Toast.makeText(this, "音频转文字 + 诈骗检测已开启，每 10 秒转一段", Toast.LENGTH_SHORT).show();
                logcatOnUI("开始音频转文字 + 诈骗检测");
            } else {
                mFraudAudioAnalyzer.stop();
                mIsFraudAudioRunning = false;
                mFraudAudioTranscriptionBtn.setText("音频转文字诈骗检测");
                Toast.makeText(this, "音频转文字 + 诈骗检测已停止", Toast.LENGTH_SHORT).show();
                logcatOnUI("停止音频转文字 + 诈骗检测");
            }
        });
    }

    // mymymy 读取 OpenAI API Key（gradle.properties → BuildConfig），占位符时返回原值并提示用户
    private String resolveOpenAiApiKey() {
        String key = BuildConfig.OPENAI_API_KEY;
        if (key == null || key.isEmpty() || key.startsWith("PLACEHOLDER_") || key.startsWith("YOUR_")) {
            Log.w(TAG, "OpenAI API Key 未配置（BuildConfig.OPENAI_API_KEY=" + key + "），请在 gradle.properties 设置 OPENAI_API_KEY=sk-xxx");
            Toast.makeText(this, "⚠️ 未配置 OPENAI_API_KEY，请在 gradle.properties 设置后重新编译", Toast.LENGTH_LONG).show();
        }
        return key;
    }

    // mymymy 初始化本地暖色调特效控制面板
    private void initLocalWarmEffectControls() {
        mLocalWarmControlPanel = findViewById(R.id.LocalWarmControlPanel);
        mLocalWarmthSeekBar = findViewById(R.id.LocalWarmthSeekBar);
        mLocalSaturationSeekBar = findViewById(R.id.LocalSaturationSeekBar);
        mLocalWarmthValue = findViewById(R.id.LocalWarmthValue);
        mLocalSaturationValue = findViewById(R.id.LocalSaturationValue);

        // 本地暖色调拖动条
        mLocalWarmthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float warmth = progress / 100.0f;
                mLocalWarmthValue.setText(String.format("%.2f", warmth));
                if (mLocalEffectProcessor != null) {
                    mLocalEffectProcessor.setWarmth(warmth);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 本地饱和度拖动条
        mLocalSaturationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sat = progress / 100.0f;
                mLocalSaturationValue.setText(String.format("%.2f", sat));
                if (mLocalEffectProcessor != null) {
                    mLocalEffectProcessor.setSaturation(sat);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // mymymy 切换本地暖色调特效
    private void toggleLocalWarmEffect() {
        mLocalWarmEffectEnabled = !mLocalWarmEffectEnabled;   // 翻转开关
        if (mLocalWarmEffectEnabled) {                        // 启用
            logcatOnUI("本地暖色调特效: 开启");
            mLocalWarmEffectToggleBtn.setText("关闭本地暖色调");
            // 显示控制面板
            if (mLocalWarmControlPanel != null) {
                mLocalWarmControlPanel.setVisibility(View.VISIBLE);
            }
            // 禁用灰度效果，启用暖色调效果
            if (mLocalEffectProcessor != null) {
                mLocalEffectProcessor.setGrayscaleEnabled(false);
                mLocalEffectProcessor.setEffectEnabled(true);
            }
        } else {                                              // 关闭
            logcatOnUI("本地暖色调特效: 关闭");
            mLocalWarmEffectToggleBtn.setText("本地暖色调特效");
            // 隐藏控制面板
            if (mLocalWarmControlPanel != null) {
                mLocalWarmControlPanel.setVisibility(View.GONE);
            }
            // 启用灰度效果，禁用暖色调效果
            if (mLocalEffectProcessor != null) {
                mLocalEffectProcessor.setEffectEnabled(false);
                mLocalEffectProcessor.setGrayscaleEnabled(true);
            }
        }
    }

    // mymym 开始录制本地视频
    private void startRecording() {
        // 创建录制器实例，传入 EGL 上下文以便共享 OpenGL 资源
        mVideoRecorder = new VideoRecorder(this, mRootEglBase.getEglBaseContext());

        // 将录制器添加到本地视频轨道，这样它就能收到视频帧了
        mVideoTrack.addSink(mVideoRecorder);

        // 生成保存路径（应用私有目录，无需权限）
        String fileName = "video_" + System.currentTimeMillis() + ".mp4";
        String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;

        // 开始录制
        if (mVideoRecorder.startRecording(outputPath)) {
            mIsRecording = true;
            mRecordToggleBtn.setText("停止录制");
            logcatOnUI("录制开始: " + fileName);
        } else {
            logcatOnUI("录制启动失败");
            mVideoTrack.removeSink(mVideoRecorder);
            mVideoRecorder = null;
        }
    }

    // mymymy 停止录制本地视频
    private void stopRecording() {
        if (mVideoRecorder != null) {
            mVideoRecorder.stopRecording();
            mVideoTrack.removeSink(mVideoRecorder);
            mVideoRecorder = null;
        }
        mIsRecording = false;
        mRecordToggleBtn.setText("录制本地视频");
        logcatOnUI("录制完成");
    }

    // mymymy 开始录制远端视频
    private void startRemoteRecording() {
        mRemoteVideoRecorder = new RemoteVideoRecorder(this, mRootEglBase.getEglBaseContext());

        if (mRemoteVideoTrack != null) {                      // 远端视频轨道存在
            mRemoteVideoTrack.addSink(mRemoteVideoRecorder);  // 录制器作为 sink
        } else {                                              // 还没收到远端视频
            logcatOnUI("远端视频轨道不可用");
            mRemoteVideoRecorder = null;
            return;
        }

        String fileName = "remote_video_" + System.currentTimeMillis() + ".mp4";
        String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;

        if (mRemoteVideoRecorder.startRecording(outputPath)) {
            mIsRemoteRecording = true;
            mRecordRemoteToggleBtn.setText("停止远端录制");
            logcatOnUI("远端录制开始: " + fileName);
        } else {
            logcatOnUI("远端录制启动失败");
            if (mRemoteVideoTrack != null) {
                mRemoteVideoTrack.removeSink(mRemoteVideoRecorder);
            }
            mRemoteVideoRecorder = null;
        }
    }

    // mymymy 停止录制远端视频
    private void stopRemoteRecording() {
        if (mRemoteVideoRecorder != null) {
            if (mRemoteVideoTrack != null) {
                mRemoteVideoTrack.removeSink(mRemoteVideoRecorder);
            }
            mRemoteVideoRecorder.stopRecording();
            mRemoteVideoRecorder = null;
        }
        mIsRemoteRecording = false;
        mRecordRemoteToggleBtn.setText("录制远端视频");
        logcatOnUI("远端录制完成");
    }

    // mymymy 扫描所有 receivers 查找音频 track
    // 解决 WebRTC 1.0.43591 onAddTrack 不触发音频回调的问题
    private void scanReceiversForAudioTrack() {
        if (mPeerConnection == null) return;
        try {
            for (org.webrtc.RtpReceiver receiver : mPeerConnection.getReceivers()) {
                if (receiver == null) continue;
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (track != null && "audio".equals(track.kind())) {
                    if (mRemoteAudioTrack == null) {
                        Log.i(TAG, "scanReceivers: found audio track: " + track.id());
                        mRemoteAudioTrack = (AudioTrack) track;
                        mRemoteAudioTrack.setEnabled(true);
                    } else {
                        Log.i(TAG, "scanReceivers: audio track already exists: " + mRemoteAudioTrack.id());
                    }
                    return;
                }
            }
            Log.i(TAG, "scanReceivers: no audio track found yet (count=" + mPeerConnection.getReceivers().size() + ")");
        } catch (Exception e) {
            Log.e(TAG, "scanReceivers failed", e);
        }
    }

    // mymymy 开始录制远端音频（通过反射从 WebRTC 内部获取远端 PCM，绕过麦克风问题）
    private void startRemoteAudioRecording() {
        try {
            Log.i(TAG, "=== startRemoteAudioRecording STEP 1: enter ===");
            Log.i(TAG, "mJavaAudioDeviceModule = " + (mJavaAudioDeviceModule != null));

            if (mJavaAudioDeviceModule == null) {              // JavaAudioDeviceModule 未初始化
                logcatOnUI("JavaAudioDeviceModule 未初始化");
                Toast.makeText(this, "JavaAudioDeviceModule 未初始化，请先建立通话", Toast.LENGTH_LONG).show();
                return;
            }

            // 主动扫描所有 receivers，查找音频 track
            Log.i(TAG, "=== startRemoteAudioRecording STEP 2: scan receivers ===");
            if (mRemoteAudioTrack == null && mPeerConnection != null) {
                try {
                    for (org.webrtc.RtpReceiver receiver : mPeerConnection.getReceivers()) {
                        if (receiver == null) continue;
                        org.webrtc.MediaStreamTrack track = receiver.track();
                        if (track != null && track.kind().equals("audio")) {
                            Log.i(TAG, "Found audio track from receivers: " + track.id());
                            mRemoteAudioTrack = (AudioTrack) track;
                            mRemoteAudioTrack.setEnabled(true);
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to scan receivers", e);
                }
            }

            // 创建 WebRtcAudioTrackReflector
            Log.i(TAG, "=== startRemoteAudioRecording STEP 3: new WebRtcAudioTrackReflector ===");
            try {
                mRemoteAudioReflector = new WebRtcAudioTrackReflector(mJavaAudioDeviceModule);
                Log.i(TAG, "=== startRemoteAudioRecording STEP 3 OK: reflector ready ===");
            } catch (Exception e) {
                Log.e(TAG, "Failed to create WebRtcAudioTrackReflector", e);
                logcatOnUI("反射获取远端音频失败: " + e.getMessage());
                Toast.makeText(this, "反射初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                mRemoteAudioReflector = null;
                return;
            }

            Log.i(TAG, "=== startRemoteAudioRecording STEP 4: new RemoteAudioRecorder ===");
            mRemoteAudioRecorder = new RemoteAudioRecorder();
            String fileName = "remote_audio_" + System.currentTimeMillis() + ".m4a";
            String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;

            // 注入反射器，让 RemoteAudioRecorder 在录音线程里调用 mRemoteAudioReflector.readPlayoutData
            mRemoteAudioRecorder.setPlayoutSource(mRemoteAudioReflector);

            Log.i(TAG, "=== startRemoteAudioRecording STEP 5: startRecording, output=" + outputPath + " ===");
            if (mRemoteAudioRecorder.startRecording(outputPath)) {
                mIsRemoteAudioRecording = true;
                mRecordRemoteAudioToggleBtn.setText("停止远端音频录制");
                Toast.makeText(this, "开始录制（WebRTC 内反射取 PCM）\n文件: " + fileName, Toast.LENGTH_SHORT).show();
                logcatOnUI("远端音频录制开始: " + fileName);
                Log.i(TAG, "=== startRemoteAudioRecording STEP 5 OK: recording started ===");
            } else {
                logcatOnUI("远端音频录制启动失败");
                Toast.makeText(this, "录制启动失败", Toast.LENGTH_SHORT).show();
                mRemoteAudioRecorder = null;
                mRemoteAudioReflector = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "FATAL: startRemoteAudioRecording crashed", t);
            logcatOnUI("录制异常: " + t.getMessage());
            Toast.makeText(this, "录制异常退出: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // mymymy 停止录制远端音频
    private void stopRemoteAudioRecording() {
        if (mRemoteAudioRecorder != null) {
            long recordedBytes = mRemoteAudioRecorder.getRecordedBytes();
            mRemoteAudioRecorder.stopRecording();
            mRemoteAudioRecorder = null;

            if (mRemoteAudioReflector != null) {
                logcatOnUI("反射抓取累计: " + mRemoteAudioReflector.getTotalBytesRead() + " bytes");
                mRemoteAudioReflector = null;
            }

            if (recordedBytes < 1000) {
                Toast.makeText(this, "录制完成但数据较少(" + recordedBytes + " bytes)\n可能没有远端音频", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "录制完成，共 " + (recordedBytes/1024) + " KB", Toast.LENGTH_SHORT).show();
            }
        }
        mIsRemoteAudioRecording = false;
        mRecordRemoteAudioToggleBtn.setText("录制远端音频");
        logcatOnUI("远端音频录制完成");
    }

    // mymymy 开始录制本端音频（独立类 LocalAudioRecorder，通过 SamplesReadyCallback 拿 PCM）
    private void startLocalAudioRecording() {
        try {
            Log.i(TAG, "=== startLocalAudioRecording STEP 1: enter ===");

            if (mJavaAudioDeviceModule == null) {
                Log.e(TAG, "mJavaAudioDeviceModule is null");
                logcatOnUI("JavaAudioDeviceModule 未初始化");
                Toast.makeText(this, "JavaAudioDeviceModule 未初始化，请先建立通话", Toast.LENGTH_LONG).show();
                return;
            }
            if (mLocalAudioBridge == null) {
                Log.e(TAG, "mLocalAudioBridge is null");
                logcatOnUI("LocalAudioBridge 未初始化");
                Toast.makeText(this, "本端音频桥接器未初始化", Toast.LENGTH_LONG).show();
                return;
            }

            mLocalAudioRecorder = new LocalAudioRecorder(mLocalAudioBridge);
            String fileName = "local_audio_" + System.currentTimeMillis() + ".m4a";
            String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;

            Log.i(TAG, "=== startLocalAudioRecording STEP 2: startRecording, output=" + outputPath + " ===");
            if (mLocalAudioRecorder.startRecording(outputPath)) {
                mIsLocalAudioRecording = true;
                mRecordLocalAudioToggleBtn.setText("停止本端音频录制");
                Toast.makeText(this, "开始录制本端音频\n文件: " + fileName, Toast.LENGTH_SHORT).show();
                logcatOnUI("本端音频录制开始: " + fileName);
                Log.i(TAG, "=== startLocalAudioRecording STEP 2 OK ===");
            } else {
                logcatOnUI("本端音频录制启动失败");
                Toast.makeText(this, "本端录制启动失败", Toast.LENGTH_SHORT).show();
                mLocalAudioRecorder = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "FATAL: startLocalAudioRecording crashed", t);
            logcatOnUI("本端录制异常: " + t.getMessage());
            Toast.makeText(this, "本端录制异常退出: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // mymymy 停止录制本端音频
    private void stopLocalAudioRecording() {
        if (mLocalAudioRecorder != null) {
            long recordedBytes = mLocalAudioRecorder.getRecordedBytes();
            mLocalAudioRecorder.stopRecording();
            mLocalAudioRecorder = null;

            if (mLocalAudioBridge != null) {
                logcatOnUI("本端 bridge 累计观察: " + mLocalAudioBridge.getTotalFramesObserved()
                        + " frames, dropped: " + mLocalAudioBridge.getTotalFramesDropped());
            }

            if (recordedBytes < 1000) {
                Toast.makeText(this, "录制完成但数据较少(" + recordedBytes + " bytes)\n可能没有本端麦克风数据", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "本端录制完成，共 " + (recordedBytes/1024) + " KB", Toast.LENGTH_SHORT).show();
            }
        }
        mIsLocalAudioRecording = false;
        mRecordLocalAudioToggleBtn.setText("录制本端音频");
        logcatOnUI("本端音频录制完成");
    }

    // mymymy 切换 Shader 渲染模式（远端）
    private void toggleShaderRendering() {
        mShaderEnabled = !mShaderEnabled;                      // 翻转开关
        if (mShaderEnabled) {                                  // 开启 Shader
            logcatOnUI("Shader mode: ON (warm color rendering)");
            mShaderToggleBtn.setText("关闭 Shader 暖色渲染");
            mControlPanel.setVisibility(View.VISIBLE);         // 显示参数面板
            if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.GONE);     // 隐藏默认视图
            if (mRemoteGLSurfaceView != null) {
                mRemoteGLSurfaceView.setVisibility(View.VISIBLE);                            // 显示 GL 视图
            }
            // mymymy 热切换：将已有的远端 track 路由到 Shader 渲染器
            reRouteRemoteTracksToShader();
        } else {                                                // 关闭 Shader
            logcatOnUI("Shader mode: OFF (WebRTC default rendering)");
            mShaderToggleBtn.setText("开启 Shader 暖色渲染");
            mControlPanel.setVisibility(View.GONE);
            if (mRemoteGLSurfaceView != null) mRemoteGLSurfaceView.setVisibility(View.GONE);
            if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.VISIBLE);
            // mymymy 热切换：将远端 track 路由回默认渲染器
            reRouteRemoteTracksToDefault();
        }
    }

    // mymymy 将远端 track 路由到 Shader 渲染器
    private void reRouteRemoteTracksToShader() {
        if (mRemoteVideoTrack == null || mRemoteGLSurfaceView == null) return;
        mRemoteVideoTrack.removeSink(mRemoteSurfaceView);       // 移除默认渲染
        mRemoteVideoTrack.addSink(mRemoteGLSurfaceView);        // 添加到 GL 渲染
        // mymymy 如果正在录制，确保录制器也连接着
        if (mIsRemoteRecording && mRemoteVideoRecorder != null) {
            mRemoteVideoTrack.addSink(mRemoteVideoRecorder);
        }
        logcatOnUI("Remote track routed to Shader renderer");
    }

    // mymymy 将远端 track 路由回默认渲染器
    private void reRouteRemoteTracksToDefault() {
        if (mRemoteVideoTrack == null || mRemoteSurfaceView == null) return;
        mRemoteVideoTrack.removeSink(mRemoteGLSurfaceView);     // 移除 GL 渲染
        mRemoteVideoTrack.addSink(mRemoteSurfaceView);          // 添加回默认
        if (mIsRemoteRecording && mRemoteVideoRecorder != null) {
            mRemoteVideoTrack.addSink(mRemoteVideoRecorder);
        }
        logcatOnUI("Remote track routed to default renderer");
    }

    /**
     * 恢复生命周期回调：开始摄像头采集
     */
    @Override
    protected void onResume() {
        super.onResume();
        mVideoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS);
    }

    /**
     * 暂停生命周期回调：停止摄像头采集
     */
    @Override
    protected void onPause() {
        super.onPause();
        try {
            mVideoCapturer.stopCapture();                       // 停止采集
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 销毁生命周期回调：释放所有资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        doLeave();                                              // 离开房间
        mLocalSurfaceView.release();                           // 释放本地渲染
        mRemoteSurfaceView.release();                          // 释放远端渲染
        // mymymy 释放 GL 渲染器
        if (mRemoteGLSurfaceView != null) {
            mRemoteGLSurfaceView.release();
            mRemoteGLSurfaceView = null;
        }
        // mymymy 停止录制并释放资源
        if (mIsRecording && mVideoRecorder != null) {
            stopRecording();
        }
        if (mIsRemoteRecording && mRemoteVideoRecorder != null) {
            stopRemoteRecording();
        }
        if (mIsRemoteAudioRecording && mRemoteAudioRecorder != null) {
            stopRemoteAudioRecording();
        }
        // mymymy 释放诈骗检测分析器
        if (mFraudDetectionAnalyzer != null) {
            mFraudDetectionAnalyzer.release();
            mFraudDetectionAnalyzer = null;
        }
        // mymymy 释放视频诈骗检测分析器（先从 VideoTrack 摘掉 sink，避免后续 dispose VideoTrack 时悬挂引用）
        if (mFraudVideoAnalyzer != null) {
            try {
                if (mRemoteVideoTrack != null) {
                    mFraudVideoAnalyzer.detachFromVideoTrack(mRemoteVideoTrack);
                }
            } catch (Throwable ignore) { }
            mFraudVideoAnalyzer.release();
            mFraudVideoAnalyzer = null;
        }
        // mymymy 释放音频转文字诈骗检测分析器
        if (mFraudAudioAnalyzer != null) {
            mFraudAudioAnalyzer.release();
            mFraudAudioAnalyzer = null;
        }
        mVideoCapturer.dispose();                               // 释放采集器
        mSurfaceTextureHelper.dispose();                        // 释放 SurfaceTexture 辅助
        PeerConnectionFactory.stopInternalTracingCapture();     // 关闭内部追踪
        PeerConnectionFactory.shutdownInternalTracer();
        mPeerConnectionFactory.dispose();                       // 释放工厂
    }

    /**
     * SDP 观察者的简单实现（用于日志记录）
     */
    public static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Log.i(TAG, "SdpObserver: onCreateSuccess !");
        }

        @Override
        public void onSetSuccess() {
            Log.i(TAG, "SdpObserver: onSetSuccess");
        }

        @Override
        public void onCreateFailure(String msg) {
            Log.e(TAG, "SdpObserver onCreateFailure: " + msg);
        }

        @Override
        public void onSetFailure(String msg) {
            Log.e(TAG, "SdpObserver onSetFailure: " + msg);
        }
    }

    /**
     * 更新通话状态 UI（idle=true 隐藏远端视图）
     */
    private void updateCallState(boolean idle) {
        runOnUiThread(() -> {
            if (idle) {
                // 空闲：全部隐藏
                if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.GONE);
                if (mRemoteGLSurfaceView != null) mRemoteGLSurfaceView.setVisibility(View.GONE);
            } else {
                // 通话中：按 Shader 模式选择显示
                if (mShaderEnabled) {
                    if (mRemoteGLSurfaceView != null) mRemoteGLSurfaceView.setVisibility(View.VISIBLE);
                } else {
                    if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    /**
     * 发起呼叫（创建 Offer 并发送）
     */
    public void doStartCall() {
        logcatOnUI("Start Call, Wait ...");
        if (mPeerConnection == null) {
            mPeerConnection = createPeerConnection();           // 创建 PeerConnection
        }
        // 配置媒体约束：必须接收音频和视频
        MediaConstraints mediaConstraints = new MediaConstraints();
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

        // 创建 Offer
        mPeerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.i(TAG, "Create local offer success: \n" + sessionDescription.description);
                mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription); // 设置本地 SDP
                // 通过信令发送 Offer
                JSONObject message = new JSONObject();
                try {
                    message.put("type", "offer");
                    message.put("sdp", sessionDescription.description);
                    SignalClient.getInstance().sendMessage(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, mediaConstraints);
    }

    /**
     * 离开房间
     */
    public void doLeave() {
        logcatOnUI("Leave room, Wait ...");
        hangup();                                               // 挂断 PeerConnection
        SignalClient.getInstance().leaveRoom();                 // 离开信令房间
    }

    /**
     * 接听呼叫（创建 Answer 并发送）
     */
    public void doAnswerCall() {
        logcatOnUI("Answer Call, Wait ...");

        if (mPeerConnection == null) {
            mPeerConnection = createPeerConnection();
        }

        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        Log.i(TAG, "Create answer ...");
        // 创建 Answer
        mPeerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.i(TAG, "Create answer success !");
                mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);

                // 发送 Answer
                JSONObject message = new JSONObject();
                try {
                    message.put("type", "answer");
                    message.put("sdp", sessionDescription.description);
                    SignalClient.getInstance().sendMessage(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, sdpMediaConstraints);
        updateCallState(false);                                 // 更新 UI 为通话中
    }

    /**
     * 挂断当前 PeerConnection
     */
    private void hangup() {
        logcatOnUI("Hangup Call, Wait ...");
        if (mPeerConnection == null) {
            return;
        }
        mPeerConnection.close();                                // 关闭连接
        mPeerConnection = null;
        logcatOnUI("Hangup Done.");
        updateCallState(true);                                  // 更新 UI 为空闲
    }

    /**
     * 创建 PeerConnection（包含 ICE 服务器配置）
     */
    public PeerConnection createPeerConnection() {
        Log.i(TAG, "Create PeerConnection ...");

        LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();

        // 添加 Google 公共 STUN 服务器
        PeerConnection.IceServer iceServer =
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                        .createIceServer();
        iceServers.add(iceServer);

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;             // 禁用 TCP 候选
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY; // 持续收集

        // 创建 PeerConnection
        PeerConnection connection = mPeerConnectionFactory.createPeerConnection(rtcConfig, mPeerConnectionObserver);

        if (connection == null) {
            Log.e(TAG, "Failed to createPeerConnection !");
            return null;
        }

        // 添加本地音视频 track
        List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");  // 流标签
        connection.addTrack(mVideoTrack, mediaStreamLabels);    // 视频轨道
        connection.addTrack(mAudioTrack, mediaStreamLabels);    // 音频轨道

        return connection;
    }

    // mymymy 保存 JavaAudioDeviceModule 引用（用于反射获取远端音频 PCM）
    private org.webrtc.audio.JavaAudioDeviceModule mJavaAudioDeviceModule;

    /**
     * 创建 PeerConnectionFactory（包含音频设备模块配置）
     */
    public PeerConnectionFactory createPeerConnectionFactory(Context context) {
        final VideoEncoderFactory encoderFactory;
        final VideoDecoderFactory decoderFactory;

        // 创建默认视频编码器工厂（禁用硬件编码 → true=启用；启用 H264 高画质 → true）
        encoderFactory = new DefaultVideoEncoderFactory(
                mRootEglBase.getEglBaseContext(),
                false,    // enableIntelVp8Encoder
                true);    // enableH264HighProfile
        // 创建默认视频解码器工厂
        decoderFactory = new DefaultVideoDecoderFactory(mRootEglBase.getEglBaseContext());

        // 初始化 PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions());

        // mymymy 显式创建 JavaAudioDeviceModule 并保存引用，以便后续通过反射获取远端音频 PCM
        // 同时创建本端音频桥接器，通过公开的 SamplesReadyCallback API 拿本端 PCM
        mLocalAudioBridge = new WebRtcAudioRecordSamplesBridge();      // 创建本端桥接器
        mJavaAudioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)               // 硬件回声消除
                .setUseHardwareNoiseSuppressor(true)                    // 硬件噪声抑制
                // mymymy 注入本端 PCM 回调（必须在 builder 阶段设置；bridge 内部默认丢弃，非录制期零开销）
                .setSamplesReadyCallback(mLocalAudioBridge)
                .setAudioRecordErrorCallback(new org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override
                    public void onWebRtcAudioRecordInitError(String errorMessage) {
                        Log.e(TAG, "AudioRecord init error: " + errorMessage);
                    }
                    @Override
                    public void onWebRtcAudioRecordStartError(
                            org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode,
                            String errorMessage) {
                        Log.e(TAG, "AudioRecord start error: " + errorCode + " " + errorMessage);
                    }
                    @Override
                    public void onWebRtcAudioRecordError(String errorMessage) {
                        Log.e(TAG, "AudioRecord error: " + errorMessage);
                    }
                })
                .setAudioTrackErrorCallback(new org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    @Override
                    public void onWebRtcAudioTrackInitError(String errorMessage) {
                        Log.e(TAG, "AudioTrack init error: " + errorMessage);
                    }
                    @Override
                    public void onWebRtcAudioTrackStartError(
                            org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode errorCode,
                            String errorMessage) {
                        Log.e(TAG, "AudioTrack start error: " + errorCode + " " + errorMessage);
                    }
                    @Override
                    public void onWebRtcAudioTrackError(String errorMessage) {
                        Log.e(TAG, "AudioTrack error: " + errorMessage);
                    }
                })
                .createAudioDeviceModule();                             // 构建 JavaAudioDeviceModule

        // 用 builder 模式创建 PeerConnectionFactory
        PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setAudioDeviceModule(mJavaAudioDeviceModule);          // 注入音频设备
        builder.setOptions(null);

        return builder.createPeerConnectionFactory();                 // 创建并返回
    }

    /**
     * 创建视频采集器（优先 Camera2，失败回退 Camera1）
     */
    private VideoCapturer createVideoCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            return createCameraCapturer(new Camera2Enumerator(this));
        } else {
            return createCameraCapturer(new Camera1Enumerator(true));
        }
    }

    /**
     * 在指定枚举器中查找可用摄像头（优先前置）
     */
    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        Log.d(TAG, "Looking for front facing cameras.");

        // 第一轮：找前置摄像头
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        Log.d(TAG, "Looking for other cameras.");

        // 第二轮：找任意可用摄像头
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;                                                  // 没找到摄像头
    }

    // PeerConnection 事件观察器（处理 ICE / Track / Stream 等回调）
    private final PeerConnection.Observer mPeerConnectionObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.i(TAG, "onSignalingChange: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.i(TAG, "onIceConnectionChange: " + iceConnectionState);

            // mymymy 当 ICE 连接成功时，主动扫描所有 receivers 查找音频 track
            if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED ||
                    iceConnectionState == PeerConnection.IceConnectionState.COMPLETED) {
                Log.i(TAG, "ICE connected, scanning for audio track in receivers...");
                scanReceiversForAudioTrack();                          // 扫描音频 track
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.i(TAG, "onIceConnectionReceivingChange: " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.i(TAG, "onIceGatheringChange: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.i(TAG, "onIceCandidate: " + iceCandidate);
            try {
                // 把 ICE 候选通过信令发给对端
                JSONObject message = new JSONObject();
                message.put("type", "candidate");
                message.put("label", iceCandidate.sdpMLineIndex);
                message.put("id", iceCandidate.sdpMid);
                message.put("candidate", iceCandidate.sdp);
                SignalClient.getInstance().sendMessage(message);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            for (IceCandidate iceCandidate : iceCandidates) {
                Log.i(TAG, "onIceCandidatesRemoved: " + iceCandidate);
            }
            mPeerConnection.removeIceCandidates(iceCandidates);      // 移除已失效的候选
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.i(TAG, "=== onAddStream called ===");
            Log.i(TAG, "onAddStream: video=" + mediaStream.videoTracks.size() + ", audio=" + mediaStream.audioTracks.size());
            Log.i(TAG, "onAddStream: stream id=" + mediaStream.getId());
            // mymymy 处理远端音频轨道
            if (mediaStream.audioTracks != null && !mediaStream.audioTracks.isEmpty()) {
                AudioTrack audioTrack = mediaStream.audioTracks.get(0);
                if (audioTrack != null) {
                    Log.i(TAG, "=== onAddStream: setting remote audio track ===");
                    mRemoteAudioTrack = audioTrack;
                    mRemoteAudioTrack.setEnabled(true);
                    Log.i(TAG, "onAddStream: audio track id=" + audioTrack.id() + ", enabled=" + audioTrack.enabled());
                }
            } else {
                Log.w(TAG, "onAddStream: no audio tracks in stream!");
            }
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.i(TAG, "onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.i(TAG, "onDataChannel");
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.i(TAG, "onRenegotiationNeeded");
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            MediaStreamTrack track = rtpReceiver.track();
            Log.i(TAG, "=== onAddTrack called ===");
            Log.i(TAG, "onAddTrack: track kind=" + track.kind() + ", id=" + track.id());
            if (track instanceof VideoTrack) {                       // 远端视频轨道
                Log.i(TAG, "onAddVideoTrack: video track received");
                mRemoteVideoTrack = (VideoTrack) track;
                mRemoteVideoTrack.setEnabled(true);
                // mymymy 根据当前模式选择渲染目标
                if (mShaderEnabled && mRemoteGLSurfaceView != null) {
                    mRemoteVideoTrack.addSink(mRemoteGLSurfaceView);
                } else {
                    mRemoteVideoTrack.addSink(mRemoteSurfaceView);
                }
            } else if (track instanceof AudioTrack) {                // 远端音频轨道
                Log.i(TAG, "=== onAddTrack: audio track received ===");
                mRemoteAudioTrack = (AudioTrack) track;
                mRemoteAudioTrack.setEnabled(true);
                Log.i(TAG, "onAddTrack: audio track id=" + mRemoteAudioTrack.id() + ", enabled=" + mRemoteAudioTrack.enabled());
            } else {                                                 // 未知类型
                Log.w(TAG, "onAddTrack: unknown track type: " + track.kind());
            }
        }
    };

    // 信令事件监听器实现
    private final SignalClient.OnSignalEventListener mOnSignalEventListener = new SignalClient.OnSignalEventListener() {

        @Override
        public void onConnected() {
            logcatOnUI("Signal Server Connected !");
        }

        @Override
        public void onConnecting() {
            logcatOnUI("Signal Server Connecting !");
        }

        @Override
        public void onDisconnected() {
            logcatOnUI("Signal Server Disconnected!");
        }

        @Override
        public void onUserJoined(String roomName, String userID) {
            logcatOnUI("local user joined!");
            mState = "joined";
            if (mPeerConnection == null) {
                mPeerConnection = createPeerConnection();
            }
        }

        @Override
        public void onUserLeaved(String roomName, String userID) {
            logcatOnUI("local user leaved!");
            mState = "leaved";
        }

        @Override
        public void onRemoteUserJoined(String roomName) {
            logcatOnUI("Remote User Joined, room: " + roomName);
            if (mState.equals("joined_unbind")) {
                if (mPeerConnection == null) {
                    mPeerConnection = createPeerConnection();
                }
            }
            mState = "joined_conn";
            doStartCall();                                          // 远端用户加入时发起呼叫
        }

        @Override
        public void onRemoteUserLeaved(String roomName, String userID) {
            logcatOnUI("Remote User Leaved, room: " + roomName + " uid:" + userID);
            mState = "joined_unbind";
            if (mPeerConnection != null) {
                mPeerConnection.close();
                mPeerConnection = null;
            }
        }

        @Override
        public void onRoomFull(String roomName, String userID) {
            logcatOnUI("The Room is Full, room: " + roomName + " uid:" + userID);
            mState = "leaved";

            // 释放所有资源并退出
            if (mLocalSurfaceView != null) {
                mLocalSurfaceView.release();
                mLocalSurfaceView = null;
            }

            if (mRemoteSurfaceView != null) {
                mRemoteSurfaceView.release();
                mRemoteSurfaceView = null;
            }

            if (mRemoteGLSurfaceView != null) {
                mRemoteGLSurfaceView.release();
                mRemoteGLSurfaceView = null;
            }

            if (mVideoCapturer != null) {
                mVideoCapturer.dispose();
                mVideoCapturer = null;
            }

            if (mSurfaceTextureHelper != null) {
                mSurfaceTextureHelper.dispose();
                mSurfaceTextureHelper = null;
            }

            PeerConnectionFactory.stopInternalTracingCapture();
            PeerConnectionFactory.shutdownInternalTracer();

            if (mPeerConnectionFactory != null) {
                mPeerConnectionFactory.dispose();
                mPeerConnectionFactory = null;
            }

            finish();                                               // 关闭 Activity
        }

        @Override
        public void onMessage(JSONObject message) {
            Log.i(TAG, "onMessage: " + message);
            try {
                String type = message.getString("type");
                if (type.equals("offer")) {
                    onRemoteOfferReceived(message);                  // 收到 Offer
                } else if (type.equals("answer")) {
                    onRemoteAnswerReceived(message);                 // 收到 Answer
                } else if (type.equals("candidate")) {
                    onRemoteCandidateReceived(message);              // 收到 ICE candidate
                } else {
                    Log.w(TAG, "the type is invalid: " + type);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // 处理收到的 Offer
        private void onRemoteOfferReceived(JSONObject message) {
            logcatOnUI("Receive Remote Call ...");
            if (mPeerConnection == null) {
                mPeerConnection = createPeerConnection();
            }
            try {
                String description = message.getString("sdp");
                mPeerConnection.setRemoteDescription(
                        new SimpleSdpObserver(),
                        new SessionDescription(SessionDescription.Type.OFFER, description)); // 设置远端 SDP
                doAnswerCall();                                       // 创建 Answer 并发送
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // 处理收到的 Answer
        private void onRemoteAnswerReceived(JSONObject message) {
            logcatOnUI("Receive Remote Answer ...");
            try {
                String description = message.getString("sdp");
                mPeerConnection.setRemoteDescription(
                        new SimpleSdpObserver(),
                        new SessionDescription(SessionDescription.Type.ANSWER, description));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            updateCallState(false);                                   // 更新 UI 为通话中
        }

        // 处理收到的 ICE 候选
        private void onRemoteCandidateReceived(JSONObject message) {
            logcatOnUI("Receive Remote Candidate ...");
            try {
                IceCandidate remoteIceCandidate = new IceCandidate(
                        message.getString("id"),
                        message.getInt("label"),
                        message.getString("candidate"));
                mPeerConnection.addIceCandidate(remoteIceCandidate);  // 添加远端 ICE 候选
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * 在 UI 上显示日志（同时打印到 logcat）
     */
    private void logcatOnUI(String msg) {
        Log.i(TAG, msg);                                             // logcat
        runOnUiThread(() -> {                                        // UI 线程
            String output = mLogcatView.getText() + "\n" + msg;       // 拼接新日志
            mLogcatView.setText(output);                              // 显示
        });
    }
}
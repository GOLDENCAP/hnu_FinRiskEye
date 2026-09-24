# 从零实现 peer —— 渐进式构建指南

> **目标读者**：了解 Android Activity 基础、知道 WebRTC 是什么、对 Socket.IO / OpenGL ES / 媒体编解码有粗略概念的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/peer.java`（1356 行）从"空 Activity"演进到当前规模 —— 一个完整的 WebRTC 音视频通话页面，包含本地/远端视频渲染、Shader 暖色调、4 路录制（本地视频、远端视频、本端音频、远端音频）等功能。
>
> **这是本目录里最大的文件**，会拆成 25+ 个阶段讲解。

---

## 目录

### 第一部分：骨架与生命周期
1. [这个 Activity 到底要做什么](#1-这个-activity-到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空 Activity + 加载 WebRTC native 库](#3-阶段-1--空-activity--加载-webrtc-native-库)
4. [阶段 2 —— 加视频采集常量 + 状态字段](#4-阶段-2--加视频采集常量--状态字段)
5. [阶段 3 —— 加 Shader UI 字段 + 录制 UI 字段](#5-阶段-3--加-shader-ui-字段--录制-ui-字段)
6. [阶段 4 —— 加本地暖色调 UI 字段 + 录制器字段](#6-阶段-4--加本地暖色调-ui-字段--录制器字段)
7. [阶段 5 —— 加远端音频字段 + 本地音频桥接器字段](#7-阶段-5--加远端音频字段--本地音频桥接器字段)
8. [阶段 6 —— 加本地视频特效字段](#8-阶段-6--加本地视频特效字段)
9. [阶段 7 —— 加 TAG 常量 + Track ID](#9-阶段-7--加-tag-常量--track-id)
10. [阶段 8 —— 加 WebRTC 核心字段](#10-阶段-8--加-webrtc-核心字段)
11. [阶段 9 —— 实现 onCreate 骨架](#11-阶段-9--实现-oncreate-骨架)
12. [阶段 10 —— onCreate: 加载布局 + 沉浸式](#12-阶段-10--oncreate-加载布局--沉浸式)
13. [阶段 11 —— onCreate: 初始化 EGL + 两个 SurfaceView](#13-阶段-11--oncreate-初始化-egl--两个-surfaceview)
14. [阶段 12 —— onCreate: 初始化 Shader GL SurfaceView](#14-阶段-12--oncreate-初始化-shader-gl-surfaceview)
15. [阶段 13 —— onCreate: 初始化控件（initShaderControls）](#15-阶段-13--oncreate-初始化控件initshadercontrols)

### 第二部分：WebRTC 初始化
16. [阶段 14 —— onCreate: 创建 PeerConnectionFactory（骨架）](#16-阶段-14--oncreate-创建-peerconnectionfactory骨架)
17. [阶段 15 —— onCreate: 创建视频采集器](#17-阶段-15--oncreate-创建视频采集器)
18. [阶段 16 —— onCreate: 创建视频轨道 + 特效处理器](#18-阶段-16--oncreate-创建视频轨道--特效处理器)
19. [阶段 17 —— onCreate: 创建音频轨道](#19-阶段-17--oncreate-创建音频轨道)
20. [阶段 18 —— onCreate: 设置信令监听器 + 加入房间](#20-阶段-18--oncreate-设置信令监听器--加入房间)
21. [阶段 19 —— initShaderControls: 饱和度/暖色调 SeekBar](#21-阶段-19--initshadercontrols-饱和度暖色调-seekbar)

### 第三部分：录制功能
22. [阶段 20 —— initRecordControls: 4 个录制按钮](#22-阶段-20--initrecordcontrols-4-个录制按钮)
23. [阶段 21 —— initLocalWarmEffectControls](#23-阶段-21--initlocalwarmeffectcontrols)
24. [阶段 22 —— toggleLocalWarmEffect + 录制开关](#24-阶段-22--togglelocalwarmeffect--录制开关)
25. [阶段 23 —— scanReceiversForAudioTrack](#25-阶段-23--scanreceiversforaudiotrack)
26. [阶段 24 —— 远端音频录制（反射 + 关键步骤日志）](#26-阶段-24--远端音频录制反射--关键步骤日志)
27. [阶段 25 —— 本端音频录制（bridge + LocalAudioRecorder）](#27-阶段-25--本端音频录制bridge--localaudiorecorder)
28. [阶段 26 —— Shader 模式切换 + 热切换路由](#28-阶段-26--shader-模式切换--热切换路由)

### 第四部分：生命周期与信令
29. [阶段 27 —— onResume / onPause / onDestroy](#29-阶段-27--onresume--onpause--ondestroy)
30. [阶段 28 —— SimpleSdpObserver + doStartCall](#30-阶段-28--simplesdpobserver--dostartcall)
31. [阶段 29 —— doAnswerCall + doLeave + hangup](#31-阶段-29--doanswercall--doleave--hangup)
32. [阶段 30 —— createPeerConnection](#32-阶段-30--createpeerconnection)
33. [阶段 31 —— createPeerConnectionFactory（详细）](#33-阶段-31--createpeerconnectionfactory详细)
34. [阶段 32 —— createVideoCapturer + createCameraCapturer](#34-阶段-32--createvideocapturer--createcameracapturer)
35. [阶段 33 —— PeerConnection.Observer（8 个回调）](#35-阶段-33--peerconnectionobserver8-个回调)
36. [阶段 34 —— SignalClient.OnSignalEventListener 实现](#36-阶段-34--signalclientonsignaleventlistener-实现)
37. [阶段 35 —— logcatOnUI](#37-阶段-35--logcathonui)
38. [完整最终代码对照](#38-完整最终代码对照)
39. [演进回顾表](#39-演进回顾表)
40. [常见问答](#40-常见问答)

---

## 1. 这个 Activity 到底要做什么

一句话：**完整的 WebRTC 音视频通话页面**，包含：
- 本地视频预览 + 远端视频显示
- 可切换默认渲染 / Shader 暖色调渲染
- 4 路录制（本地视频、远端视频、本端音频、远端音频）
- 通话状态机（init → joined → joined_conn → leaved）
- 信令客户端事件响应

---

## 2. 前置知识速查

### 2.1 WebRTC PeerConnection 三大组件

```
PeerConnectionFactory (工厂)
       ↓ 创建
PeerConnection (P2P 连接)
       ↓ 通过
MediaStream / AudioTrack / VideoTrack (媒体轨道)
```

### 2.2 SDP / ICE Candidate

- **SDP**（Session Description Protocol）：双方的能力描述（编解码、分辨率等）
  - Offer：发起方发出
  - Answer：响应方回复
- **ICE Candidate**：双方的网络地址候选（局域网 IP、公网 IP 等）

建立连接的流程：
1. A 创建 Offer → 通过信令发给 B
2. B 收到 Offer → 创建 Answer → 发回 A
3. A 和 B 互相交换 ICE Candidate
4. P2P 连接建立

### 2.3 VideoTrack / AudioTrack / DataChannel

- WebRTC 的"轨道"概念
- `addTrack` 到 PeerConnection 让对方能看到/听到
- `addSink` 让本地能渲染

### 2.4 通话状态机

```
init → joined → joined_conn → leaved
        ↑           ↓
        └───────────┘
```

---

## 3. 阶段 1 —— 空 Activity + 加载 WebRTC native 库

### 3.1 完整代码

```java
package com.example.chapter07;

import androidx.appcompat.app.AppCompatActivity;

public class peer extends AppCompatActivity {
    static {
        System.loadLibrary("jingle_peerconnection_so");
    }
}
```

### 3.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `extends AppCompatActivity` | 基类 |
| `static { System.loadLibrary(...) }` | **关键**：加载 WebRTC 的 .so 库 |

### 3.3 为什么 static 块？
- `static` 块在类加载时执行，**只在第一次使用类时执行一次**
- WebRTC 的 .so 库加载一次即可，多次加载会报错

---

## 4. 阶段 2 —— 加视频采集常量 + 状态字段

### 4.1 完整代码

```java
private static final int VIDEO_RESOLUTION_WIDTH = 1280;
private static final int VIDEO_RESOLUTION_HEIGHT = 720;
private static final int VIDEO_FPS = 30;

private String mState = "init";  // 通话状态
```

---

## 5. 阶段 3 —— 加 Shader UI 字段 + 录制 UI 字段

### 5.1 完整代码

```java
private TextView mLogcatView;  // 日志显示

private Button mShaderToggleBtn;       // Shader 开关
private Button mRecordToggleBtn;       // 本地视频录制
private Button mRecordRemoteToggleBtn; // 远端视频录制
private Button mRecordRemoteAudioToggleBtn; // 远端音频录制
private Button mLocalWarmEffectToggleBtn;   // 本地暖色调
private LinearLayout mControlPanel;
private SeekBar mSaturationSeekBar;
private SeekBar mWarmthSeekBar;
private TextView mSaturationValue;
private TextView mWarmthValue;
```

---

## 6. 阶段 4 —— 加本地暖色调 UI 字段 + 录制器字段

### 6.1 完整代码

```java
private LinearLayout mLocalWarmControlPanel;
private SeekBar mLocalWarmthSeekBar;
private SeekBar mLocalSaturationSeekBar;
private TextView mLocalWarmthValue;
private TextView mLocalSaturationValue;

private VideoRecorder mVideoRecorder;
private boolean mIsRecording = false;
private RemoteVideoRecorder mRemoteVideoRecorder;
private boolean mIsRemoteRecording = false;
```

---

## 7. 阶段 5 —— 加远端音频字段 + 本地音频桥接器字段

### 7.1 完整代码

```java
private RemoteAudioRecorder mRemoteAudioRecorder;
private boolean mIsRemoteAudioRecording = false;
private AudioTrack mRemoteAudioTrack;
private WebRtcAudioTrackReflector mRemoteAudioReflector;

private WebRtcAudioRecordSamplesBridge mLocalAudioBridge;
private LocalAudioRecorder mLocalAudioRecorder;
private boolean mIsLocalAudioRecording = false;
private Button mRecordLocalAudioToggleBtn;
```

---

## 8. 阶段 6 —— 加本地视频特效字段

### 8.1 完整代码

```java
private YuvFrameProcessorWithEffect mLocalEffectProcessor;
private boolean mLocalWarmEffectEnabled = false;
```

---

## 9. 阶段 7 —— 加 TAG 常量 + Track ID

### 9.1 完整代码

```java
private static final String TAG = "CallActivity";

public static final String VIDEO_TRACK_ID = "1";
public static final String AUDIO_TRACK_ID = "2";
```

---

## 10. 阶段 8 —— 加 WebRTC 核心字段

### 10.1 完整代码

```java
private PeerConnection mPeerConnection;
private PeerConnectionFactory mPeerConnectionFactory;
private EglBase mRootEglBase;
private SurfaceTextureHelper mSurfaceTextureHelper;

private SurfaceViewRenderer mLocalSurfaceView;
private SurfaceViewRenderer mRemoteSurfaceView;
private YuvGLSurfaceView mRemoteGLSurfaceView;
private boolean mShaderEnabled = false;
private VideoTrack mRemoteVideoTrack;

private VideoTrack mVideoTrack;
private AudioTrack mAudioTrack;
private VideoCapturer mVideoCapturer;
```

---

## 11. 阶段 9 —— 实现 onCreate 骨架

### 11.1 完整代码

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // 后续步骤
}
```

---

## 12. 阶段 10 —— onCreate: 加载布局 + 沉浸式

### 12.1 完整代码

```java
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

EdgeToEdge.enable(this);
setContentView(R.layout.activity_peer);
ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
    v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
    return insets;
});
mLogcatView = findViewById(R.id.LogcatView);
```

---

## 13. 阶段 11 —— onCreate: 初始化 EGL + 两个 SurfaceView

### 13.1 完整代码

```java
mRootEglBase = EglBase.create();

mLocalSurfaceView = findViewById(R.id.LocalSurfaceView);
mRemoteSurfaceView = findViewById(R.id.RemoteSurfaceView);

mLocalSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
mLocalSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
mLocalSurfaceView.setMirror(true);
mLocalSurfaceView.setEnableHardwareScaler(false);

mRemoteSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
mRemoteSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
mRemoteSurfaceView.setMirror(true);
mRemoteSurfaceView.setEnableHardwareScaler(true);

mLocalSurfaceView.setZOrderMediaOverlay(true);
```

---

## 14. 阶段 12 —— onCreate: 初始化 Shader GL SurfaceView

### 14.1 完整代码

```java
mRemoteGLSurfaceView = findViewById(R.id.RemoteGLSurfaceView);
// 初始化在 initShaderControls 之后（因为需要等控件加载）
```

---

## 15. 阶段 13 —— onCreate: 初始化控件（initShaderControls）

### 15.1 完整代码

```java
initShaderControls();
initRecordControls();

mPeerConnectionFactory = createPeerConnectionFactory(this);
Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);
mVideoCapturer = createVideoCapturer();
```

---

## 16. 阶段 14 —— onCreate: 创建 PeerConnectionFactory（骨架）

详见阶段 31。

---

## 17. 阶段 15 —— onCreate: 创建视频采集器

### 17.1 完整代码

```java
mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mRootEglBase.getEglBaseContext());
VideoSource videoSource = mPeerConnectionFactory.createVideoSource(false);
mVideoCapturer.initialize(mSurfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
mVideoTrack = mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
mVideoTrack.setEnabled(true);
```

---

## 18. 阶段 16 —— onCreate: 创建视频轨道 + 特效处理器

### 18.1 完整代码

```java
mLocalEffectProcessor = new YuvFrameProcessorWithEffect(mLocalSurfaceView);
mLocalEffectProcessor.setGrayscaleEnabled(true);  // 默认灰度
mVideoTrack.addSink(mLocalEffectProcessor);
```

---

## 19. 阶段 17 —— onCreate: 创建音频轨道

### 19.1 完整代码

```java
AudioSource audioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
mAudioTrack = mPeerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
mAudioTrack.setEnabled(true);
```

---

## 20. 阶段 18 —— onCreate: 设置信令监听器 + 加入房间

### 20.1 完整代码

```java
SignalClient.getInstance().setSignalEventListener(mOnSignalEventListener);

String serverAddr = getIntent().getStringExtra("ServerAddr");
String roomName = getIntent().getStringExtra("RoomName");

if (serverAddr == null || serverAddr.isEmpty()) {
    serverAddr = "https://192.168.31.234";
}
if (serverAddr.endsWith("/")) {
    serverAddr = serverAddr.substring(0, serverAddr.length() - 1);
}

SignalClient.getInstance().joinRoom(serverAddr, roomName);
```

---

## 21. 阶段 19 —— initShaderControls: 饱和度/暖色调 SeekBar

### 21.1 完整代码

```java
private void initShaderControls() {
    mShaderToggleBtn = findViewById(R.id.ShaderToggleBtn);
    mControlPanel = findViewById(R.id.ControlPanel);
    mSaturationSeekBar = findViewById(R.id.SaturationSeekBar);
    mWarmthSeekBar = findViewById(R.id.WarmthSeekBar);
    mSaturationValue = findViewById(R.id.SaturationValue);
    mWarmthValue = findViewById(R.id.WarmthValue);

    mShaderToggleBtn.setOnClickListener(v -> toggleShaderRendering());

    mSaturationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            float sat = progress / 100.0f;
            mSaturationValue.setText(String.format("%.2f", sat));
            if (mRemoteGLSurfaceView != null) {
                mRemoteGLSurfaceView.setSaturation(sat);
            }
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });

    mWarmthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            float warmth = progress / 100.0f;
            mWarmthValue.setText(String.format("%.2f", warmth));
            if (mRemoteGLSurfaceView != null) {
                mRemoteGLSurfaceView.setWarmth(warmth);
            }
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });
}
```

---

## 22. 阶段 20 —— initRecordControls: 4 个录制按钮

### 22.1 完整代码

```java
private void initRecordControls() {
    mRecordToggleBtn = findViewById(R.id.RecordToggleBtn);
    mRecordRemoteToggleBtn = findViewById(R.id.RecordRemoteToggleBtn);
    mRecordRemoteAudioToggleBtn = findViewById(R.id.RecordRemoteAudioToggleBtn);
    mRecordLocalAudioToggleBtn = findViewById(R.id.RecordLocalAudioToggleBtn);

    mRecordToggleBtn.setOnClickListener(v -> {
        if (!mIsRecording) startRecording();
        else stopRecording();
    });

    mRecordRemoteToggleBtn.setOnClickListener(v -> {
        if (!mIsRemoteRecording) startRemoteRecording();
        else stopRemoteRecording();
    });

    mRecordRemoteAudioToggleBtn.setOnClickListener(v -> {
        if (!mIsRemoteAudioRecording) startRemoteAudioRecording();
        else stopRemoteAudioRecording();
    });

    mRecordLocalAudioToggleBtn.setOnClickListener(v -> {
        if (!mIsLocalAudioRecording) startLocalAudioRecording();
        else stopLocalAudioRecording();
    });

    mLocalWarmEffectToggleBtn = findViewById(R.id.LocalWarmEffectToggleBtn);
    mLocalWarmEffectToggleBtn.setOnClickListener(v -> toggleLocalWarmEffect());

    initLocalWarmEffectControls();
}
```

---

## 23. 阶段 21 —— initLocalWarmEffectControls

### 23.1 完整代码

```java
private void initLocalWarmEffectControls() {
    mLocalWarmControlPanel = findViewById(R.id.LocalWarmControlPanel);
    mLocalWarmthSeekBar = findViewById(R.id.LocalWarmthSeekBar);
    mLocalSaturationSeekBar = findViewById(R.id.LocalSaturationSeekBar);
    mLocalWarmthValue = findViewById(R.id.LocalWarmthValue);
    mLocalSaturationValue = findViewById(R.id.LocalSaturationValue);

    // 暖色调拖动条
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

    // 饱和度拖动条（类似）
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
```

---

## 24. 阶段 22 —— toggleLocalWarmEffect + 录制开关

### 24.1 完整代码

```java
private void toggleLocalWarmEffect() {
    mLocalWarmEffectEnabled = !mLocalWarmEffectEnabled;
    if (mLocalWarmEffectEnabled) {
        logcatOnUI("本地暖色调特效: 开启");
        mLocalWarmEffectToggleBtn.setText("关闭本地暖色调");
        if (mLocalWarmControlPanel != null) mLocalWarmControlPanel.setVisibility(View.VISIBLE);
        if (mLocalEffectProcessor != null) {
            mLocalEffectProcessor.setGrayscaleEnabled(false);
            mLocalEffectProcessor.setEffectEnabled(true);
        }
    } else {
        logcatOnUI("本地暖色调特效: 关闭");
        mLocalWarmEffectToggleBtn.setText("本地暖色调特效");
        if (mLocalWarmControlPanel != null) mLocalWarmControlPanel.setVisibility(View.GONE);
        if (mLocalEffectProcessor != null) {
            mLocalEffectProcessor.setEffectEnabled(false);
            mLocalEffectProcessor.setGrayscaleEnabled(true);
        }
    }
}
```

### 24.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 翻转开关 | 切换状态 |
| 改按钮文字 | UI 反馈 |
| 显示/隐藏控制面板 | UI 切换 |
| 切换特效 | 真正的功能切换 |

---

## 25. 阶段 23 —— scanReceiversForAudioTrack

### 25.1 完整代码

```java
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
    } catch (Exception e) {
        Log.e(TAG, "scanReceivers failed", e);
    }
}
```

### 25.2 为什么需要这个方法？
- WebRTC 1.0.43591 的 onAddTrack **不触发音频回调**（已知 bug）
- 主动扫描 receivers 是 workaround

---

## 26. 阶段 24 —— 远端音频录制（反射 + 关键步骤日志）

### 26.1 完整代码（核心部分）

```java
private void startRemoteAudioRecording() {
    try {
        Log.i(TAG, "=== startRemoteAudioRecording STEP 1: enter ===");
        if (mJavaAudioDeviceModule == null) {
            logcatOnUI("JavaAudioDeviceModule 未初始化");
            return;
        }

        // 主动扫描所有 receivers，查找音频 track
        if (mRemoteAudioTrack == null && mPeerConnection != null) {
            for (org.webrtc.RtpReceiver receiver : mPeerConnection.getReceivers()) {
                if (receiver == null) continue;
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (track != null && track.kind().equals("audio")) {
                    mRemoteAudioTrack = (AudioTrack) track;
                    mRemoteAudioTrack.setEnabled(true);
                    break;
                }
            }
        }

        // 创建反射器
        mRemoteAudioReflector = new WebRtcAudioTrackReflector(mJavaAudioDeviceModule);

        // 创建录制器
        mRemoteAudioRecorder = new RemoteAudioRecorder();
        String fileName = "remote_audio_" + System.currentTimeMillis() + ".m4a";
        String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;
        mRemoteAudioRecorder.setPlayoutSource(mRemoteAudioReflector);

        if (mRemoteAudioRecorder.startRecording(outputPath)) {
            mIsRemoteAudioRecording = true;
            mRecordRemoteAudioToggleBtn.setText("停止远端音频录制");
            Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();
        }
    } catch (Throwable t) {
        Log.e(TAG, "FATAL: startRemoteAudioRecording crashed", t);
    }
}
```

### 26.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| STEP 1/2/3/4/5 日志 | 调试时定位问题 |
| catch Throwable | 兜底所有异常（不只是 Exception） |
| 主动扫描 receivers | Workaround for WebRTC bug |

---

## 27. 阶段 25 —— 本端音频录制

### 27.1 完整代码（核心部分）

```java
private void startLocalAudioRecording() {
    try {
        if (mJavaAudioDeviceModule == null) {
            logcatOnUI("JavaAudioDeviceModule 未初始化");
            return;
        }
        if (mLocalAudioBridge == null) {
            logcatOnUI("LocalAudioBridge 未初始化");
            return;
        }
        mLocalAudioRecorder = new LocalAudioRecorder(mLocalAudioBridge);
        String fileName = "local_audio_" + System.currentTimeMillis() + ".m4a";
        String outputPath = getExternalFilesDir(null).getAbsolutePath() + "/" + fileName;
        if (mLocalAudioRecorder.startRecording(outputPath)) {
            mIsLocalAudioRecording = true;
            mRecordLocalAudioToggleBtn.setText("停止本端音频录制");
        }
    } catch (Throwable t) {
        Log.e(TAG, "FATAL: startLocalAudioRecording crashed", t);
    }
}
```

---

## 28. 阶段 26 —— Shader 模式切换 + 热切换路由

### 28.1 完整代码

```java
private void toggleShaderRendering() {
    mShaderEnabled = !mShaderEnabled;
    if (mShaderEnabled) {
        mShaderToggleBtn.setText("关闭 Shader 暖色渲染");
        mControlPanel.setVisibility(View.VISIBLE);
        if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.GONE);
        if (mRemoteGLSurfaceView != null) mRemoteGLSurfaceView.setVisibility(View.VISIBLE);
        reRouteRemoteTracksToShader();
    } else {
        mShaderToggleBtn.setText("开启 Shader 暖色渲染");
        mControlPanel.setVisibility(View.GONE);
        if (mRemoteGLSurfaceView != null) mRemoteGLSurfaceView.setVisibility(View.GONE);
        if (mRemoteSurfaceView != null) mRemoteSurfaceView.setVisibility(View.VISIBLE);
        reRouteRemoteTracksToDefault();
    }
}

private void reRouteRemoteTracksToShader() {
    if (mRemoteVideoTrack == null || mRemoteGLSurfaceView == null) return;
    mRemoteVideoTrack.removeSink(mRemoteSurfaceView);
    mRemoteVideoTrack.addSink(mRemoteGLSurfaceView);
    if (mIsRemoteRecording && mRemoteVideoRecorder != null) {
        mRemoteVideoTrack.addSink(mRemoteVideoRecorder);
    }
}

private void reRouteRemoteTracksToDefault() {
    if (mRemoteVideoTrack == null || mRemoteSurfaceView == null) return;
    mRemoteVideoTrack.removeSink(mRemoteGLSurfaceView);
    mRemoteVideoTrack.addSink(mRemoteSurfaceView);
    if (mIsRemoteRecording && mRemoteVideoRecorder != null) {
        mRemoteVideoTrack.addSink(mRemoteVideoRecorder);
    }
}
```

### 28.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| toggleShaderRendering | Shader 模式切换 |
| reRouteRemoteTracksToShader/Default | **热切换**：不需要重建 track |
| 录制器 addSink | 录制器在 Shader 切换时保持连接 |

---

## 29. 阶段 27 —— onResume / onPause / onDestroy

### 29.1 完整代码

```java
@Override
protected void onResume() {
    super.onResume();
    mVideoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS);
}

@Override
protected void onPause() {
    super.onPause();
    try { mVideoCapturer.stopCapture(); } catch (InterruptedException e) { e.printStackTrace(); }
}

@Override
protected void onDestroy() {
    super.onDestroy();
    doLeave();
    mLocalSurfaceView.release();
    mRemoteSurfaceView.release();
    if (mRemoteGLSurfaceView != null) {
        mRemoteGLSurfaceView.release();
        mRemoteGLSurfaceView = null;
    }
    if (mIsRecording && mVideoRecorder != null) stopRecording();
    if (mIsRemoteRecording && mRemoteVideoRecorder != null) stopRemoteRecording();
    if (mIsRemoteAudioRecording && mRemoteAudioRecorder != null) stopRemoteAudioRecording();
    mVideoCapturer.dispose();
    mSurfaceTextureHelper.dispose();
    PeerConnectionFactory.stopInternalTracingCapture();
    PeerConnectionFactory.shutdownInternalTracer();
    mPeerConnectionFactory.dispose();
}
```

---

## 30. 阶段 28 —— SimpleSdpObserver + doStartCall

### 30.1 完整代码

```java
public static class SimpleSdpObserver implements SdpObserver {
    @Override public void onCreateSuccess(SessionDescription sessionDescription) {
        Log.i(TAG, "SdpObserver: onCreateSuccess !");
    }
    @Override public void onSetSuccess() { Log.i(TAG, "SdpObserver: onSetSuccess"); }
    @Override public void onCreateFailure(String msg) { Log.e(TAG, "SdpObserver onCreateFailure: " + msg); }
    @Override public void onSetFailure(String msg) { Log.e(TAG, "SdpObserver onSetFailure: " + msg); }
}

public void doStartCall() {
    logcatOnUI("Start Call, Wait ...");
    if (mPeerConnection == null) {
        mPeerConnection = createPeerConnection();
    }
    MediaConstraints mediaConstraints = new MediaConstraints();
    mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
    mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
    mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

    mPeerConnection.createOffer(new SimpleSdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
            JSONObject message = new JSONObject();
            try {
                message.put("type", "offer");
                message.put("sdp", sessionDescription.description);
                SignalClient.getInstance().sendMessage(message);
            } catch (JSONException e) { e.printStackTrace(); }
        }
    }, mediaConstraints);
}
```

---

## 31. 阶段 29 —— doAnswerCall + doLeave + hangup

### 31.1 完整代码（核心）

```java
public void doAnswerCall() {
    logcatOnUI("Answer Call, Wait ...");
    if (mPeerConnection == null) {
        mPeerConnection = createPeerConnection();
    }
    MediaConstraints sdpMediaConstraints = new MediaConstraints();
    mPeerConnection.createAnswer(new SimpleSdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
            JSONObject message = new JSONObject();
            try {
                message.put("type", "answer");
                message.put("sdp", sessionDescription.description);
                SignalClient.getInstance().sendMessage(message);
            } catch (JSONException e) { e.printStackTrace(); }
        }
    }, sdpMediaConstraints);
    updateCallState(false);
}

public void doLeave() {
    logcatOnUI("Leave room, Wait ...");
    hangup();
    SignalClient.getInstance().leaveRoom();
}

private void hangup() {
    logcatOnUI("Hangup Call, Wait ...");
    if (mPeerConnection == null) return;
    mPeerConnection.close();
    mPeerConnection = null;
    updateCallState(true);
}
```

---

## 32. 阶段 30 —— createPeerConnection

### 32.1 完整代码

```java
public PeerConnection createPeerConnection() {
    LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

    PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
    rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
    rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

    PeerConnection connection = mPeerConnectionFactory.createPeerConnection(rtcConfig, mPeerConnectionObserver);
    if (connection == null) return null;

    List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");
    connection.addTrack(mVideoTrack, mediaStreamLabels);
    connection.addTrack(mAudioTrack, mediaStreamLabels);
    return connection;
}
```

---

## 33. 阶段 31 —— createPeerConnectionFactory（详细）

### 33.1 完整代码（核心）

```java
public PeerConnectionFactory createPeerConnectionFactory(Context context) {
    final VideoEncoderFactory encoderFactory;
    final VideoDecoderFactory decoderFactory;

    encoderFactory = new DefaultVideoEncoderFactory(
            mRootEglBase.getEglBaseContext(), false, true);
    decoderFactory = new DefaultVideoDecoderFactory(mRootEglBase.getEglBaseContext());

    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true).createInitializationOptions());

    // 关键：显式创建 JavaAudioDeviceModule 并保存引用
    mLocalAudioBridge = new WebRtcAudioRecordSamplesBridge();
    mJavaAudioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback(mLocalAudioBridge)  // 关键！
            .setAudioRecordErrorCallback(...)            // 错误回调
            .setAudioTrackErrorCallback(...)             // 错误回调
            .createAudioDeviceModule();

    PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(mJavaAudioDeviceModule);
    builder.setOptions(null);
    return builder.createPeerConnectionFactory();
}
```

### 33.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `setSamplesReadyCallback(mLocalAudioBridge)` | **关键**：让本端 PCM 能被拿到 |
| `setUseHardwareAcousticEchoCanceler(true)` | 硬件回声消除 |
| 错误回调 | 录制失败时能看到日志 |

---

## 34. 阶段 32 —— createVideoCapturer + createCameraCapturer

### 34.1 完整代码

```java
private VideoCapturer createVideoCapturer() {
    if (Camera2Enumerator.isSupported(this)) {
        return createCameraCapturer(new Camera2Enumerator(this));
    } else {
        return createCameraCapturer(new Camera1Enumerator(true));
    }
}

private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
    final String[] deviceNames = enumerator.getDeviceNames();

    // 第一轮：找前置
    for (String deviceName : deviceNames) {
        if (enumerator.isFrontFacing(deviceName)) {
            VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
            if (videoCapturer != null) return videoCapturer;
        }
    }
    // 第二轮：找任意
    for (String deviceName : deviceNames) {
        if (!enumerator.isFrontFacing(deviceName)) {
            VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
            if (videoCapturer != null) return videoCapturer;
        }
    }
    return null;
}
```

---

## 35. 阶段 33 —— PeerConnection.Observer（8 个回调）

### 35.1 完整代码（核心）

```java
private final PeerConnection.Observer mPeerConnectionObserver = new PeerConnection.Observer() {
    @Override
    public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        Log.i(TAG, "onSignalingChange: " + signalingState);
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        // 关键：ICE 连接成功时主动扫描音频 track（workaround）
        if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED ||
                iceConnectionState == PeerConnection.IceConnectionState.COMPLETED) {
            scanReceiversForAudioTrack();
        }
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate) {
        // 把 ICE candidate 发给对端
        JSONObject message = new JSONObject();
        try {
            message.put("type", "candidate");
            message.put("label", iceCandidate.sdpMLineIndex);
            message.put("id", iceCandidate.sdpMid);
            message.put("candidate", iceCandidate.sdp);
            SignalClient.getInstance().sendMessage(message);
        } catch (JSONException e) { e.printStackTrace(); }
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        mPeerConnection.removeIceCandidates(iceCandidates);
    }

    @Override
    public void onAddStream(MediaStream mediaStream) {
        // 处理远端音频
        if (mediaStream.audioTracks != null && !mediaStream.audioTracks.isEmpty()) {
            AudioTrack audioTrack = mediaStream.audioTracks.get(0);
            mRemoteAudioTrack = audioTrack;
            mRemoteAudioTrack.setEnabled(true);
        }
    }

    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        MediaStreamTrack track = rtpReceiver.track();
        if (track instanceof VideoTrack) {
            mRemoteVideoTrack = (VideoTrack) track;
            mRemoteVideoTrack.setEnabled(true);
            if (mShaderEnabled && mRemoteGLSurfaceView != null) {
                mRemoteVideoTrack.addSink(mRemoteGLSurfaceView);
            } else {
                mRemoteVideoTrack.addSink(mRemoteSurfaceView);
            }
        } else if (track instanceof AudioTrack) {
            mRemoteAudioTrack = (AudioTrack) track;
            mRemoteAudioTrack.setEnabled(true);
        }
    }

    // 其他回调略
};
```

---

## 36. 阶段 34 —— SignalClient.OnSignalEventListener 实现

### 36.1 完整代码（核心）

```java
private final SignalClient.OnSignalEventListener mOnSignalEventListener = new SignalClient.OnSignalEventListener() {
    @Override public void onConnected() { logcatOnUI("Signal Server Connected !"); }
    @Override public void onDisconnected() { logcatOnUI("Signal Server Disconnected!"); }
    @Override public void onConnecting() { logcatOnUI("Signal Server Connecting !"); }

    @Override
    public void onUserJoined(String roomName, String userID) {
        logcatOnUI("local user joined!");
        mState = "joined";
        if (mPeerConnection == null) mPeerConnection = createPeerConnection();
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
            if (mPeerConnection == null) mPeerConnection = createPeerConnection();
        }
        mState = "joined_conn";
        doStartCall();
    }

    @Override
    public void onRemoteUserLeaved(String roomName, String userID) {
        logcatOnUI("Remote User Leaved, room: " + roomName);
        mState = "joined_unbind";
        if (mPeerConnection != null) { mPeerConnection.close(); mPeerConnection = null; }
    }

    @Override
    public void onRoomFull(String roomName, String userID) {
        logcatOnUI("The Room is Full, room: " + roomName);
        mState = "leaved";
        // 释放资源并退出
        // ...
        finish();
    }

    @Override
    public void onMessage(JSONObject message) {
        try {
            String type = message.getString("type");
            if (type.equals("offer")) onRemoteOfferReceived(message);
            else if (type.equals("answer")) onRemoteAnswerReceived(message);
            else if (type.equals("candidate")) onRemoteCandidateReceived(message);
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void onRemoteOfferReceived(JSONObject message) {
        logcatOnUI("Receive Remote Call ...");
        if (mPeerConnection == null) mPeerConnection = createPeerConnection();
        try {
            String description = message.getString("sdp");
            mPeerConnection.setRemoteDescription(new SimpleSdpObserver(),
                    new SessionDescription(SessionDescription.Type.OFFER, description));
            doAnswerCall();
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void onRemoteAnswerReceived(JSONObject message) {
        logcatOnUI("Receive Remote Answer ...");
        try {
            String description = message.getString("sdp");
            mPeerConnection.setRemoteDescription(new SimpleSdpObserver(),
                    new SessionDescription(SessionDescription.Type.ANSWER, description));
        } catch (JSONException e) { e.printStackTrace(); }
        updateCallState(false);
    }

    private void onRemoteCandidateReceived(JSONObject message) {
        logcatOnUI("Receive Remote Candidate ...");
        try {
            IceCandidate remoteIceCandidate = new IceCandidate(
                    message.getString("id"), message.getInt("label"), message.getString("candidate"));
            mPeerConnection.addIceCandidate(remoteIceCandidate);
        } catch (JSONException e) { e.printStackTrace(); }
    }
};
```

---

## 37. 阶段 35 —— logcatOnUI

### 37.1 完整代码

```java
private void logcatOnUI(String msg) {
    Log.i(TAG, msg);
    runOnUiThread(() -> {
        String output = mLogcatView.getText() + "\n" + msg;
        mLogcatView.setText(output);
    });
}
```

---

## 38. 完整最终代码对照

到阶段 35 为止，与 `app/src/main/java/com/example/chapter07/peer.java` 完全一致。所有代码已在前面 35 个阶段给出。

---

## 39. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 加载 .so | 10 | WebRTC 可用 |
| 2-8 | 字段声明 | 200 | 完整状态 |
| 9-15 | onCreate 骨架 | 350 | 初始化入口 |
| 14, 31 | createPeerConnectionFactory | 600 | 工厂 |
| 15-18 | 媒体轨道 | 650 | 音视频源 |
| 19-23 | UI 控件初始化 | 800 | UI 控制 |
| 24-27 | 录制功能 | 1100 | 4 路录制 |
| 28 | Shader 切换 | 1150 | 暖色调切换 |
| 29 | 生命周期 | 1200 | onResume/Pause/Destroy |
| 30-32 | SDP / PeerConnection | 1280 | P2P 建立 |
| 33 | PeerConnectionObserver | 1330 | ICE / Track 回调 |
| 34 | SignalClient 监听 | 1350 | 信令响应 |
| 35 | logcatOnUI | 1356 | 调试辅助 |

---

## 40. 常见问答

### Q1：为什么 static 加载 .so 而不是动态加载？
A：WebRTC .so 加载一次即可，static 块保证类加载时执行。

### Q2：为什么要 ZOrderMediaOverlay？
A：本地视频要显示在远端视频之上。
默认远端在上，本地用 `setZOrderMediaOverlay(true)` 让本地显示在上面。

### Q3：为什么有 scanReceiversForAudioTrack？
A：WebRTC 1.0.43591 的 onAddTrack 对音频不触发。
主动扫 receivers 是 workaround。

### Q4：为什么要 catch Throwable 而不是 Exception？
A：录制逻辑可能崩在 native 层（Error）。
Throwable 兜底所有错误。

### Q5：为什么要热切换 Shader 渲染？
A：不重建 PeerConnection 就能切换渲染目标，性能更好。

### Q6：为什么远端视频和远端音频分开 addSink？
A：VideoSink 接口只能处理视频。
音频走 AudioTrack.setEnabled + 反射拿 PCM。

### Q7：为什么默认启用灰度？
A：调试方便，看清 YUV 通道。生产环境应该默认无特效。

### Q8：mState 状态机的所有值是？
A：`init` → `joined` → `joined_conn`（远端加入） → `joined_unbind`（远端离开） → `leaved`

### Q9：onDestroy 里释放顺序重要吗？
A：是的。先释放 track → 再释放 sink → 再释放 factory。
否则可能泄露 native 资源。

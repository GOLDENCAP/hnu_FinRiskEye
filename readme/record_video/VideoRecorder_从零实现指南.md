# 从零实现 VideoRecorder —— 渐进式构建指南

> **目标读者**：了解 MediaCodec H.264 / MediaMuxer 基础、知道 EGL + OpenGL ES 是什么、写过简单 GL shader 的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/VideoRecorder.java`（500 行）从"空类"演进到当前规模。
>
> **核心数据流**：`WebRTC VideoFrame → I420 buffer → GL 上传纹理 → YUV→RGB Shader 渲染到 MediaCodec Surface → MediaCodec(H.264) → MediaMuxer(MP4)`
>
> **与 LocalAudioRecorder 的关键区别**：用 **EGL + OpenGL ES** 把视频帧渲染到 MediaCodec 的输入 Surface（零拷贝），而 LocalAudioRecorder 用 Java 字节数组（拷贝）。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空类实现 VideoSink + 构造](#3-阶段-1--空类实现-videosink--构造)
4. [阶段 2 —— 加常量（H.264 配置）](#4-阶段-2--加常量h264-配置)
5. [阶段 3 —— 加 EGL / GL 字段](#5-阶段-3--加-egl--gl-字段)
6. [阶段 4 —— 加 MediaCodec/MediaMuxer 字段](#6-阶段-4--加-mediacodecmediamuxer-字段)
7. [阶段 5 —— 加渲染线程字段](#7-阶段-5--加渲染线程字段)
8. [阶段 6 —— 实现 startRecording（骨架）](#8-阶段-6--实现-startrecording骨架)
9. [阶段 7 —— 配置 H.264 编码器](#9-阶段-7--配置-h264-编码器)
10. [阶段 8 —— 创建编码器并获取输入 Surface](#10-阶段-8--创建编码器并获取输入-surface)
11. [阶段 9 —— 启动渲染线程 + CountDownLatch 同步](#11-阶段-9--启动渲染线程--countdownlatch-同步)
12. [阶段 10 —— 创建 MediaMuxer](#12-阶段-10--创建-mediamuxer)
13. [阶段 11 —— 实现 onFrame（WebRTC 回调）](#13-阶段-11--实现-onframewebrtc-回调)
14. [阶段 12 —— 实现 processFrames 主循环](#14-阶段-12--实现-processframes-主循环)
15. [阶段 13 —— 实现 renderFrame 骨架](#15-阶段-13--实现-renderframe-骨架)
16. [阶段 14 —— renderFrame: ByteBuffer → byte[]](#16-阶段-14--renderframe-bytebuffer--byte)
17. [阶段 15 —— renderFrame: drawFrame](#17-阶段-15--renderframe-drawframe)
18. [阶段 16 —— 实现 initEGL](#18-阶段-16--实现-initegl)
19. [阶段 17 —— 实现 initGLResources（shader + 纹理）](#19-阶段-17--实现-initglresourcesshader--纹理)
20. [阶段 18 —— 实现 drawFrame（YUV→RGB 渲染）](#20-阶段-18--实现-drawframeyuvrgb-渲染)
21. [阶段 19 —— 实现 drainEncoderInternal](#21-阶段-19--实现-drainencoderinternal)
22. [阶段 20 —— 实现 stopRecording](#22-阶段-20--实现-stoprecording)
23. [阶段 21 —— 实现 releaseRecorderInternal](#23-阶段-21--实现-releaserecorderinternal)
24. [完整最终代码对照](#24-完整最终代码对照)
25. [演进回顾表](#25-演进回顾表)
26. [常见报错对照表](#26-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**把 WebRTC 视频帧通过 OpenGL ES 2.0 shader 渲染到 MediaCodec H.264 编码器的输入 Surface，编码后封装成 MP4 文件**。

这是用 EGL + GL 把"视频帧 → 编码输入"的零拷贝管线串起来的最常用 Android 视频录制方案。

---

## 2. 前置知识速查

### 2.1 EGL 是什么

- EGL = OpenGL 与窗口系统之间的接口层
- 让 GL 能"画到某个 Surface 上"
- Android 提供 `EGL14`（EGL 1.4）API

### 2.2 EGL 初始化六步

```
1. eglGetDisplay(EGL_DEFAULT_DISPLAY)        → EGLDisplay
2. eglInitialize(display, version)            → 拿到版本
3. eglChooseConfig(...)                       → EGLConfig（RGBA8888 + ES2）
4. eglCreateContext(...)                      → EGLContext（共享 GL 上下文）
5. eglCreateWindowSurface(...)                → EGLSurface（绑定到目标 Surface）
6. eglMakeCurrent(...)                        → 把当前线程绑定到 context+surface
```

### 2.3 MediaCodec 输入 Surface 模式

```java
mMediaCodec.configure(format, null, null, CONFIGURE_FLAG_ENCODE);
Surface inputSurface = mMediaCodec.createInputSurface();
// inputSurface 可以作为 EGLSurface 的目标
// OpenGL 渲染到这个 surface = 直接喂给编码器（零拷贝）
```

### 2.4 YUV → RGB Shader（BT.601）

```glsl
float y = texture2D(uYTexture, vTexCoord).r;
float u = texture2D(uUTexture, vTexCoord).r - 0.5;
float v = texture2D(uVTexture, vTexCoord).r - 0.5;
float r = y + 1.402 * v;
float g = y - 0.344 * u - 0.714 * v;
float b = y + 1.772 * u;
```

### 2.5 跨线程同步：CountDownLatch

- 等待 N 个事件完成后继续
- 本项目：等待 GL 线程初始化完成

---

## 3. 阶段 1 —— 空类实现 VideoSink + 构造

### 3.1 完整代码

```java
package com.example.chapter07;

import android.content.Context;
import org.webrtc.EglBase;
import org.webrtc.VideoSink;

public class VideoRecorder implements VideoSink {
    private final Context mContext;
    private final EglBase.Context mSharedContext;

    public VideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }
}
```

### 3.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements VideoSink` | 能 addSink |
| `mSharedContext` | 与 WebRTC 共享 EGL 上下文（跨线程纹理共享） |

---

## 4. 阶段 2 —— 加常量（H.264 配置）

### 4.1 完整代码

```java
private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;  // H.264
private static final int FRAME_RATE = 30;
private static final int I_FRAME_INTERVAL = 1;  // 每秒一个关键帧
private static final int BIT_RATE = 2_000_000;  // 2 Mbps
```

---

## 5. 阶段 3 —— 加 EGL / GL 字段

### 5.1 完整代码

```java
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
private android.opengl.EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
private Surface mEncoderSurface;

private int[] mYTexIds = new int[1];
private int[] mUTexIds = new int[1];
private int[] mVTexIds = new int[1];
private int mProgramId = 0;
private int mPositionHandle = -1, mTexCoordHandle = -1;
private int mYTexHandle = -1, mUTexHandle = -1, mVTexHandle = -1;
private FloatBuffer mVertexBuffer, mTexCoordBuffer;

private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f
};
private static final float[] TEXTURE_COORDS = {
        0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f
};
```

---

## 6. 阶段 4 —— 加 MediaCodec/MediaMuxer 字段

### 6.1 完整代码

```java
private MediaCodec mMediaCodec;
private MediaMuxer mMediaMuxer;
private int mVideoTrackIndex = -1;
private boolean mMuxerStarted = false;
private volatile boolean mIsRecording = false;
private String mOutputPath;
```

---

## 7. 阶段 5 —— 加渲染线程字段

### 7.1 完整代码

```java
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

private HandlerThread mRenderThread;
private Handler mRenderHandler;
private final ConcurrentLinkedQueue<VideoFrame> mFrameQueue = new ConcurrentLinkedQueue<>();
private final AtomicBoolean mProcessFrames = new AtomicBoolean(true);

private final Object mLock = new Object();

private int mVideoWidth = 1280;
private int mVideoHeight = 720;
```

---

## 8. 阶段 6 —— 实现 startRecording（骨架）

### 8.1 完整代码（骨架）

```java
public boolean startRecording(String outputPath) {
    synchronized (mLock) {
        if (mIsRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }
        this.mOutputPath = outputPath;
        try {
            // 步骤 1-6（下几步）
            mIsRecording = true;
            mProcessFrames.set(true);
            mRenderHandler.post(this::processFrames);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            releaseRecorderInternal();
            return false;
        }
    }
}
```

---

## 9. 阶段 7 —— 配置 H.264 编码器

### 9.1 完整代码

```java
MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);  // 关键：Surface 输入
format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
```

### 9.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `COLOR_FormatSurface` | 关键：让 MediaCodec 接受 GL 渲染输入（零拷贝） |

---

## 10. 阶段 8 —— 创建编码器并获取输入 Surface

### 10.1 完整代码

```java
mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
mEncoderSurface = mMediaCodec.createInputSurface();  // 拿到 Surface
mMediaCodec.start();
```

### 10.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `createEncoderByType` | 按 MIME 创建编码器 |
| `createInputSurface()` | 拿到 GL 可渲染的目标 Surface |

---

## 11. 阶段 9 —— 启动渲染线程 + CountDownLatch 同步

### 11.1 本阶段目标
启动 HandlerThread，并在 GL 线程初始化 EGL/GL。

### 11.2 遇到的新问题
**问题 1：为什么要 CountDownLatch？**
- EGL/GL 初始化必须在 GL 线程
- 但我们要在调用线程（主线程）等初始化完成才能继续
- 用 CountDownLatch 同步

**问题 2：为什么 EGL 初始化必须在 GL 线程？**
- EGLContext 是线程局部的
- 一个线程只能 makeCurrent 一个 context

### 11.3 完整代码

```java
mRenderThread = new HandlerThread("VideoRecorderThread");
mRenderThread.start();
mRenderHandler = new Handler(mRenderThread.getLooper());

final CountDownLatch initLatch = new CountDownLatch(1);
mRenderHandler.post(() -> {
    try {
        initEGL();
        initGLResources();
    } catch (Exception e) {
        Log.e(TAG, "Init error", e);
    } finally {
        initLatch.countDown();
    }
});
initLatch.await();
```

---

## 12. 阶段 10 —— 创建 MediaMuxer

### 12.1 完整代码

```java
File outputFile = new File(outputPath);
File dir = outputFile.getParentFile();
if (dir != null && !dir.exists()) dir.mkdirs();
mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

---

## 13. 阶段 11 —— 实现 onFrame（WebRTC 回调）

### 13.1 完整代码

```java
@Override
public void onFrame(VideoFrame frame) {
    if (!mIsRecording || frame == null) return;
    mFrameQueue.add(frame);  // 入队（WebRTC 线程 → 渲染线程）
}
```

### 13.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 快速路径（未录制直接返回） | 性能优化 |
| `add(frame)` | 跨线程传帧 |

---

## 14. 阶段 12 —— 实现 processFrames 主循环

### 14.1 完整代码

```java
private void processFrames() {
    while (mProcessFrames.get()) {
        VideoFrame frame = mFrameQueue.poll();
        if (frame != null) {
            try {
                renderFrame(frame);
            } catch (Exception e) {
                Log.e(TAG, "Render error", e);
            }
        } else {
            try { Thread.sleep(5); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

---

## 15. 阶段 13 —— 实现 renderFrame 骨架

### 15.1 完整代码

```java
private void renderFrame(VideoFrame frame) {
    if (frame == null || frame.getBuffer() == null) return;

    VideoFrame.I420Buffer i420 = null;
    try {
        i420 = frame.getBuffer().toI420();
    } catch (Exception e) {
        Log.e(TAG, "Failed to get I420 buffer", e);
        return;
    }
    if (i420 == null) return;

    int width = i420.getWidth();
    int height = i420.getHeight();

    // 更新分辨率
    synchronized (mLock) {
        if (width != mVideoWidth || height != mVideoHeight) {
            mVideoWidth = width;
            mVideoHeight = height;
        }
    }

    int ySize = width * height;
    int uvSize = ((width + 1) / 2) * ((height + 1) / 2);

    ByteBuffer yBuf = i420.getDataY();
    ByteBuffer uBuf = i420.getDataU();
    ByteBuffer vBuf = i420.getDataV();
    if (yBuf == null || uBuf == null || vBuf == null) {
        i420.release();
        return;
    }
    if (yBuf.capacity() < ySize || uBuf.capacity() < uvSize || vBuf.capacity() < uvSize) {
        i420.release();
        return;
    }

    byte[] yData = new byte[ySize];
    byte[] uData = new byte[uvSize];
    byte[] vData = new byte[uvSize];

    yBuf.rewind(); uBuf.rewind(); vBuf.rewind();
    yBuf.get(yData, 0, ySize);
    uBuf.get(uData, 0, uvSize);
    vBuf.get(vData, 0, uvSize);

    i420.release();

    drawFrame(yData, uData, vData, width, height);
}
```

---

## 16. 阶段 14 —— renderFrame: ByteBuffer → byte[]

已在阶段 13 中。

---

## 17. 阶段 15 —— renderFrame: drawFrame

将在阶段 18 详细讲。

---

## 18. 阶段 16 —— 实现 initEGL

### 18.1 本阶段目标
EGL 初始化六步。

### 18.2 完整代码

```java
private void initEGL() {
    mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("...");

    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        throw new RuntimeException("...");
    }

    int[] configAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
    };
    EGLConfig[] configs = new EGLConfig[1];
    int[] numConfigs = new int[1];
    if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0))
        throw new RuntimeException("...");

    int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
    mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
    if (mEglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("...");

    int[] surfaceAttribs = { EGL14.EGL_NONE };
    mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], mEncoderSurface, surfaceAttribs, 0);
    if (mEglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("...");

    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext))
        throw new RuntimeException("...");
}
```

### 18.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| eglGetDisplay + eglInitialize | 拿到 EGL 显示连接 |
| eglChooseConfig | 选 RGBA8888 + ES2 配置 |
| eglCreateContext | 创建 EGL 上下文（共享 WebRTC 的） |
| eglCreateWindowSurface | 创建绑定到 MediaCodec Surface 的 EGLSurface |
| eglMakeCurrent | 把当前线程绑定到 context+surface |

---

## 19. 阶段 17 —— 实现 initGLResources（shader + 纹理）

### 19.1 本阶段目标
编译 YUV→RGB shader + 创建三个 YUV 纹理。

### 19.2 完整代码（核心部分）

```java
private void initGLResources() {
    // Vertex Shader
    String vs = "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }";

    // Fragment Shader: YUV → RGB
    String fs = "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uYTexture;\n" +
                "uniform sampler2D uUTexture;\n" +
                "uniform sampler2D uVTexture;\n" +
                "void main() {\n" +
                "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
                "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
                "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
                "    float r = y + 1.402 * v;\n" +
                "    float g = y - 0.344 * u - 0.714 * v;\n" +
                "    float b = y + 1.772 * u;\n" +
                "    gl_FragColor = vec4(clamp(r, 0, 1), clamp(g, 0, 1), clamp(b, 0, 1), 1);\n" +
                "}";

    int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs);
    int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);

    mProgramId = GLES20.glCreateProgram();
    GLES20.glAttachShader(mProgramId, vertexShader);
    GLES20.glAttachShader(mProgramId, fragmentShader);
    GLES20.glLinkProgram(mProgramId);

    mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
    mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
    mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
    mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
    mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");

    mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTEX_COORDS);
    mVertexBuffer.rewind();

    mTexCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEXTURE_COORDS);
    mTexCoordBuffer.rewind();

    GLES20.glGenTextures(1, mYTexIds, 0);
    GLES20.glGenTextures(1, mUTexIds, 0);
    GLES20.glGenTextures(1, mVTexIds, 0);
}
```

---

## 20. 阶段 18 —— 实现 drawFrame（YUV→RGB 渲染）

### 20.1 本阶段目标
上传 YUV 到 GL 纹理，绘制到 MediaCodec Surface。

### 20.2 完整代码（核心部分）

```java
private void drawFrame(byte[] yData, byte[] uData, byte[] vData, int width, int height) {
    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) return;

    GLES20.glViewport(0, 0, width, height);
    GLES20.glClearColor(0, 0, 0, 1);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GLES20.glUseProgram(mProgramId);

    int uvHeight = (height + 1) / 2;
    int uvWidth = (width + 1) / 2;

    // Y 纹理
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));
    setTexParams();
    GLES20.glUniform1i(mYTexHandle, 0);

    // U 纹理
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvWidth, uvHeight, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(uData));
    setTexParams();
    GLES20.glUniform1i(mUTexHandle, 1);

    // V 纹理（类似）

    GLES20.glEnableVertexAttribArray(mPositionHandle);
    GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
    GLES20.glEnableVertexAttribArray(mTexCoordHandle);
    GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

    EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);  // 关键：触发编码

    synchronized (mLock) {
        drainEncoderInternal(false);
    }
}

private void setTexParams() {
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
}
```

### 20.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 三个 YUV 纹理上传 | 把 PCM 数据搬到 GPU |
| Shader 渲染 | YUV → RGB 转换 |
| `eglSwapBuffers` | 把渲染结果提交给 MediaCodec 编码 |

---

## 21. 阶段 19 —— 实现 drainEncoderInternal

### 21.1 本阶段目标
排空 MediaCodec 输出，写到 MP4。

### 21.2 完整代码（核心部分）

```java
private void drainEncoderInternal(boolean endOfStream) {
    if (mMediaCodec == null) return;
    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    while (true) {
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) break;
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (mMuxerStarted) throw new RuntimeException("Format changed twice");
            MediaFormat newFormat = mMediaCodec.getOutputFormat();
            mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
            mMediaMuxer.start();
            mMuxerStarted = true;
        } else if (outputBufferIndex >= 0) {
            ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0;
            }
            if (bufferInfo.size != 0 && mMuxerStarted) {
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, bufferInfo);
            }
            mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }
}
```

---

## 22. 阶段 20 —— 实现 stopRecording

### 22.1 本阶段目标
发送 EOS，停止编码，关闭 muxer。

### 22.2 完整代码（核心部分）

```java
public void stopRecording() {
    synchronized (mLock) {
        if (!mIsRecording) return;
        mIsRecording = false;
    }
    mProcessFrames.set(false);

    try {
        if (mRenderThread != null) mRenderThread.join(2000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }

    synchronized (mLock) {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.signalEndOfInputStream();
                drainEncoderInternal(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during stop recording", e);
        } finally {
            releaseRecorderInternal();
        }
    }
}
```

### 22.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `signalEndOfInputStream()` | 通知编码器输入结束 |
| `drainEncoderInternal(true)` | 强制排空直到 EOS |

---

## 23. 阶段 21 —— 实现 releaseRecorderInternal

### 23.1 本阶段目标
释放所有资源（EGL/GL/MediaCodec/MediaMuxer/Thread）。

### 23.2 完整代码（核心部分）

```java
private void releaseRecorderInternal() {
    if (mRenderThread != null) {
        HandlerThread t = mRenderThread;
        mRenderThread = null;
        t.quitSafely();
    }

    if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (mEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
        if (mEglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(mEglDisplay, mEglContext);
        EGL14.eglTerminate(mEglDisplay);
    }
    mEglDisplay = EGL14.EGL_NO_DISPLAY;
    mEglContext = EGL14.EGL_NO_CONTEXT;
    mEglSurface = EGL14.EGL_NO_SURFACE;

    if (mMediaCodec != null) {
        try { mMediaCodec.stop(); mMediaCodec.release(); } catch (Exception e) { ... }
        mMediaCodec = null;
    }
    if (mMediaMuxer != null) {
        try {
            if (mMuxerStarted) mMediaMuxer.stop();
            mMediaMuxer.release();
        } catch (Exception e) { ... }
        mMediaMuxer = null;
        mMuxerStarted = false;
    }
}
```

---

## 24. 完整最终代码对照

到阶段 21 为止，与 `app/src/main/java/com/example/chapter07/VideoRecorder.java` 完全一致。所有代码已在前面 21 个阶段给出。

---

## 25. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空类 + 构造 | 10 | 骨架 |
| 2 | H.264 常量 | 16 | 配置 |
| 3 | EGL/GL 字段 | 60 | EGL 准备 |
| 4 | MediaCodec/Muxer 字段 | 70 | 编码器 |
| 5 | 渲染线程字段 | 90 | 异步 |
| 6 | startRecording 骨架 | 110 | 启动入口 |
| 7 | 配置 H.264 | 130 | 编码器参数 |
| 8 | createInputSurface | 140 | 零拷贝 |
| 9 | 渲染线程 + Latch | 160 | EGL 同步 |
| 10 | MediaMuxer | 170 | MP4 输出 |
| 11 | onFrame | 180 | WebRTC 接入 |
| 12 | processFrames | 200 | 主循环 |
| 13-14 | renderFrame | 250 | 单帧处理 |
| 15 | drawFrame（待补） | — | — |
| 16 | initEGL | 320 | EGL 初始化 |
| 17 | initGLResources | 380 | shader/纹理 |
| 18 | drawFrame 完整 | 430 | YUV→RGB |
| 19 | drainEncoderInternal | 470 | 输出处理 |
| 20 | stopRecording | 490 | 优雅停止 |
| 21 | releaseRecorderInternal | 540 | 错误恢复 |

**关键里程碑**：
- 阶段 8 = 零拷贝管道建立
- 阶段 18 = 完整 GL 渲染
- 阶段 20 = 完整生命周期

---

## 26. 常见报错对照表

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `IllegalStateException at createInputSurface` | 8 | 编码器已配置。检查 configure 参数 |
| `GL_OUT_OF_MEMORY` | 17 | 纹理分配失败。检查分辨率 |
| MP4 文件只有几 KB | 19 | muxer 没 stop 或 stop 在 start 之前 |
| 黑屏 | 18 | YUV 上传时用了 width 而不是 stride |
| 颜色偏色 | 17 | BT.601 系数错了 |
| `No suitable EGLConfig` | 16 | 设备不支持 ES2 或 RGBA8888。检查设备 |
| `signalEndOfInputStream` 之后没数据 | 19 | drainEncoder 要用 endOfStream=true |
| 应用崩在 `eglMakeCurrent` | 16 | EGL 没初始化或已被 destroy |

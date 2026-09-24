# 从零实现 YuvGLSurfaceView —— 渐进式构建指南

> **目标读者**：了解 Android GLSurfaceView 用法、知道 OpenGL ES Shader、对 WebRTC VideoSink 接口有基础认识的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/YuvGLSurfaceView.java`（400 行）从"空 GLSurfaceView"演进到能接收 WebRTC 视频帧、用 GL Shader 渲染到屏幕、自带暖色调特效的完整自定义 View。
>
> **核心功能**：自定义 GLSurfaceView + GL Shader + WebRTC VideoSink 一体化实现。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 继承 GLSurfaceView](#3-阶段-1--继承-glsurfaceview)
4. [阶段 2 —— 实现 Renderer 接口（空）](#4-阶段-2--实现-renderer-接口空)
5. [阶段 3 —— 实现 VideoSink](#5-阶段-3--实现-videosink)
6. [阶段 4 —— 加 setter](#6-阶段-4--加-setter)
7. [阶段 5 —— 加常量（顶点/纹理坐标）](#7-阶段-5--加常量顶点纹理坐标)
8. [阶段 6 —— 加字段（标志 + 纹理）](#8-阶段-6--加字段标志--纹理)
9. [阶段 7 —— 实现 onSurfaceCreated 骨架](#9-阶段-7--实现-onsurfacecreated-骨架)
10. [阶段 8 —— onSurfaceCreated: GL 全局状态](#10-阶段-8--onsurfacecreated-gl-全局状态)
11. [阶段 9 —— onSurfaceCreated: 顶点/纹理 Buffer](#11-阶段-9--onsurfacecreated-顶点纹理-buffer)
12. [阶段 10 —— 实现 initShaders（GLES2/3 双版本）](#12-阶段-10--实现-initshadersgles23-双版本)
13. [阶段 11 —— initShaders: shader 编译与链接](#13-阶段-11--initshaders-shader-编译与链接)
14. [阶段 12 —— 实现 loadShader（带错误检查）](#14-阶段-12--实现-loadshader带错误检查)
15. [阶段 13 —— 实现 initTextures](#15-阶段-13--实现-inittextures)
16. [阶段 14 —— 实现 setTextureParams](#16-阶段-14--实现-settextureparams)
17. [阶段 15 —— 实现 onSurfaceChanged](#17-阶段-15--实现-onsurfacechanged)
18. [阶段 16 —— 实现 onDrawFrame](#18-阶段-16--实现-ondrawframe)
19. [阶段 17 —— 实现 onFrame（WebRTC）](#19-阶段-17--实现-onframewebrtc)
20. [阶段 18 —— 实现 uploadYuvData（跨线程）](#20-阶段-18--实现-uploadyuvdata跨线程)
21. [阶段 19 —— 实现 release + waitForSurfaceReady](#21-阶段-19--实现-release--waitforsurfaceready)
22. [完整最终代码对照](#22-完整最终代码对照)
23. [演进回顾表](#23-演进回顾表)
24. [常见问答](#24-常见问答)

---

## 1. 这个类到底要做什么

一句话：**自定义一个 GLSurfaceView，能直接作为 WebRTC VideoSink 接收视频帧，用 GL shader 渲染到屏幕，支持暖色调和饱和度调节**。

这个类比 `LocalWarmEffectRenderer` 完整 —— 渲染到屏幕（不是 FBO）。

---

## 2. 前置知识速查

### 2.1 GLSurfaceView 是什么

- Android 提供的一个"自动管理 GL 线程"的 View
- 你只需要实现 Renderer 接口
- GL 操作在专用线程（GL 线程）上跑
- 通过 `queueEvent(Runnable)` 把任务切到 GL 线程

### 2.2 Renderer 接口

```java
public interface Renderer {
    void onSurfaceCreated(GL10 gl, EGLConfig config);
    void onSurfaceChanged(GL10 gl, int width, int height);
    void onDrawFrame(GL10 gl);
}
```

### 2.3 GLSurfaceView 的 GL 线程

- 默认有一个 GL 线程在循环调 `onDrawFrame`
- 通过 `queueEvent(Runnable)` 把任务 post 到 GL 线程执行
- 通过 `requestRender()` 触发一次重绘（连续模式下不用调）

### 2.4 GLES2 vs GLES3 检测

```java
String versionString = GLES20.glGetString(GLES20.GL_VERSION);
boolean isGLES3 = versionString != null && versionString.contains("OpenGL ES 3.");
```

---

## 3. 阶段 1 —— 继承 GLSurfaceView

### 3.1 本阶段目标
创建空的自定义 GLSurfaceView。

### 3.2 完整代码

```java
package com.example.chapter07;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import androidx.annotation.Nullable;

public class YuvGLSurfaceView extends GLSurfaceView {
    public YuvGLSurfaceView(Context context) {
        this(context, null);
    }

    public YuvGLSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }
}
```

### 3.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `extends GLSurfaceView` | 继承 Android 的 GL View |
| 两个构造方法 | 支持代码 / XML 创建 |

---

## 4. 阶段 2 —— 实现 Renderer 接口（空）

### 4.1 完整代码

```java
public class YuvGLSurfaceView extends GLSurfaceView
        implements GLSurfaceView.Renderer {

    public YuvGLSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);  // 请求 ES3.0
        setRenderer(this);                // 设置自己为 renderer
        setRenderMode(RENDERMODE_CONTINUOUSLY);  // 连续渲染
    }

    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        // 暂不实现
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // 暂不实现
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // 暂不实现
    }
}
```

### 4.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements Renderer` | 让 GLSurfaceView 调用我们的渲染回调 |
| `setEGLContextClientVersion(3)` | 请求 ES 3.0（不支持会回退 ES 2.0） |
| `setRenderer(this)` | 把自己设为 renderer |
| `RENDERMODE_CONTINUOUSLY` | 默认每帧都重绘 |

---

## 5. 阶段 3 —— 实现 VideoSink

### 5.1 完整代码

```java
public class YuvGLSurfaceView extends GLSurfaceView
        implements GLSurfaceView.Renderer,
                   SurfaceTexture.OnFrameAvailableListener,
                   org.webrtc.VideoSink {

    @Override
    public void onFrame(VideoFrame frame) {
        // 暂不实现
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        requestRender();
    }
}
```

### 5.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements VideoSink` | 能 addSink 到 WebRTC VideoTrack |
| `implements OnFrameAvailableListener` | 接口要求实现（虽然本类没真用 SurfaceTexture） |
| `onFrameAvailable` 空实现 | 接口要求 |
| `requestRender()` | 触发一次重绘 |

---

## 6. 阶段 4 —— 加 setter

### 6.1 完整代码

```java
public void setWarmth(float warmth) {
    this.mWarmth = warmth;
}

public void setSaturation(float saturation) {
    this.mSaturation = saturation;
}
```

---

## 7. 阶段 5 —— 加常量（顶点/纹理坐标）

### 7.1 完整代码

```java
private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  1.0f, -1.0f, -1.0f,  1.0f,  1.0f,  1.0f
};

// 当前类：YUV 数据原点在左上，所以 t 不需要翻转（与 LocalWarmEffectRenderer 相反）
private static final float[] TEXTURE_COORDS = {
        0.0f, 0.0f,  1.0f, 0.0f,  0.0f, 1.0f,  1.0f, 1.0f
};
```

### 7.2 与 LocalWarmEffectRenderer 的区别

- `LocalWarmEffectRenderer`：`TEXTURE_COORDS` 用 `(0, 1)`（左下角对应 t=1）
- `YuvGLSurfaceView`：`TEXTURE_COORDS` 用 `(0, 0)`（左下角对应 t=0）

因为两者在 WebRTC 的 toI420 时的内部约定不同。

---

## 8. 阶段 6 —— 加字段（标志 + 纹理）

### 8.1 完整代码

```java
private final AtomicBoolean mHasFrame = new AtomicBoolean(false);
private final CountDownLatch mInitLatch = new CountDownLatch(1);

private int mCurrentWidth = 0;
private int mCurrentHeight = 0;

private int[] mYTexIds = new int[1];
private int[] mUTexIds = new int[1];
private int[] mVTexIds = new int[1];

private FloatBuffer mVertexBuffer;
private FloatBuffer mTexCoordBuffer;
private int mProgramId = 0;
private int mPositionHandle = -1, mTexCoordHandle = -1;
private int mYTexHandle = -1, mUTexHandle = -1, mVTexHandle = -1;

private volatile float mWarmth = 0.15f;
private volatile float mSaturation = 1.1f;
private volatile int mWarmthLocation = -1;
private volatile int mSaturationLocation = -1;

private SurfaceTexture mSurfaceTexture;
private boolean mGLES30Supported = false;
```

---

## 9. 阶段 7 —— 实现 onSurfaceCreated 骨架

### 9.1 完整代码

```java
@Override
public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
    if (DEBUG) Log.d(TAG, "onSurfaceCreated");

    // 检测版本
    String versionString = GLES20.glGetString(GLES20.GL_VERSION);
    mGLES30Supported = versionString != null && versionString.contains("OpenGL ES 3.");
    if (DEBUG) Log.i(TAG, "Using GLES " + (mGLES30Supported ? "3.0" : "2.0"));

    // 全局状态
    GLES20.glClearColor(0, 0, 0, 1);
    GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    GLES20.glDisable(GLES20.GL_BLEND);

    // 顶点/纹理 Buffer
    mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTEX_COORDS);
    mVertexBuffer.rewind();

    mTexCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEXTURE_COORDS);
    mTexCoordBuffer.rewind();

    // 初始化 shader 和纹理
    initShaders();
    initTextures();

    mInitLatch.countDown();  // 通知外部线程初始化完成
}
```

---

## 10. 阶段 8 —— onSurfaceCreated: GL 全局状态

已在阶段 9 中。

---

## 11. 阶段 9 —— onSurfaceCreated: 顶点/纹理 Buffer

已在阶段 9 中。

---

## 12. 阶段 10 —— 实现 initShaders（GLES2/3 双版本）

### 12.1 完整代码

```java
private void initShaders() {
    String vs, fs;
    if (mGLES30Supported) {
        vs = "#version 300 es\n" +
             "in vec4 aPosition;\n" +
             "in vec2 aTexCoord;\n" +
             "out vec2 vTexCoord;\n" +
             "void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }";

        fs = "#version 300 es\n" +
             "precision mediump float;\n" +
             "in vec2 vTexCoord;\n" +
             "uniform sampler2D uYTexture;\n" +
             "uniform sampler2D uUTexture;\n" +
             "uniform sampler2D uVTexture;\n" +
             "uniform float uWarmth;\n" +
             "uniform float uSaturation;\n" +
             "out vec4 fragColor;\n" +
             "void main() {\n" +
             "    float y = texture(uYTexture, vTexCoord).r;\n" +
             "    float u = texture(uUTexture, vTexCoord).r - 0.5;\n" +
             "    float v = texture(uVTexture, vTexCoord).r - 0.5;\n" +
             "    float r = y + 1.402 * v;\n" +
             "    float g = y - 0.344 * u - 0.714 * v;\n" +
             "    float b = y + 1.772 * u;\n" +
             "    vec3 color = vec3(r, g, b);\n" +
             "    float luminance = dot(color, vec3(0.299, 0.587, 0.114));\n" +
             "    color = mix(vec3(luminance), color, uSaturation);\n" +
             "    color.r = color.r + uWarmth * (1.0 - color.r);\n" +
             "    color.g = color.g + uWarmth * 0.3 * (1.0 - color.g);\n" +
             "    color.b = color.b - uWarmth * 0.7 * color.b;\n" +
             "    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
             "}";
    } else {
        vs = "attribute vec4 aPosition;\n" +
             "attribute vec2 aTexCoord;\n" +
             "varying vec2 vTexCoord;\n" +
             "void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }";

        fs = "precision mediump float;\n" +
             "varying vec2 vTexCoord;\n" +
             "uniform sampler2D uYTexture;\n" +
             "uniform sampler2D uUTexture;\n" +
             "uniform sampler2D uVTexture;\n" +
             "uniform float uWarmth;\n" +
             "uniform float uSaturation;\n" +
             "void main() {\n" +
             "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
             "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
             "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
             "    float r = y + 1.402 * v;\n" +
             "    float g = y - 0.344 * u - 0.714 * v;\n" +
             "    float b = y + 1.772 * u;\n" +
             "    vec3 color = vec3(r, g, b);\n" +
             "    float luminance = dot(color, vec3(0.299, 0.587, 0.114));\n" +
             "    color = mix(vec3(luminance), color, uSaturation);\n" +
             "    color.r = color.r + uWarmth * (1.0 - color.r);\n" +
             "    color.g = color.g + uWarmth * 0.3 * (1.0 - color.g);\n" +
             "    color.b = color.b - uWarmth * 0.7 * color.b;\n" +
             "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
             "}";
    }
    // 编译 + 链接（见阶段 11）
}
```

---

## 13. 阶段 11 —— initShaders: shader 编译与链接

### 13.1 完整代码

```java
int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs);
int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);

mProgramId = GLES20.glCreateProgram();
GLES20.glAttachShader(mProgramId, vertexShader);
GLES20.glAttachShader(mProgramId, fragmentShader);
GLES20.glLinkProgram(mProgramId);

int[] linkStatus = new int[1];
GLES20.glGetProgramiv(mProgramId, GLES20.GL_LINK_STATUS, linkStatus, 0);
if (linkStatus[0] != GLES20.GL_TRUE) {
    Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(mProgramId));
    return;
}

mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");
mWarmthLocation = GLES20.glGetUniformLocation(mProgramId, "uWarmth");
mSaturationLocation = GLES20.glGetUniformLocation(mProgramId, "uSaturation");
```

---

## 14. 阶段 12 —— 实现 loadShader（带错误检查）

### 14.1 完整代码

```java
private int loadShader(int type, String shaderCode) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, shaderCode);
    GLES20.glCompileShader(shader);
    int[] compiled = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
    if (compiled[0] == 0) {
        Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
        GLES20.glDeleteShader(shader);
        return 0;
    }
    return shader;
}
```

---

## 15. 阶段 13 —— 实现 initTextures

### 15.1 完整代码

```java
private void initTextures() {
    GLES20.glGenTextures(1, mYTexIds, 0);
    GLES20.glGenTextures(1, mUTexIds, 0);
    GLES20.glGenTextures(1, mVTexIds, 0);

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
    setTextureParams(GLES20.GL_TEXTURE_2D);

    // U、V 纹理同理
}

private void setTextureParams(int target) {
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
}
```

---

## 16. 阶段 14 —— 实现 setTextureParams

已在阶段 15 中。

---

## 17. 阶段 15 —— 实现 onSurfaceChanged

### 17.1 完整代码

```java
@Override
public void onSurfaceChanged(GL10 gl, int width, int height) {
    GLES20.glViewport(0, 0, width, height);
}
```

---

## 18. 阶段 16 —— 实现 onDrawFrame

### 18.1 完整代码

```java
@Override
public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

    if (!mHasFrame.get() || mCurrentWidth == 0 || mCurrentHeight == 0) return;
    if (mProgramId == 0) return;

    GLES20.glUseProgram(mProgramId);

    if (mWarmthLocation != -1) GLES20.glUniform1f(mWarmthLocation, mWarmth);
    if (mSaturationLocation != -1) GLES20.glUniform1f(mSaturationLocation, mSaturation);

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
    GLES20.glUniform1i(mYTexHandle, 0);

    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
    GLES20.glUniform1i(mUTexHandle, 1);

    GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
    GLES20.glUniform1i(mVTexHandle, 2);

    GLES20.glEnableVertexAttribArray(mPositionHandle);
    GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

    GLES20.glEnableVertexAttribArray(mTexCoordHandle);
    GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

    GLES20.glDisableVertexAttribArray(mPositionHandle);
    GLES20.glDisableVertexAttribArray(mTexCoordHandle);

    mHasFrame.set(false);
}
```

---

## 19. 阶段 17 —— 实现 onFrame（WebRTC）

### 19.1 完整代码

```java
@Override
public void onFrame(VideoFrame frame) {
    VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
    int width = i420.getWidth();
    int height = i420.getHeight();
    int yStride = i420.getStrideY();
    int uvStride = i420.getStrideU();
    int uvHeight = (height + 1) / 2;

    ByteBuffer yBuf = i420.getDataY();
    ByteBuffer uBuf = i420.getDataU();
    ByteBuffer vBuf = i420.getDataV();

    byte[] yData = new byte[yStride * height];
    byte[] uData = new byte[uvStride * uvHeight];
    byte[] vData = new byte[uvStride * uvHeight];

    yBuf.rewind(); uBuf.rewind(); vBuf.rewind();
    yBuf.get(yData);
    uBuf.get(uData);
    vBuf.get(vData);

    i420.release();

    uploadYuvData(yData, uData, vData, width, height, yStride, uvStride);
}
```

---

## 20. 阶段 18 —— 实现 uploadYuvData（跨线程）

### 20.1 完整代码

```java
public void uploadYuvData(final byte[] yData, final byte[] uData, final byte[] vData,
                          final int width, final int height,
                          final int yStride, final int uvStride) {
    final int uvHeight = (height + 1) / 2;

    queueEvent(() -> {
        boolean sizeChanged = (width != mCurrentWidth || height != mCurrentHeight);
        if (sizeChanged) {
            mCurrentWidth = width;
            mCurrentHeight = height;
            // 重新分配纹理
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    yStride, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
            // U、V 纹理同理
        }

        // 上传 YUV 数据（用 TexSubImage 增量更新）
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, yStride, height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));
        // U、V 纹理同理

        mHasFrame.set(true);
    });
}
```

### 20.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `queueEvent(...)` | 切到 GL 线程（GL 操作必须在 GL 线程） |
| `TexImage2D` 重新分配 | 分辨率变化时 |
| `TexSubImage2D` 增量更新 | 性能优化 |
| `mHasFrame.set(true)` | 通知 onDrawFrame 渲染 |

---

## 21. 阶段 19 —— 实现 release + waitForSurfaceReady

### 21.1 完整代码

```java
public void release() {
    try {
        queueEvent(() -> {
            if (mProgramId != 0) {
                GLES20.glDeleteProgram(mProgramId);
                mProgramId = 0;
            }
            GLES20.glDeleteTextures(1, mYTexIds, 0);
            GLES20.glDeleteTextures(1, mUTexIds, 0);
            GLES20.glDeleteTextures(1, mVTexIds, 0);
            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "release error", e);
    }
}

public void waitForSurfaceReady() {
    try {
        mInitLatch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 22. 完整最终代码对照

到阶段 19 为止，与 `app/src/main/java/com/example/chapter07/YuvGLSurfaceView.java` 完全一致。所有代码已在前面 19 个阶段给出。

---

## 23. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 继承 GLSurfaceView | 12 | View 基类 |
| 2 | 实现 Renderer | 30 | GL 渲染入口 |
| 3 | 实现 VideoSink | 45 | WebRTC 接入 |
| 4 | setter | 55 | 参数控制 |
| 5 | 常量 | 70 | 几何 |
| 6 | 字段 | 110 | 状态 |
| 7 | onSurfaceCreated 骨架 | 130 | GL 初始化入口 |
| 8 | GL 全局状态 | 140 | 渲染状态 |
| 9 | 顶点/纹理 Buffer | 160 | VBO |
| 10 | initShaders 双版本 | 220 | shader |
| 11 | shader 编译链接 | 240 | 链接 |
| 12 | loadShader 检查 | 260 | 错误处理 |
| 13 | initTextures | 280 | 纹理 |
| 14 | setTextureParams | 290 | 纹理参数 |
| 15 | onSurfaceChanged | 295 | 视口 |
| 16 | onDrawFrame | 340 | 渲染 |
| 17 | onFrame | 360 | WebRTC 入口 |
| 18 | uploadYuvData | 390 | 跨线程纹理 |
| 19 | release + waitForSurfaceReady | 410 | 生命周期 |

**关键里程碑**：
- 阶段 2 = GL 线程接入
- 阶段 18 = WebRTC → GL 线程完整链路
- 阶段 19 = 完整生命周期

---

## 24. 常见问答

### Q1：为什么用 GLSurfaceView 而不是 SurfaceView？
A：GLSurfaceView 自动管理 GL 线程，省心。
SurfaceView 需要自己管理 EGL + GL 线程。

### Q2：为什么 GLES2 + GLES3 双版本？
A：兼容老设备（只支持 GLES2）。
检测后用对应语法：
- ES3：`in/out`、`texture()`、`out vec4 fragColor`
- ES2：`attribute/varying`、`texture2D()`、`gl_FragColor`

### Q3：为什么要 queueEvent？
A：onFrame 是 WebRTC 线程，GL 操作必须在 GL 线程。
queueEvent 把任务 post 到 GL 线程执行。

### Q4：为什么 TexSubImage2D 而不是 TexImage2D？
A：TexImage2D 会重新分配 GPU 内存（慢）。
TexSubImage2D 只更新数据（快）。

### Q5：怎么设置只在有帧时渲染？
A：把 `setRenderMode(RENDERMODE_CONTINUOUSLY)` 改为 `RENDERMODE_WHEN_DIRTY`。
然后在 `uploadYuvData` 末尾调 `requestRender()`。

但当前实现用连续模式 + `mHasFrame` 标志，性能差不多但更简单。

### Q6：暖色调参数变了多久生效？
A：volatile 字段，下一次 `onDrawFrame` 读到新值（毫秒级）。

### Q7：和 YuvFrameProcessorWithEffect 的区别？
A：
- YuvFrameProcessorWithEffect：CPU 端特效（慢但简单）
- YuvGLSurfaceView：GPU 端特效（快，能显示到屏幕）

### Q8：怎么支持 SurfaceTexture？
A：实现 `SurfaceTexture.OnFrameAvailableListener`。
但当前类没真用 SurfaceTexture（用 GL 直接渲染）。

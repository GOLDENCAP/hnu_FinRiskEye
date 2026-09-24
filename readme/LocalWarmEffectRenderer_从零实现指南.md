# 从零实现 LocalWarmEffectRenderer —— 渐进式构建指南

> **目标读者**：了解 EGL + OpenGL ES 3.0 Shader、知道 WebRTC VideoSink 接口的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/LocalWarmEffectRenderer.java`（430 行）从"空类"演进到当前规模。
>
> **核心功能**：实现 VideoSink 接口，把 WebRTC 视频帧通过 GL ES 3.0 shader 渲染到 FBO（离屏纹理），支持暖色调和饱和度调节。
>
> **与 VideoRecorder 的关系**：两者都用 EGL + GL，但 VideoRecorder 是渲染到 MediaCodec Surface（输出文件），LocalWarmEffectRenderer 是渲染到 FBO（占位演示）。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空类实现 VideoSink + 构造](#3-阶段-1--空类实现-videosink--构造)
4. [阶段 2 —— 加 setter 方法（特效参数）](#4-阶段-2--加-setter-方法特效参数)
5. [阶段 3 —— 加 EGL/GL 字段](#5-阶段-3--加-eglgl-字段)
6. [阶段 4 —— 加 Shader / 纹理字段](#6-阶段-4--加-shader--纹理字段)
7. [阶段 5 —— 加顶点/纹理坐标常量](#7-阶段-5--加顶点纹理坐标常量)
8. [阶段 6 —— 加状态标志 + 特效参数字段](#8-阶段-6--加状态标志--特效参数字段)
9. [阶段 7 —— 实现 onFrame 主体](#9-阶段-7--实现-onframe-主体)
10. [阶段 8 —— 实现 processFrame](#10-阶段-8--实现-processframe)
11. [阶段 9 —— 实现 initGL](#11-阶段-9--实现-initgl)
12. [阶段 10 —— initGL: 8 个子步骤](#12-阶段-10--initgl-8-个子步骤)
13. [阶段 11 —— 实现 initFBO](#13-阶段-11--实现-initfbo)
14. [阶段 12 —— 实现 initShaders（GLES3 shader）](#14-阶段-12--实现-initshadersgles3-shader)
15. [阶段 13 —— 实现 initTextures](#15-阶段-13--实现-inittextures)
16. [阶段 14 —— 实现 initBuffers](#16-阶段-14--实现-initbuffers)
17. [阶段 15 —— 实现 loadShader 工具方法](#17-阶段-15--实现-loadshader-工具方法)
18. [阶段 16 —— 实现 release](#18-阶段-16--实现-release)
19. [完整最终代码对照](#19-完整最终代码对照)
20. [演进回顾表](#20-演进回顾表)
21. [常见问答](#21-常见问答)

---

## 1. 这个类到底要做什么

一句话：**用 EGL + OpenGL ES 3.0 shader 把 WebRTC 视频帧渲染到 FBO（Frame Buffer Object，离屏纹理），并支持暖色调/饱和度调节**。

业务场景：
- 视频通话时想让本地画面带暖色调
- 当前实现：渲染到 FBO，但**最终结果没传到屏幕**（演示性实现）
- 实际上更常用的方案是 `YuvFrameProcessorWithEffect`（CPU 端）或 `YuvGLSurfaceView`（GPU + SurfaceView）

---

## 2. 前置知识速查

### 2.1 OpenGL ES 3.0 vs 2.0

| 版本 | GLSL 语法 | attribute/varying | in/out |
|------|----------|------------------|--------|
| 2.0 | `attribute vec4 aPosition;` | 用 attribute / varying | — |
| 3.0 | `in vec4 aPosition;` | — | 用 in / out |

当前类用 ES 3.0（更现代）。

### 2.2 FBO（Frame Buffer Object）

- 离屏渲染目标
- 渲染到 FBO = 渲染到一张 GPU 纹理（不显示到屏幕）
- 用 `glFramebufferTexture2D` 把纹理挂到 FBO

### 2.3 PBuffer Surface

- EGL 的离屏渲染目标（不同于 WindowSurface）
- 本类用它创建 EGL 渲染表面
- 没有真实窗口，但 GL 渲染有目标

### 2.4 渲染流程

```
YUV data → glTexImage2D → GL Texture
                                ↓
                          GL Shader (YUV→RGB + 暖色调)
                                ↓
                          FBO → FBO Texture（结果）
                                ↓
                          （未使用，本演示）
```

---

## 3. 阶段 1 —— 空类实现 VideoSink + 构造

### 3.1 完整代码

```java
package com.example.chapter07;

import org.webrtc.VideoSink;

public class LocalWarmEffectRenderer implements VideoSink {
    private final VideoSink mTargetSink;

    public LocalWarmEffectRenderer(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }
}
```

### 3.2 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements VideoSink` | 能 addSink |
| `mTargetSink` | 下游 sink（透传） |

---

## 4. 阶段 2 —— 加 setter 方法（特效参数）

### 4.1 完整代码

```java
public void setEffectEnabled(boolean enabled) {
    mIsEffectEnabled = enabled;
}

public boolean isEffectEnabled() {
    return mIsEffectEnabled;
}

public void setWarmth(float warmth) {
    this.mWarmth = warmth;
}

public void setSaturation(float saturation) {
    this.mSaturation = saturation;
}
```

---

## 5. 阶段 3 —— 加 EGL/GL 字段

### 5.1 完整代码

```java
import android.opengl.EGL14;

private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
private android.opengl.EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
private int mTextureId = -1;
```

---

## 6. 阶段 4 —— 加 Shader / 纹理字段

### 6.1 完整代码

```java
import android.opengl.GLES30;

private int mProgramId = 0;
private int mPositionHandle = -1;
private int mTexCoordHandle = -1;
private int mYTexHandle = -1, mUTexHandle = -1, mVTexHandle = -1;

private int[] mYTexIds = new int[1];
private int[] mUTexIds = new int[1];
private int[] mVTexIds = new int[1];
private int[] mFBOTexIds = new int[1];
private int[] mFBOIds = new int[1];
```

---

## 7. 阶段 5 —— 加顶点/纹理坐标常量

### 7.1 完整代码

```java
import java.nio.FloatBuffer;

private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  1.0f, -1.0f, -1.0f,  1.0f,  1.0f,  1.0f
};
// 注意：YUV 原点在左上，所以 t 方向反一下
private static final float[] TEXTURE_COORDS = {
        0.0f, 1.0f,  1.0f, 1.0f,  0.0f, 0.0f,  1.0f, 0.0f
};
```

---

## 8. 阶段 6 —— 加状态标志 + 特效参数字段

### 8.1 完整代码

```java
private volatile boolean mIsEffectEnabled = false;
private volatile int mVideoWidth = 1280;
private volatile int mVideoHeight = 720;
private volatile float mWarmth = 0.3f;
private volatile float mSaturation = 1.1f;
private boolean mInitialized = false;
```

---

## 9. 阶段 7 —— 实现 onFrame 主体

### 9.1 本阶段目标
WebRTC 回调入口。

### 9.2 完整代码

```java
@Override
public void onFrame(VideoFrame frame) {
    if (frame == null || frame.getBuffer() == null) {
        mTargetSink.onFrame(frame);
        return;
    }

    VideoFrame.I420Buffer i420 = null;
    try {
        i420 = frame.getBuffer().toI420();
    } catch (Exception e) {
        Log.e(TAG, "Failed to get I420 buffer", e);
        mTargetSink.onFrame(frame);
        return;
    }
    if (i420 == null) {
        mTargetSink.onFrame(frame);
        return;
    }

    int width = i420.getWidth();
    int height = i420.getHeight();
    try {
        processFrame(i420, width, height);
    } catch (Exception e) {
        Log.e(TAG, "Error processing frame", e);
    } finally {
        i420.release();
    }
    mTargetSink.onFrame(frame);  // 注意：当前实现透传原始帧
}
```

### 9.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 防御性检查 | 防 frame 为 null |
| try/finally | 确保 i420Buffer release |
| `mTargetSink.onFrame(frame)` | 透传原始帧给下游 |

---

## 10. 阶段 8 —— 实现 processFrame

### 10.1 本阶段目标
实际 GL 渲染流程。

### 10.2 完整代码

```java
private void processFrame(VideoFrame.I420Buffer i420, int width, int height) {
    if (!mInitialized || !mIsEffectEnabled) return;
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) return;

    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
        Log.e(TAG, "Failed to make EGL context current");
        return;
    }

    GLES30.glViewport(0, 0, width, height);
    GLES30.glClearColor(0, 0, 0, 1);
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

    int yStride = i420.getStrideY();
    int uvStride = i420.getStrideU();
    int uvHeight = (height + 1) / 2;

    ByteBuffer yBuffer = i420.getDataY();
    yBuffer.rewind();
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mYTexIds[0]);
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE,
            yStride, height, 0, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, yBuffer);

    // U、V 纹理（类似）

    GLES30.glUseProgram(mProgramId);
    GLES30.glUniform1i(mYTexHandle, 0);
    GLES30.glUniform1i(mUTexHandle, 1);
    GLES30.glUniform1i(mVTexHandle, 2);
    GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uWarmth"), mWarmth);
    GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uSaturation"), mSaturation);

    GLES30.glEnableVertexAttribArray(mPositionHandle);
    GLES30.glVertexAttribPointer(mPositionHandle, 2, GLES30.GL_FLOAT, false, 0, mVertexBuffer);
    GLES30.glEnableVertexAttribArray(mTexCoordHandle);
    GLES30.glVertexAttribPointer(mTexCoordHandle, 2, GLES30.GL_FLOAT, false, 0, mTexCoordBuffer);

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFBOIds[0]);
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

    EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
}
```

### 10.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `eglMakeCurrent` | 把当前线程绑定到 EGL |
| YUV 纹理上传 | 数据从 CPU 到 GPU |
| Shader 使用 | YUV → RGB + 暖色调 |
| `glDrawArrays` | 绘制全屏四边形 |
| `eglSwapBuffers` | 提交渲染 |
| 绑定 FBO | 渲染到离屏纹理 |

---

## 11. 阶段 9 —— 实现 initGL

### 11.1 本阶段目标
EGL + GL 初始化入口。

### 11.2 完整代码

```java
public void initGL(Context context) {
    if (mInitialized) return;
    try {
        // 1-8 步骤（见下）
        mInitialized = true;
        Log.i(TAG, "OpenGL ES 3.0 initialized successfully");
    } catch (Exception e) {
        Log.e(TAG, "Failed to initialize OpenGL ES 3.0", e);
    }
}
```

---

## 12. 阶段 10 —— initGL: 8 个子步骤

### 12.1 完整代码

```java
// 1. EGL Display
mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
if (mEglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("...");

// 2. EGL Initialize
int[] version = new int[2];
if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) throw new RuntimeException("...");

// 3. EGL Config
int[] configAttribs = {
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, 0x0040,  // EGL_OPENGL_ES3_BIT
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE
};
EGLConfig[] configs = new EGLConfig[1];
int[] numConfigs = new int[1];
if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0))
    throw new RuntimeException("...");

// 4. EGL Context（ES 3.0）
int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
if (mEglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("...");

// 5. PBuffer Surface
int[] surfaceAttribs = {
        EGL14.EGL_WIDTH, mVideoWidth,
        EGL14.EGL_HEIGHT, mVideoHeight,
        EGL14.EGL_NONE
};
mEglSurface = EGL14.eglCreatePbufferSurface(mEglDisplay, configs[0], surfaceAttribs, 0);
if (mEglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("...");

// 6. Make Current
if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext))
    throw new RuntimeException("...");

// 7. GL Version
String versionString = GLES30.glGetString(GLES30.GL_VERSION);
Log.i(TAG, "OpenGL Version: " + versionString);

// 8. Init sub-resources
initShaders();
initTextures();
initBuffers();
initFBO();
```

---

## 13. 阶段 11 —— 实现 initFBO

### 13.1 完整代码

```java
private void initFBO() {
    GLES30.glGenFramebuffers(1, mFBOIds, 0);
    GLES30.glGenTextures(1, mFBOTexIds, 0);

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFBOTexIds[0]);
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            mVideoWidth, mVideoHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
    setTexParams();

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFBOIds[0]);
    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, mFBOTexIds[0], 0);

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
}

private void setTexParams() {
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
}
```

---

## 14. 阶段 12 —— 实现 initShaders（GLES3 shader）

### 14.1 完整代码

```java
private void initShaders() {
    String vertexShaderCode =
            "#version 300 es\n" +
            "in vec4 aPosition;\n" +
            "in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    String fragmentShaderCode =
            "#version 300 es\n" +
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
            "}\n";

    int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);
    int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode);

    mProgramId = GLES30.glCreateProgram();
    GLES30.glAttachShader(mProgramId, vertexShader);
    GLES30.glAttachShader(mProgramId, fragmentShader);
    GLES30.glLinkProgram(mProgramId);

    mPositionHandle = GLES30.glGetAttribLocation(mProgramId, "aPosition");
    mTexCoordHandle = GLES30.glGetAttribLocation(mProgramId, "aTexCoord");
    mYTexHandle = GLES30.glGetUniformLocation(mProgramId, "uYTexture");
    mUTexHandle = GLES30.glGetUniformLocation(mProgramId, "uUTexture");
    mVTexHandle = GLES30.glGetUniformLocation(mProgramId, "uVTexture");
}
```

---

## 15. 阶段 13 —— 实现 initTextures

### 15.1 完整代码

```java
private void initTextures() {
    GLES30.glGenTextures(1, mYTexIds, 0);
    GLES30.glGenTextures(1, mUTexIds, 0);
    GLES30.glGenTextures(1, mVTexIds, 0);

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mYTexIds[0]);
    setTexParams();
    // 同 U、V
}
```

---

## 16. 阶段 14 —— 实现 initBuffers

### 16.1 完整代码

```java
private void initBuffers() {
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
}
```

---

## 17. 阶段 15 —— 实现 loadShader 工具方法

### 17.1 完整代码

```java
private int loadShader(int type, String shaderCode) {
    int shader = GLES30.glCreateShader(type);
    GLES30.glShaderSource(shader, shaderCode);
    GLES30.glCompileShader(shader);
    return shader;
}
```

> 注：当前实现**不检查编译错误**。生产环境应该：
> ```java
> int[] compiled = new int[1];
> GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
> if (compiled[0] == 0) {
>     Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(shader));
>     GLES30.glDeleteShader(shader);
>     return 0;
> }
> ```

---

## 18. 阶段 16 —— 实现 release

### 18.1 完整代码

```java
public void release() {
    if (mFBOIds[0] != 0) {
        GLES30.glDeleteFramebuffers(1, mFBOIds, 0);
        mFBOIds[0] = 0;
    }
    if (mFBOTexIds[0] != 0) {
        GLES30.glDeleteTextures(1, mFBOTexIds, 0);
        mFBOTexIds[0] = 0;
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
    mInitialized = false;
}
```

---

## 19. 完整最终代码对照

到阶段 16 为止，与 `app/src/main/java/com/example/chapter07/LocalWarmEffectRenderer.java` 完全一致。所有代码已在前面 16 个阶段给出。

---

## 20. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空类 + 构造 | 10 | 骨架 |
| 2 | setter | 30 | 参数控制 |
| 3 | EGL 字段 | 38 | EGL 准备 |
| 4 | Shader/纹理字段 | 60 | GL 资源 |
| 5 | 顶点/纹理常量 | 75 | 几何 |
| 6 | 状态标志 | 90 | 参数 |
| 7 | onFrame | 130 | WebRTC 接入 |
| 8 | processFrame | 200 | 渲染主体 |
| 9 | initGL | 215 | 初始化入口 |
| 10 | 8 个子步骤 | 290 | EGL/GL 完整 |
| 11 | initFBO | 320 | 离屏目标 |
| 12 | initShaders | 380 | YUV→RGB+暖色 |
| 13 | initTextures | 400 | 纹理 |
| 14 | initBuffers | 415 | VBO |
| 15 | loadShader | 420 | shader 工具 |
| 16 | release | 450 | 资源释放 |

**关键里程碑**：
- 阶段 10 = EGL/GL 完整初始化
- 阶段 11 = FBO 离屏目标
- 阶段 12 = YUV→RGB + 特效 shader

---

## 21. 常见问答

### Q1：为什么用 GLES3 而不是 GLES2？
A：GLES3 用新版 GLSL（in/out），语法更清晰。
GLES2 也行，但要写 attribute/varying。

### Q2：为什么要 FBO？
A：因为渲染目标是"应用到屏幕"的话要 WindowSurface。
FBO 是离屏的，可以做"中间结果"，下一步再处理。

### Q3：为什么最后又把原帧透传给 mTargetSink？
A：当前 FBO 渲染的结果**没用到**，只是演示 GL pipeline。
所以保留原帧透传，让下游仍能看到原始画面。
实际场景应该把 FBO 纹理画到屏幕上。

### Q4：PBuffer Surface 和 WindowSurface 区别？
A：PBuffer 是离屏的（没有真实窗口）。
WindowSurface 绑定到 Android Surface（可以显示到屏幕）。

### Q5：为什么 YUV 原点在左上，但纹理坐标用了 (0, 1)？
A：OpenGL 纹理坐标原点在左下。
要把"左上"映射到"左下"，所以左下顶点对应纹理 (0, 1)。

### Q6：这个类适合生产环境吗？
A：不太适合。
- CPU 端特效：`YuvFrameProcessorWithEffect`（更简单）
- GPU + 屏幕显示：`YuvGLSurfaceView`（推荐）

当前类更适合做"GL pipeline 演示"。

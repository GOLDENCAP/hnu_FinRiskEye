# Android 本地视频录制功能实现详解

## 目录
1. [项目背景与目标](#1-项目背景与目标)
2. [核心概念与原理](#2-核心概念与原理)
3. [技术选型与架构设计](#3-技术选型与架构设计)
4. [代码实现详解](#4-代码实现详解)
5. [关键问题与解决方案](#5-关键问题与解决方案)
6. [总结与展望](#6-总结与展望)

---

## 1. 项目背景与目标

### 1.1 需求描述
在 WebRTC 视频通话应用（如 `peer.java`）中，实现对本地摄像头采集的视频流进行录制，并保存为标准的 MP4 文件。

### 1.2 核心挑战
1. **线程安全**：WebRTC 的视频帧回调（`VideoSink.onFrame`）发生在编码器线程，而非主线程。
2. **生命周期管理**：WebRTC 的 `VideoFrame` 使用引用计数管理，需要正确处理其生命周期。
3. **EGL 上下文管理**：OpenGL 渲染必须在正确的线程和 EGL 上下文中执行。
4. **编码与封装**：将原始视频帧编码为 H.264 格式，并封装为 MP4 容器。

---

## 2. 核心概念与原理

### 2.1 WebRTC VideoSink 机制
在 WebRTC 中，`VideoSink` 是一个接口，用于接收视频帧。
```java
public interface VideoSink {
    void onFrame(VideoFrame frame);
}
```
**原理**：
- `VideoTrack.addSink(VideoSink)` 将录制器订阅到视频轨道。
- 每一帧视频都会通过 `onFrame` 回调传递给所有订阅者。
- `VideoFrame` 包含时间戳、旋转角度以及视频数据（通常是 `I420Buffer`）。

### 2.2 视频编码原理 (MediaCodec)
Android 提供 `MediaCodec` API 用于硬件加速视频编码。
```java
MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
Surface inputSurface = encoder.createInputSurface();
encoder.start();
```
**原理**：
- `video/avc` 是 H.264 编码格式。
- `createInputSurface()` 创建一个 `Surface`，我们将 OpenGL 渲染的图像绘制到这个 Surface 上。
- `dequeueOutputBuffer` 获取编码后的数据。
- `MediaMuxer` 将编码后的 H.264 视频流和音频流（如果有）封装为 MP4 文件。

### 2.3 OpenGL ES 渲染流程
我们需要将 YUV 数据（摄像头原始格式）渲染到 MediaCodec 的输入 Surface 上。

**YUV 到 RGB 转换公式（Fragment Shader）：**
```glsl
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uYTexture;
uniform sampler2D uUTexture;
uniform sampler2D uVTexture;

void main() {
    float y = texture2D(uYTexture, vTexCoord).r;
    float u = texture2D(uUTexture, vTexCoord).r - 0.5;
    float v = texture2D(uVTexture, vTexCoord).r - 0.5;

    float r = y + 1.402 * v;
    float g = y - 0.344 * u - 0.714 * v;
    float b = y + 1.772 * u;

    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
```

### 2.4 多线程模型
为了避免阻塞 WebRTC 线程并确保 OpenGL 操作在正确的线程上执行，我们采用了以下架构：

```text
[WebRTC 编码线程]  -->  [ConcurrentLinkedQueue]  -->  [录制渲染线程 (HandlerThread)]
     |                                                |
     |  onFrame(VideoFrame)                          |  processFrames()
     |  (仅入队，不做耗时处理)                        |  (耗时操作：YUV拷贝、OpenGL渲染、编码)
     v                                                v
```

---

## 3. 技术选型与架构设计

### 3.1 组件选择
| 组件 | 用途 |
|------|------|
| `VideoSink` | 接收 WebRTC 视频帧 |
| `MediaCodec` | H.264 硬件编码 |
| `MediaMuxer` | MP4 封装 |
| `HandlerThread` | 独立的录制处理线程 |
| `ConcurrentLinkedQueue` | 帧缓冲队列 |
| `EGL14` | OpenGL 上下文管理 |

### 3.2 类设计：`VideoRecorder`
```java
public class VideoRecorder implements VideoSink {
    // 1. 编码相关
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private Surface mEncoderSurface;

    // 2. OpenGL 相关
    private EGLDisplay mEglDisplay;
    private EGLSurface mEglSurface;
    private android.opengl.EGLContext mEglContext;

    // 3. 线程安全
    private final Object mLock = new Object();
    private final ConcurrentLinkedQueue<VideoFrame> mFrameQueue = new ConcurrentLinkedQueue<>();
    private HandlerThread mRenderThread;
}
```

---

## 4. 代码实现详解

### 4.1 初始化 (startRecording)
```java
public boolean startRecording(String outputPath) {
    synchronized (mLock) {
        // 1. 配置 MediaCodec (H.264 编码器)
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        format.setInteger(KEY_COLOR_FORMAT, COLOR_FormatSurface); // 使用 Surface 作为输入
        format.setInteger(KEY_BIT_RATE, 2_000_000);
        format.setInteger(KEY_FRAME_RATE, 30);

        mMediaCodec = MediaCodec.createEncoderByType("video/avc");
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();

        // 2. 初始化 OpenGL 环境 (必须在独立的 HandlerThread 中执行)
        mRenderThread = new HandlerThread("VideoRecorderThread");
        mRenderThread.start();
        mRenderHandler = new Handler(mRenderThread.getLooper());
        mRenderHandler.post(() -> {
            initEGL();       // 创建 EGL Display, Context, Surface
            initGLResources(); // 编译 Shader, 创建 Program
        });

        // 3. 初始化 MediaMuxer
        mMediaMuxer = new MediaMuxer(outputPath, OutputFormat.MUXER_OUTPUT_MPEG_4);

        mIsRecording = true;
        mProcessFrames.set(true);
        // 4. 启动帧处理循环
        mRenderHandler.post(this::processFrames);
    }
}
```

### 4.2 接收帧 (onFrame)
```java
@Override
public void onFrame(VideoFrame frame) {
    if (!mIsRecording || frame == null) return;
    // 仅入队，不做耗时处理，保证 WebRTC 线程不卡顿
    mFrameQueue.add(frame);
}
```

### 4.3 处理帧 (processFrames & renderFrame)
```java
private void processFrames() {
    while (mProcessFrames.get()) {
        VideoFrame frame = mFrameQueue.poll();
        if (frame != null) {
            try {
                renderFrame(frame); // 在此线程中处理 OpenGL 和编码
            } catch (Exception e) {
                Log.e(TAG, "Render error", e);
            }
            // 注意：这里不需要 frame.release()
            // VideoSink 并不拥有帧的生命周期
        } else {
            Thread.sleep(5); // 避免空转 CPU
        }
    }
}

private void renderFrame(VideoFrame frame) {
    // 1. 提取 YUV 数据
    VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
    int width = i420.getWidth();
    int height = i420.getHeight();

    // 2. 拷贝数据（避免 ByteBuffer 复用导致的问题）
    byte[] yData = new byte[width * height];
    byte[] uData = new byte[((width+1)/2) * ((height+1)/2)];
    byte[] vData = new byte[((width+1)/2) * ((height+1)/2)];

    i420.getDataY().rewind().get(yData);
    i420.getDataU().rewind().get(uData);
    i420.getDataV().rewind().get(vData);
    i420.release();

    // 3. 绘制并编码
    drawFrame(yData, uData, vData, width, height);
}
```

### 4.4 OpenGL 绘制 (drawFrame)
```java
private void drawFrame(byte[] yData, byte[] uData, byte[] vData, int width, int height) {
    // 1. 设置 EGL 上下文
    EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext);

    // 2. 上传纹理
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
    // ... 配置纹理参数 ...
    GLES20.glTexImage2D(..., GLES20.GL_LUMINANCE, width, height, 0,
                         GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));

    // (U, V 纹理同理)

    // 3. 绘制四边形 (TRIANGLE_STRIP)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

    // 4. 交换缓冲区，将图像推送到 MediaCodec Input Surface
    EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);

    // 5. 取出编码数据并封装
    synchronized (mLock) {
        drainEncoder(false);
    }
}
```

### 4.5 停止录制 (stopRecording)
```java
public void stopRecording() {
    synchronized (mLock) {
        if (!mIsRecording) return;
        mIsRecording = false;
    }
    
    mProcessFrames.set(false);

    // 1. 等待录制线程结束
    try { mRenderThread.join(2000); } catch (InterruptedException e) {}

    synchronized (mLock) {
        try {
            // 2. 通知编码器结束
            mMediaCodec.signalEndOfInputStream();
            // 3. 取出剩余编码数据
            drainEncoder(true);
        } finally {
            // 4. 释放所有资源
            releaseRecorderInternal();
        }
    }
}
```

---

## 5. 关键问题与解决方案

### 5.1 崩溃：release() called on an object with refcount < 1
**问题**：`VideoFrame` 使用引用计数管理。`VideoSink` 不应该主动调用 `release()`（除非你先 `retain()` 了它）。
**解决**：移除 `frame.release()` 调用，让 WebRTC 管理帧的生命周期。

### 5.2 崩溃：Failed to make EGL context current
**问题**：EGL 操作（绘制、交换缓冲区）不在创建 EGL 上下文的同一线程上执行。
**解决**：
1. 创建独立的 `HandlerThread`。
2. 所有 EGL 和 OpenGL 调用都 post 到这个线程。
3. `onFrame` 仅负责将帧加入队列，不做渲染。

### 5.3 崩溃：媒体资源并发访问 (Race Condition)
**问题**：`stopRecording` 在 UI 线程释放 `MediaMuxer`，而 `drainEncoder` 在录制线程可能还在访问它。
**解决**：使用 `synchronized (mLock)` 保护所有对 `MediaCodec` 和 `MediaMuxer` 的访问。

### 5.4 视频黑屏或花屏
**问题**：YUV 数据拷贝逻辑错误，导致纹理数据不匹配。
**解决**：
1. 计算正确的 UV 平面大小：`((width+1)/2) * ((height+1)/2)`。
2. 使用 `rewind()` 重置 `ByteBuffer` 位置。
3. 严格控制拷贝的字节数，避免 `remaining()` 的歧义。

---

## 6. 总结与展望

### 6.1 总结
我们成功实现了一个高效的本地视频录制器，其核心在于：
1. **线程解耦**：使用队列将 WebRTC 线程和录制线程解耦。
2. **EGL 隔离**：保证 OpenGL 操作在稳定的上下文中执行。
3. **同步控制**：通过锁机制保护共享资源。
4. **生命周期管理**：正确处理 WebRTC 帧的生命周期。

### 6.2 后续优化方向
1. **音频录制**：集成 `AudioRecord` 或 WebRTC 的音频轨道，混合后封装。
2. **码率控制**：根据场景复杂度动态调整码率。
3. **文件切片**：支持录制文件分片，避免单个文件过大。
4. **硬件加速**：验证不同设备上的编码器性能。

# 从零实现 RemoteVideoRecorder —— 渐进式构建指南

> **目标读者**：会 Android 基础开发、知道 MediaCodec / OpenGL 是什么，但不知道"如何在 WebRTC 通话中录制远端视频"全貌的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/RemoteVideoRecorder.java`（约 700 行）从"空类"逐步演进到当前规模。每一阶段只做一件事，做完之后你能编译、能运行、能验证；再进入下一阶段。
>
> **本文不教**：MediaCodec / OpenGL ES / EGL / WebRTC VideoSink 各自的入门（默认你已经知道它们的"是什么"）。本文教的是：**当你已经知道单个零件之后，怎么把它们拼成一个可工作的录制器，以及拼装顺序为什么是这个**。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 仅占位的 VideoSink 空类](#3-阶段-1--仅占位的-videosink-空类)
4. [阶段 2 —— 加常量 + onFrame 打印日志](#4-阶段-2--加常量--onframe-打印日志)
5. [阶段 3 —— 初始化 MediaCodec H.264 编码器](#5-阶段-3--初始化-mediacodec-h264-编码器)
6. [阶段 4 —— 切换到 Surface 输入模式](#6-阶段-4--切换到-surface-输入模式)
7. [阶段 5 —— 初始化 MediaMuxer（MP4 容器）](#7-阶段-5--初始化-mediamuxermp4-容器)
8. [阶段 6 —— 初始化 EGL（Display / Config / Context）](#8-阶段-6--初始化-egldisplay--config--context)
9. [阶段 7 —— 把 EGLSurface 绑到 MediaCodec 输入](#9-阶段-7--把-eglsurface-绑到-mediacodec-输入)
10. [阶段 8 —— 写 OpenGL Shader（YUV→RGB）](#10-阶段-8--写-opengl-shaderyuvrgb)
11. [阶段 9 —— 纹理上传 + drawFrame](#11-阶段-9--纹理上传--drawframe)
12. [阶段 10 —— 独立渲染线程 HandlerThread](#12-阶段-10--独立渲染线程-handlerthread)
13. [阶段 11 —— 帧队列 + 停止标志](#13-阶段-11--帧队列--停止标志)
14. [阶段 12 —— renderFrame：从 VideoFrame 抽出 I420 byte[]](#14-阶段-12--renderframe从-videoframe-抽出-i420-byte)
15. [阶段 13 —— drainEncoderInternal：把编码数据写进 MP4](#15-阶段-13--drainencoderinternal把编码数据写进-mp4)
16. [阶段 14 —— stopRecording + 资源回收 + 同步锁 + 动态分辨率](#16-阶段-14--stoprecording--资源回收--同步锁--动态分辨率)
17. [完整最终代码对照](#17-完整最终代码对照)
18. [演进回顾表](#18-演进回顾表)
19. [常见报错对照表](#19-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**实现 WebRTC 的 `VideoSink` 接口，把远端视频帧一帧一帧渲染到 `MediaCodec` 的输入 `Surface`，再把编码后的 H.264 写入 `MediaMuxer` 产出 MP4 文件。**

它做的事情表面看起来和 `RemoteAudioRecorder` 很像（都是"录制远端媒体到 MP4"），但**视频比音频复杂得多**，主要因为：

| 维度 | 音频 | 视频 |
|------|------|------|
| 数据格式 | PCM（byte[] 就能装下） | YUV/I420（要拆三个平面） |
| 编码器输入 | `MediaCodec` 喂 byte[] | **`MediaCodec` 喂 `Surface`**（需要 OpenGL 渲染进去） |
| 渲染线程 | 普通 Java 线程即可 | **必须有 EGL 上下文**，GL 操作必须在当前绑定 EGL 的线程 |
| 数据传递 | 直接拷贝 byte[] | 必须把 YUV 上传成 GL 纹理 → shader 转 RGB → 画到 Surface |
| 同步 | 单生产者单消费者，锁即可 | 还要处理 `VideoFrame` 的引用计数 (`release()`) |

后面 14 个阶段就是在解决上面每一个复杂度，每一步只解决一个。

---

## 2. 前置知识速查

### 2.1 视频从 WebRTC 到 MP4 的完整数据流

```
WebRTC 远端 VideoTrack
       │ onFrame(VideoFrame)
       ▼
┌──────────────────────────────────────┐
│  WebRTC 线程                         │
│  onFrame(): 把 frame 塞进队列       │  ← 阶段 11
└──────────────┬───────────────────────┘
               │ ConcurrentLinkedQueue
               ▼
┌──────────────────────────────────────┐
│  渲染线程 (HandlerThread)            │
│  renderFrame(): I420 → byte[]      │  ← 阶段 12
│  drawFrame(): YUV 纹理上传 + 渲染   │  ← 阶段 9
└──────────────┬───────────────────────┘
               │ eglSwapBuffers → MediaCodec 输入 Surface
               ▼
┌──────────────────────────────────────┐
│  MediaCodec (H.264 硬件编码器)       │  ← 阶段 3-4
│  dequeueOutputBuffer → 编码帧       │  ← 阶段 13
└──────────────┬───────────────────────┘
               │ writeSampleData
               ▼
┌──────────────────────────────────────┐
│  MediaMuxer (MP4 容器)               │  ← 阶段 5
│  → 文件 outputPath                    │
└──────────────────────────────────────┘
```

我们这个类**既是入口（VideoSink）也是渲染器（EGL/GL）还是消费者（Muxer）**——把整条流水线都写在同一个类里，方便维护。

### 2.2 YUV / I420 速记

- **YUV420 planar (I420)**：三个平面 `Y`、`U`、`V`
  - `Y` 平面：宽 × 高 字节
  - `U` 平面：宽/2 × 高/2 字节
  - `V` 平面：宽/2 × 高/2 字节
- WebRTC 的 `VideoFrame.I420Buffer` 就是这个格式；用 `frame.getBuffer().toI420()` 取出来。
- 三个平面要分别上传成 3 张 GL 纹理，shader 里做 `YUV → RGB` 转换。

### 2.3 EGL / OpenGL / Surface 关系速记

| 概念 | 一句话 |
|------|--------|
| `EGLDisplay` | 对物理屏幕的"连接句柄"（一台机器一个） |
| `EGLConfig` | 选定的 RGBA / 深度 / 模板等像素格式 |
| `EGLContext` | GL 状态机的容器（线程私有） |
| `EGLSurface` | GL 渲染结果的"目标地"；可以是屏幕、可以是 PBuffer、**可以是 `MediaCodec.createInputSurface()` 返回的 `Surface`** |
| `eglMakeCurrent(d, s, s, c)` | 把"线程 ↔ Context ↔ Surface"绑定 |
| `eglSwapBuffers(d, s)` | 把当前帧"提交"到 EGLSurface，触发 MediaCodec 编码 |

> **关键事实**：`eglSwapBuffers` 才是真正"把画面送进 MediaCodec"的瞬间。光 `glDrawArrays` 不会触发编码。

### 2.4 MediaCodec Surface 输入模式 vs ByteBuffer 模式

| 模式 | 怎么喂数据 | 性能 | 适用 |
|------|----------|------|------|
| ByteBuffer | `codec.getInputBuffer(i).put(yuvBytes)` | 慢，有拷贝 | YUV byte[] 已经准备好时 |
| **Surface** | OpenGL 把帧画到 `codec.createInputSurface()` | **快，零拷贝，硬件加速** | **本文使用** |

Surface 模式之所以能零拷贝，是因为 GL 渲染输出的 buffer 本来就在 GPU 上，`eglSwapBuffers` 直接把 GPU 内存句柄交给硬件编码器，CPU 不参与。

---

## 3. 阶段 1 —— 仅占位的 VideoSink 空类

### 3.1 本阶段目标
能编译、能 `new RemoteVideoRecorder(ctx, sharedContext)` 调用，`VideoTrack.addSink(recorder)` 不报 ClassCastException。

### 3.2 完整代码

```java
package com.example.chapter07;

import android.content.Context;

import org.webrtc.EglBase;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * 远端视频录制器：通过 VideoSink 接收 WebRTC 远端视频帧，编码保存为 MP4。
 * 当前阶段：仅占位，暂不实际录制。
 */
public class RemoteVideoRecorder implements VideoSink {

    private final Context mContext;
    private final EglBase.Context mSharedContext;

    public RemoteVideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 暂不处理
    }
}
```

### 3.3 加了什么 + 为什么

| 字段 / 方法 | 作用 |
|------------|------|
| `implements VideoSink` | 让 WebRTC 的 `VideoTrack.addSink()` 接受我们 |
| `mContext` | Android 上下文，将来 `MediaCodec` / 文件操作要用 |
| `mSharedContext` | WebRTC 的 EGL 上下文，将来如果要共享纹理时用 |
| `onFrame` 空实现 | 接口要求；不实现就编译不过 |

### 3.4 阶段 1 后能干嘛
- 编译通过
- 把 recorder 挂到 VideoTrack 上不会崩
- 但来帧什么都丢，没有日志也没有数据

### 3.5 一切为啥要从这里开始
WebRTC 的视频管线是"观察者模式"——你必须先实现 `VideoSink`，才能接收到任何东西。在你拿到哪怕一帧视频之前，**接口契约**就得先写对。

---

## 4. 阶段 2 —— 加常量 + onFrame 打印日志

### 4.1 本阶段目标
让 `onFrame` 打印出视频帧的宽高、旋转、时间戳等元数据，**确认链路通到我们了**。

### 4.2 遇到的新问题

**问题 1：怎么知道来帧了？**
- 在 `onFrame` 里 `Log.i`。但 WebRTC 一秒 30 帧，不能每帧都打，要节流。
- 用 `frame.getRotatedWidth() / getRotatedHeight()` 拿旋转后的尺寸（默认 90° 时这两个值是交换过的）。

**问题 2：录制参数定多少？**
- 实际录制前先把硬编码参数写出来，方便后面阶段直接用：
  - 编码格式 `video/avc` (H.264)
  - 帧率 30 fps
  - I 帧间隔 1 秒
  - 码率 2 Mbps
  - 默认分辨率 1280×720（首次来帧时被覆盖）

### 4.3 完整代码（到阶段 2 为止）

```java
package com.example.chapter07;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import org.webrtc.EglBase;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class RemoteVideoRecorder implements VideoSink {

    private static final String TAG = "RemoteVideoRecorder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int BIT_RATE = 2_000_000;

    // 视频尺寸，第一帧来时被实际分辨率覆盖
    private int mVideoWidth = 1280;
    private int mVideoHeight = 720;

    private final Context mContext;
    private final EglBase.Context mSharedContext;

    public RemoteVideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (frame == null) return;
        // 节流：每秒打一行
        long now = System.currentTimeMillis();
        if (now - mLastLogTime < 1000) return;
        mLastLogTime = now;

        Log.i(TAG, "onFrame: " + frame.getRotatedWidth() + "x"
                + frame.getRotatedHeight()
                + ", rot=" + frame.getRotation()
                + ", ts=" + frame.getTimestampNs());
    }

    private long mLastLogTime = 0;
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 4 个常量 | 把"录制参数"集中在一处，方便日后调 |
| `mVideoWidth/Height` 默认值 | 启动录制时编码器要确定分辨率 |
| `onFrame` 日志 + 节流 | 验证链路：先确认我们收到帧了，再考虑处理 |
| `getRotatedWidth/Height` | 注意是"旋转后"的尺寸，对横屏录制非常重要 |

### 4.5 阶段 2 后能干嘛
- 在 logcat 看到 `onFrame: 1280x720, rot=0, ts=12345...` 这样的输出
- **如果不打 → 说明链路根本不到我们**，回头查 `VideoTrack.addSink(recorder)` 是否调用

### 4.6 调试技巧
- `frame.getRotation()` 常见值：0（正）、90（手机竖屏拿横屏录制时）、180、270
- 不要用 `getWidth()` / `getHeight()` 直接当编码器分辨率，必须用 `getRotatedWidth/Height`，否则输出会颠倒

---

## 5. 阶段 3 —— 初始化 MediaCodec H.264 编码器

### 5.1 本阶段目标
能在 `startRecording` 里配置并启动 H.264 编码器，**虽然还没有实际喂数据**。

### 5.2 遇到的新问题

**问题 1：编码器输入怎么选？**
- 本阶段先试**最简单的 ByteBuffer 模式**：`COLOR_FormatYUV420SemiPlanar` 或 `COLOR_FormatYUV420Flexible`，后面阶段再切 Surface。
- 不过为了尽早暴露"未来要走 Surface"的事实，本阶段我们就**直接配置 Surface 输入模式**（`COLOR_FormatSurface` + `createInputSurface()`），即使现在还没有 EGL，先把 API 走通。

**问题 2：怎么确定编码器支持 Surface 输入？**
- 不用手动判断：`createEncoderByType(MIME_TYPE)` + `createInputSurface()` 就能用，硬件编码器一定支持 Surface 输入。

**问题 3：怎么管理"开始录制"的状态？**
- 加 `mIsRecording` 标志位，`isRecording()` getter，启动时检查重复启动。

### 5.3 完整代码（到阶段 3 为止）

```java
package com.example.chapter07;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class RemoteVideoRecorder implements VideoSink {

    private static final String TAG = "RemoteVideoRecorder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int BIT_RATE = 2_000_000;

    private int mVideoWidth = 1280;
    private int mVideoHeight = 720;

    // ===== 编码器 =====
    private MediaCodec mMediaCodec;
    private Surface mEncoderSurface;

    // ===== 状态 =====
    private volatile boolean mIsRecording = false;
    private String mOutputPath;

    private final Context mContext;
    private final EglBase.Context mSharedContext;

    public RemoteVideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }

    public boolean isRecording() { return mIsRecording; }

    /**
     * 开始录制。本阶段：仅初始化 MediaCodec；EGL/Muxer 还没接上。
     */
    public boolean startRecording(String outputPath) {
        if (mIsRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }
        this.mOutputPath = outputPath;

        try {
            // ===== 1. 配置 MediaFormat =====
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            // ===== 2. 创建并启动编码器 =====
            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mMediaCodec.createInputSurface();
            mMediaCodec.start();

            mIsRecording = true;
            Log.i(TAG, "MediaCodec started, surface=" + mEncoderSurface);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            releaseInternal();
            return false;
        }
    }

    /**
     * 释放资源（暂简单处理）
     */
    private void releaseInternal() {
        if (mMediaCodec != null) {
            try { mMediaCodec.release(); } catch (Exception ignore) {}
            mMediaCodec = null;
        }
        mIsRecording = false;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 暂不处理
    }
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mMediaCodec` | 持有编码器 |
| `mEncoderSurface` | 后面 OpenGL 渲染的目标 |
| `mIsRecording` volatile | 跨线程可读，WebRTC 线程要读它判断是否来帧 |
| `mOutputPath` | 输出文件路径 |
| `MediaFormat` 配置 | 告诉编码器分辨率 / 码率 / 帧率 / I 帧间隔 |
| `COLOR_FormatSurface` | **关键**：告诉编码器"我要用 Surface 输入" |
| `createInputSurface()` | 创建 GL 渲染目标 |
| `releaseInternal()` | 启动失败时回收已经创建的编码器 |

### 5.5 阶段 3 后能干嘛
- `startRecording()` 返回 true，编码器在跑
- `mEncoderSurface` 已经有有效句柄，但目前没人往它上面画 → MediaCodec 内部 buffer 队列空着，不会编码出帧
- 如果你的手机不支持 H.264 编码，会抛 `MediaCodec.CodecException`——查 `Build.VERSION.SDK_INT` 与设备是否老旧

### 5.6 经验教训
**为什么现在就开始用 `COLOR_FormatSurface` 而不是先用 ByteBuffer 模式？**
- ByteBuffer 模式意味着你要自己 `codec.getInputBuffer(idx)` 喂 YUV，性能差；
- 我们的目标是走 GPU 通路，越早切到 Surface 模式越省事；
- `createInputSurface()` 在没有 EGL 的情况下也能成功——它只是返回一个 `Surface` 对象，渲染是后面阶段的事。

---

## 6. 阶段 4 —— 切换到 Surface 输入模式

### 6.1 本阶段目标
本阶段只是**巩固对 Surface 输入模式的理解**，并不真的渲染（EGL 还没建）。

### 6.2 遇到的新问题

**问题：怎么"喂"一帧到 Surface？**
- 答案：OpenGL 渲染 + `eglSwapBuffers`。**不是**直接写 byte[]。
- 换句话说：从阶段 4 开始，**没有"喂数据"这个动作**。要让编码器跑起来，必须先做 EGL + OpenGL（阶段 6-9）。
- 所以本阶段代码其实和阶段 3 几乎一样，但读者要开始建立"数据是通过 GPU 渲染进去的"的认知。

### 6.3 阶段 4 后能干嘛
- 心智模型：编码器在等一个 Surface 上有新内容；我们还没"画"，它就不编码。
- 接下来要建 EGL。

### 6.4 为什么这个阶段独立存在
**很多新手在阶段 3 就想"那我把 YUV byte[] 直接写进 Surface 行不行"** —— 不行，Surface 不是 byte[] 容器。要建立这个认知需要单独一个阶段。

---

## 7. 阶段 5 —— 初始化 MediaMuxer（MP4 容器）

### 7.1 本阶段目标
让 `startRecording` 同时初始化 `MediaMuxer`，把输出文件的容器先准备好。

### 7.2 遇到的新问题

**问题 1：Muxer 现在 start 吗？**
- **不能**。Muxer 必须先 `addTrack()` 再 `start()`，而 `addTrack()` 需要 `MediaFormat`，那是 `MediaCodec` 第一次 `dequeueOutputBuffer` 返回 `INFO_OUTPUT_FORMAT_CHANGED` 时才给的。
- 所以 Muxer 此时只能 `new` 出来，**留到阶段 13 才 start**。

**问题 2：Muxer 输出目录不存在怎么办？**
- 手动 `mkdirs()`。MediaMuxer 不会自动建目录。

**问题 3：MuXer 的状态字段提前定义吗？**
- 定义，否则阶段 13 写代码时要回头加字段。提前加上 `mMediaMuxer`、`mVideoTrackIndex`、`mMuxerStarted`。

### 7.3 完整代码（到阶段 5 为止 —— 增量为 `MediaMuxer` 部分）

在字段区增加：
```java
// ===== Muxer =====
private MediaMuxer mMediaMuxer;
private int mVideoTrackIndex = -1;
private boolean mMuxerStarted = false;
```

`startRecording` 末尾、设置 `mIsRecording = true` **之前**，插入：
```java
// ===== 3. 初始化 MediaMuxer（暂不 start） =====
File outputFile = new File(outputPath);
File dir = outputFile.getParentFile();
if (dir != null && !dir.exists()) {
    dir.mkdirs();
}
mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

`releaseInternal` 末尾增加：
```java
if (mMediaMuxer != null) {
    try { mMediaMuxer.release(); } catch (Exception ignore) {}
    mMediaMuxer = null;
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mMediaMuxer` | 持有 MP4 容器对象 |
| `mVideoTrackIndex` | 视频轨道的索引，writeSampleData 时用 |
| `mMuxerStarted` | 防止重复 start / 重复 addTrack |
| `mkdirs()` | 容错：很多用户传 `/sdcard/record/` 路径但中间目录不存在 |
| Muxer 不 start | 必须等 MediaCodec 给 output format |

### 7.5 阶段 5 后能干嘛
- `startRecording` 创建了编码器 + MP4 文件对象
- Muxer 现在**未启动**，写入文件还没产生
- 文件已被创建（空文件），后面阶段会填入数据

### 7.6 关键经验
**为什么 Muxer 不能先 start？**
- MP4 容器要求"先知道所有轨道的格式，再开始写入"。
- 视频轨道的格式（码率、profile、SPS/PPS）由 MediaCodec 在第一次 dequeueOutputBuffer 时通过 `INFO_OUTPUT_FORMAT_CHANGED` 告诉我们。
- 所以**必须先 codec 出第一帧 → 拿到 output format → addTrack → muxer.start()**。

---

## 8. 阶段 6 —— 初始化 EGL（Display / Config / Context）

### 8.1 本阶段目标
把 EGL 三件套（Display / Config / Context）建好。**这一步没有 EGLSurface**——Surface 留到阶段 7。

### 8.2 遇到的新问题

**问题 1：为什么 EGL 要自己建？WebRTC 不是有 EglBase 吗？**
- WebRTC 的 `EglBase` 用来做"渲染到自己的渲染器"，但它的 Context 绑的是 WebRTC 的渲染线程；
- MediaCodec 需要**我们的线程持有 EGL Context**，把帧画到 MediaCodec 的输入 Surface；
- 所以**必须自己再建一份 EGL**，不能直接复用 WebRTC 的 `mSharedContext`（那个 Context 绑死的 Surface 不一样）。

**问题 2：Display 是单例吗？**
- 同一进程内 `eglGetDisplay(EGL_DEFAULT_DISPLAY)` 返回的 `EGLDisplay` 是单例，多次调用安全。

**问题 3：Config 属性怎么选？**
- 8 bit RGBA + OpenGL ES 2.0 + PBuffer（占位用）。
- `EGL_RENDERABLE_TYPE = EGL_OPENGL_ES2_BIT` 锁死 GL ES 2.0。

**问题 4：Context 版本？**
- `EGL_CONTEXT_CLIENT_VERSION = 2`（GL ES 2.0）。
- MediaCodec 配套 EGL 必须 ≥ ES 2.0，但实际只需要 2.0 就能干完所有事。

### 8.3 完整代码（到阶段 6 为止 —— 在 `initEGL` 拆成两段，本阶段只有 Display/Config/Context）

字段区增加：
```java
// ===== EGL =====
private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
```

新增 `initEGLDisplayAndContext()` 方法：
```java
private void initEGLDisplayAndContext() {
    // ===== 1) Display =====
    mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
        throw new RuntimeException("Unable to get EGL14 display");
    }

    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        throw new RuntimeException("Unable to initialize EGL14");
    }

    // ===== 2) Config =====
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
    if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0,
            configs, 0, 1, numConfigs, 0)) {
        throw new RuntimeException("Unable to find suitable EGLConfig");
    }

    // ===== 3) Context =====
    int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
    };
    mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0],
            EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
    if (mEglContext == EGL14.EGL_NO_CONTEXT) {
        throw new RuntimeException("Unable to create EGLContext");
    }

    Log.i(TAG, "EGL Display/Config/Context ready");
}
```

在 `startRecording` 中调用（先把 `mEncoderSurface = mMediaCodec.createInputSurface()` 后的位置）：
```java
initEGLDisplayAndContext();
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mEglDisplay` | EGL 顶层句柄 |
| `mEglContext` | GL 状态机容器（线程私有） |
| `eglGetDisplay` + `eglInitialize` | 把 EGL 库初始化 |
| `eglChooseConfig` | 选 RGBA8888 + ES2 的像素格式 |
| `eglCreateContext` | 建一个 ES 2.0 上下文 |
| `EGL_NO_CONTEXT` 作 share_context | 我们不与 WebRTC 共享 Context（理由见问题 1） |

### 8.5 阶段 6 后能干嘛
- EGL 三件套就位，但 `eglMakeCurrent` 还没调（没有 Surface）
- 调任何 GL 函数都会因"没有 current context"而崩溃或无效
- 本阶段末必须过渡到阶段 7

### 8.6 经验教训
**EGL_NO_CONTEXT vs mSharedContext**
- 选 `EGL_NO_CONTEXT` 表示"不与任何 Context 共享 GL 资源（纹理、buffer）"。
- 如果选 `mSharedContext`，意味着可以与 WebRTC 共享纹理——但代价是 GL 状态相互影响，调试噩梦。
- 我们这里不需要共享纹理（每帧都重新上传），所以用 `EGL_NO_CONTEXT` 干净。

---

## 9. 阶段 7 —— 把 EGLSurface 绑到 MediaCodec 输入

### 9.1 本阶段目标
建一个 `EGLSurface` 指向 `mEncoderSurface`，并 `eglMakeCurrent` 绑定到当前线程。

### 9.2 遇到的新问题

**问题 1：Window Surface 和 PBuffer Surface 区别？**
- `eglCreatePbufferSurface`：离屏渲染，结果留在 GPU 内存
- `eglCreateWindowSurface(surface)`：渲染到一个具体的 `Surface`（窗口或 MediaCodec 输入）
- 我们要 Window Surface，参数就是 `mEncoderSurface`。

**问题 2：为什么 `eglMakeCurrent(d, s, s, c)` 写两次 surface？**
- 第二、三个参数分别是"读 surface"和"写 surface"。
- 我们读和写都到同一个（MediaCodec 输入），所以传两次。

**问题 3：绑到哪个线程？**
- 当前阶段我们假设 `startRecording` 在主线程调用，所以 `eglMakeCurrent` 把当前主线程与 EGL 绑定。
- **但这是错的！** WebRTC 视频帧是在 WebRTC 自己的线程回调的，后面阶段 10 我们会改成在独立 HandlerThread 上绑 EGL。
- 本阶段先把"EGL Surface 能创建、能绑"跑通，线程的事留到阶段 10。

### 9.3 完整代码（到阶段 7 为止 —— 完成整个 `initEGL`）

字段区增加：
```java
private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
```

把阶段 6 的 `initEGLDisplayAndContext` 扩展为完整的 `initEGL`：

```java
private void initEGL() {
    // Display + Config + Context（阶段 6）
    mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
        throw new RuntimeException("Unable to get EGL14 display");
    }
    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        throw new RuntimeException("Unable to initialize EGL14");
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
    if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0,
            configs, 0, 1, numConfigs, 0)) {
        throw new RuntimeException("Unable to find suitable EGLConfig");
    }

    int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
    };
    mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0],
            EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
    if (mEglContext == EGL14.EGL_NO_CONTEXT) {
        throw new RuntimeException("Unable to create EGLContext");
    }

    // ===== 新增: Window Surface 绑到 MediaCodec 输入 =====
    int[] surfaceAttribs = { EGL14.EGL_NONE };
    mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0],
            mEncoderSurface, surfaceAttribs, 0);
    if (mEglSurface == EGL14.EGL_NO_SURFACE) {
        throw new RuntimeException("Unable to create EGLSurface");
    }

    // ===== 新增: makeCurrent =====
    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
        throw new RuntimeException("Unable to make EGL context current");
    }

    Log.i(TAG, "EGL fully initialized");
}
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mEglSurface` 字段 | 持有绑定 MediaCodec 的 EGLSurface |
| `eglCreateWindowSurface(mEncoderSurface, ...)` | 把 MediaCodec 输入 Surface 包装成 EGLSurface |
| `eglMakeCurrent` | 当前线程就可以调 GL 函数了 |

### 9.5 阶段 7 后能干嘛
- 在 `startRecording` 调用栈内（即主线程）调任何 `GLES20.*` 函数都生效
- `eglSwapBuffers` 已经能把"当前 GL 输出"送到 MediaCodec
- **但还没有 shader、纹理**——画出来是黑屏
- 没有 `glClear` 也会显示未定义内容

### 9.6 调试技巧
- `eglGetError()` 在任何 EGL 失败时立刻调，返回非 `EGL_SUCCESS` 表示哪一步错
- `eglMakeCurrent` 失败最常见原因：之前的 Context 没解绑（必须在另一个线程上解绑才能在当前线程绑新的）

---

## 10. 阶段 8 —— 写 OpenGL Shader（YUV→RGB）

### 10.1 本阶段目标
编译 vertex + fragment shader，link 成 program，**拿到 attribute/uniform 句柄**。

### 10.2 遇到的新问题

**问题 1：为什么需要 YUV→RGB shader？**
- MediaCodec 输入 Surface 是 RGB 格式（具体是 RGBA8888）。
- WebRTC 给的是 YUV420 planar。
- GPU 上做转换比 CPU 上做快得多，所以用 fragment shader。

**问题 2：vertex shader 干啥？**
- 几乎不做事：把 4 个角点位置传过去，把 4 个纹理坐标传过去。
- `gl_Position = aPosition;` 把标准化设备坐标直接输出（4 个角点正好覆盖全屏）。

**问题 3：怎么缓存 attribute / uniform 句柄？**
- `glGetAttribLocation(program, "aPosition")` 给一次就缓存。
- **不要在每帧 `drawArrays` 前再调**——`glGetUniformLocation` 内部有字符串比较，是热点性能陷阱。

### 10.3 完整代码（到阶段 8 为止 —— 新增 `initGLResources`）

字段区增加（OpenGL 句柄）：

```java
private int mProgramId = 0;
private int mPositionHandle = -1;
private int mTexCoordHandle = -1;
private int mYTexHandle = -1;
private int mUTexHandle = -1;
private int mVTexHandle = -1;

private FloatBuffer mVertexBuffer;
private FloatBuffer mTexCoordBuffer;

// 顶点坐标（4 个角点的 NDC 坐标）
private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  // 左下
         1.0f, -1.0f,  // 右下
        -1.0f,  1.0f,  // 左上
         1.0f,  1.0f   // 右上
};

// 纹理坐标（注意 Y 轴翻转，对应 YUV 原始方向）
private static final float[] TEXTURE_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
};
```

新增 `initGLResources()`：

```java
private void initGLResources() {
    // ===== Vertex Shader =====
    String vertexShaderCode =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // ===== Fragment Shader (YUV → RGB) =====
    String fragmentShaderCode =
            "precision mediump float;\n" +
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
            "    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0),\n" +
            "                        clamp(b, 0.0, 1.0), 1.0);\n" +
            "}\n";

    int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
    int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

    mProgramId = GLES20.glCreateProgram();
    GLES20.glAttachShader(mProgramId, vertexShader);
    GLES20.glAttachShader(mProgramId, fragmentShader);
    GLES20.glLinkProgram(mProgramId);

    mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
    mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
    mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
    mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
    mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");

    // 准备顶点 Buffer
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

    Log.i(TAG, "GL resources ready, programId=" + mProgramId);
}

private int loadShader(int type, String shaderCode) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, shaderCode);
    GLES20.glCompileShader(shader);
    return shader;
}
```

在 `startRecording` 末尾调用：
```java
initGLResources();
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| YUV→RGB fragment shader | GPU 上做颜色空间转换 |
| vertex shader | 几乎透明，把 4 个角的纹理坐标传给 fragment |
| `loadShader` helper | 编译 shader 的标准 3 步：create/source/compile |
| `glLinkProgram` | 把两个 shader 链成 program |
| 缓存 attribute/uniform 句柄 | 每帧少 5 次字符串查找 |
| `ByteBuffer.allocateDirect().order(nativeOrder()).asFloatBuffer()` | **关键**：必须是 direct + native order，GL 才能直接读 |

### 10.5 阶段 8 后能干嘛
- Shader 已编译链接，program id 非 0
- 句柄都拿到了，理论上可以 `glUseProgram(mProgramId)` 干活
- 但还没有纹理对象（glGenTextures），所以现在 `glDrawArrays` 还是黑屏

### 10.6 经验教训
**为什么纹理坐标 Y 要翻转？**
- WebRTC 的 YUV 数据约定 `Y=0` 在顶部（从上到下扫描）。
- OpenGL 纹理坐标 `t=0` 在底部（NDC 坐标 Y 轴向上）。
- 直接 `t = texCoord.t` 会得到"上下颠倒"的图像。
- 我们用 `TEXTURE_COORDS = {0,1, 1,1, 0,0, 1,0}`（注意第二列是 1,1,0,0）显式翻转。

---

## 11. 阶段 9 —— 纹理上传 + drawFrame

### 11.1 本阶段目标
写一个 `drawFrame(byte[] y, byte[] u, byte[] v, w, h)` 方法，能把一帧 YUV 上传并画到编码器 Surface。

### 11.2 遇到的新问题

**问题 1：怎么上传 byte[] 到 GL 纹理？**
- `glTexImage2D(target, level, internalformat, w, h, border, format, type, ByteBuffer.wrap(data))`
- 注意参数：`format=GL_LUMINANCE`（Y/U/V 都是单通道），`type=GL_UNSIGNED_BYTE`
- **每次都 `glTexImage2D` 而不是 `glTexSubImage2D`**：因为我们每帧分辨率可能变化，全量重传最简单

**问题 2：Y/U/V 三个平面的纹理格式？**
- `GL_LUMINANCE`：单通道灰度（OpenGL ES 2.0 仍支持）
- 也可以用 `GL_RED`（GL ES 3.0+）；我们这里用 `GL_LUMINANCE` 兼容老 GL ES 2.0

**问题 3：纹理环绕和过滤？**
- `GL_CLAMP_TO_EDGE`：超出边界的采样取最近像素（YUV 平面采样必备，否则会取到另一边）
- `GL_LINEAR`：双线性过滤，画面平滑
- 三个平面都用相同设置

**问题 4：怎么保证 Surface 上的内容被 MediaCodec 取走？**
- **`eglSwapBuffers` 才是触发编码的瞬间**。光 `glDrawArrays` 不够。
- swap 之后 MediaCodec 才能 dequeueOutputBuffer 拿到这一帧

### 11.3 完整代码（到阶段 9 为止 —— 新增 `drawFrame`）

字段区增加：
```java
private int[] mYTexIds = new int[1];
private int[] mUTexIds = new int[1];
private int[] mVTexIds = new int[1];
```

`initGLResources` 末尾增加：
```java
GLES20.glGenTextures(1, mYTexIds, 0);
GLES20.glGenTextures(1, mUTexIds, 0);
GLES20.glGenTextures(1, mVTexIds, 0);
```

新增 `drawFrame`：

```java
private void drawFrame(byte[] yData, byte[] uData, byte[] vData,
                       int width, int height) {
    // 重新 makeCurrent（防御性，其它线程可能解绑了）
    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
        Log.e(TAG, "makeCurrent failed: " + EGL14.eglGetError());
        return;
    }

    GLES20.glViewport(0, 0, width, height);
    GLES20.glClearColor(0, 0, 0, 1);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GLES20.glUseProgram(mProgramId);

    int uvWidth = (width + 1) / 2;
    int uvHeight = (height + 1) / 2;

    // ===== Y 平面 =====
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(yData));
    setTexParams();
    GLES20.glUniform1i(mYTexHandle, 0);

    // ===== U 平面 =====
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(uData));
    setTexParams();
    GLES20.glUniform1i(mUTexHandle, 1);

    // ===== V 平面 =====
    GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(vData));
    setTexParams();
    GLES20.glUniform1i(mVTexHandle, 2);

    // ===== 顶点属性 =====
    GLES20.glEnableVertexAttribArray(mPositionHandle);
    GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

    GLES20.glEnableVertexAttribArray(mTexCoordHandle);
    GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

    // ===== 绘制 =====
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

    // ===== 提交给 MediaCodec =====
    EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
}

private void setTexParams() {
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
}
```

为了能跑通测试，可以临时在 `onFrame` 里手造一帧（仅阶段 9 测试用）：
```java
@Override
public void onFrame(VideoFrame frame) {
    if (!mIsRecording) return;
    // 测试桩：构造一帧单色
    int w = mVideoWidth, h = mVideoHeight;
    int ySize = w * h, uvSize = (w / 2) * (h / 2);
    byte[] y = new byte[ySize];  // 全 0（黑）
    byte[] u = new byte[uvSize]; Arrays.fill(u, (byte) 128);
    byte[] v = new byte[uvSize]; Arrays.fill(v, (byte) 128);
    drawFrame(y, u, v, w, h);
}
```

### 11.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 3 个纹理 id 数组 | Y/U/V 各一张 |
| `glGenTextures` | 分配 GPU 纹理对象 |
| `setTexParams` | 统一参数设置（Y/U/V 都用同样环绕/过滤） |
| `ByteBuffer.wrap(byte[])` | 把 Java byte[] 临时包成 NIO ByteBuffer（每帧都新建，会 GC；可优化为复用但非必须） |
| `GL_TRIANGLE_STRIP, 0, 4` | 4 个顶点画一个矩形 |
| `eglSwapBuffers` | **真正触发 MediaCodec 编码** |
| 防御性 `makeCurrent` | 防止其它线程解绑导致当前线程没 context |

### 11.5 阶段 9 后能干嘛
**第一个可见效果的版本！** 阶段 9 测试桩在 onFrame 里画全黑，理论上：
- MediaCodec 收到帧
- `dequeueOutputBuffer` 能返回有数据的 index
- 但 Muxer 还没 start，编码数据还没写入文件

### 11.6 常见踩坑
**坑 1：`GL_LUMINANCE` 在 GL ES 3.0 被 deprecated**
- GL ES 2.0 仍然支持，没问题
- 想要兼容 GL ES 3.0 用 `GL_RED`；但 Android 9 以下很多设备只支持 ES 2.0，所以保留 `GL_LUMINANCE`

**坑 2：`glTexImage2D` 第二个 `format`（internalformat）和第七个（format）必须匹配**
- 我们都传 `GL_LUMINANCE`，OK
- 如果传成 `GL_LUMINANCE_ALPHA`，内存布局变化会渲染花屏

---

## 12. 阶段 10 —— 独立渲染线程 HandlerThread

### 12.1 本阶段目标
把"所有 EGL/GL 操作"挪到独立线程上，避免阻塞 WebRTC 线程。

### 12.2 遇到的新问题

**问题 1：为什么不能继续在主线程？**
- WebRTC 的 `onFrame` 在 WebRTC 的 worker thread 回调；
- 主线程绝不能被 GPU 操作阻塞（会卡 UI）；
- EGL Context 绑定是**线程私有**的——即使后面想换线程，必须在新线程重新 `eglMakeCurrent`（不能跨线程使用）。

**问题 2：为什么用 HandlerThread 而不是普通 Thread？**
- HandlerThread 自带 Looper，可以 `post(Runnable)` 把任务排队；
- 后期 `processFrames` 这种"循环消费队列"的模型天然适合 Handler；
- quitSafely() 提供优雅退出（处理完已 post 的任务再停）。

**问题 3：startRecording 里怎么"等初始化完成"再返回？**
- HandlerThread 启动后 `getLooper()` 是异步返回的；
- 我们要 `eglMakeCurrent` 必须在渲染线程上做；
- 用 `CountDownLatch`：在渲染线程 `post` 一个 init 任务，init 完 countDown，主线程 await。

### 12.3 完整代码（到阶段 10 为止 —— 调整 `startRecording` 的 EGL 初始化顺序）

字段区增加：
```java
private HandlerThread mRenderThread;
private Handler mRenderHandler;
```

`startRecording` 改为：

```java
public boolean startRecording(String outputPath) {
    if (mIsRecording) {
        Log.w(TAG, "Already recording");
        return false;
    }
    this.mOutputPath = outputPath;

    try {
        // ===== 1. 编码器（主线程） =====
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();

        // ===== 2. 渲染线程 =====
        mRenderThread = new HandlerThread("RemoteRecorderThread");
        mRenderThread.start();
        mRenderHandler = new Handler(mRenderThread.getLooper());

        // ===== 3. 在渲染线程上初始化 EGL + GL =====
        final CountDownLatch initLatch = new CountDownLatch(1);
        mRenderHandler.post(() -> {
            try {
                initEGL();
                initGLResources();
            } catch (Exception e) {
                Log.e(TAG, "Init on render thread failed", e);
            } finally {
                initLatch.countDown();
            }
        });
        initLatch.await(); // 阻塞主线程直到渲染线程初始化完成

        // ===== 4. MediaMuxer =====
        File outputFile = new File(outputPath);
        File dir = outputFile.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mIsRecording = true;
        Log.i(TAG, "Recording started: " + outputPath);
        return true;

    } catch (Exception e) {
        Log.e(TAG, "startRecording failed", e);
        releaseInternal();
        return false;
    }
}
```

`releaseInternal` 增加：
```java
if (mRenderThread != null) {
    HandlerThread t = mRenderThread;
    mRenderThread = null;
    t.quitSafely();
}
```

### 12.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mRenderThread` | 持有渲染线程 |
| `mRenderHandler` | post 任务到渲染线程 |
| `initLatch` 同步 | 主线程等渲染线程初始化完 EGL 才返回 |
| `quitSafely` | 释放时优雅停线程 |

### 12.5 阶段 10 后能干嘛
- 所有 EGL/GL 操作都跑在 `RemoteRecorderThread` 上
- onFrame 现在如果在主线程调 `drawFrame` 会**崩**（无 EGL Context）
- 准备进入阶段 11：在 WebRTC 线程把 frame 塞进队列，渲染线程消费

### 12.6 经验教训
**为什么 init 用 latch 同步等？**
- 如果不 await，startRecording 直接返回 true，但 EGL 还没建好
- 此时用户立刻调 `stopRecording`，渲染线程还没初始化就会出乱
- await 让"录制开始"语义 = "EGL 就绪"

**为什么 onFrame 不能再直接调 drawFrame？**
- drawFrame 必须跑在有 EGL current 的线程，即 RemoteRecorderThread
- onFrame 在 WebRTC 线程，**没有 EGL**
- 必须把 frame 跨线程传递 → 阶段 11

---

## 13. 阶段 11 —— 帧队列 + 停止标志

### 13.1 本阶段目标
让 WebRTC 线程的 `onFrame` 把帧塞进线程安全队列，渲染线程消费。

### 13.2 遇到的新问题

**问题 1：用什么队列？**
- `ConcurrentLinkedQueue`：无界、线程安全、FIFO。
- 不用 `LinkedBlockingQueue`：因为录制是"丢帧优先"——堆太多帧反而延迟大。
- 队列无限增长也没事：渲染线程通常比 30fps 生产快。

**问题 2：怎么停止消费循环？**
- `AtomicBoolean mProcessFrames`。
- WebRTC 线程（stopRecording）置 false，渲染线程轮询退出。

**问题 3：是否要 mLock？**
- MediaCodec 和 MediaMuxer 是**非线程安全**的；
- 但我们把所有 codec / muxer 操作都放在渲染线程做（drainEncoder 也由 drawFrame 末尾触发），WebRTC 线程完全不碰；
- 所以**目前还不需要 mLock**。阶段 14 加 stopRecording 时再加。

### 13.3 完整代码（到阶段 11 为止）

字段区增加：
```java
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

private final ConcurrentLinkedQueue<VideoFrame> mFrameQueue = new ConcurrentLinkedQueue<>();
private final AtomicBoolean mProcessFrames = new AtomicBoolean(true);
```

`startRecording` 末尾（`mIsRecording = true` 之前）：
```java
mProcessFrames.set(true);
mRenderHandler.post(this::processFrames);
```

新增 `processFrames`：
```java
private void processFrames() {
    while (mProcessFrames.get()) {
        VideoFrame frame = mFrameQueue.poll();
        if (frame != null) {
            try {
                // 占位：阶段 12 会替换为完整 renderFrame
                frame.release();
            } catch (Exception e) {
                Log.e(TAG, "Render error", e);
            }
        } else {
            try { Thread.sleep(5); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    Log.i(TAG, "processFrames loop exited");
}
```

`onFrame` 改为：
```java
@Override
public void onFrame(VideoFrame frame) {
    if (!mIsRecording || frame == null) {
        if (frame != null) frame.release();  // 防止内存泄漏
        return;
    }
    mFrameQueue.add(frame);
}
```

### 13.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mFrameQueue` | WebRTC 线程 → 渲染线程的桥梁 |
| `mProcessFrames` | 渲染线程退出信号 |
| `processFrames()` | 渲染线程的工作循环 |
| `onFrame` 改 add | 不再在 WebRTC 线程做任何 GPU 工作 |
| `frame.release()` | **关键**：`VideoFrame` 是 native 引用计数对象，丢帧必须 release |

### 13.5 阶段 11 后能干嘛
- 队列长度是观察录制健康度的指标
- 队列持续增长 → 渲染线程处理不过来，需要降分辨率或排查
- `processFrames` 当前是"取到就释放"的占位，阶段 12 替换为真正渲染

### 13.6 关键经验
**`VideoFrame.release()` 必调**
- 漏调 → native 内存泄漏，最终 OOM
- 取出来不用也得 release
- 包括 onFrame 里 `frame == null` 的早返回分支：传入的 frame 仍归我们处置

---

## 14. 阶段 12 —— renderFrame：从 VideoFrame 抽出 I420 byte[]

### 14.1 本阶段目标
把 `VideoFrame` 转成 `byte[] yData, uData, vData`，然后调用 `drawFrame`。

### 14.2 遇到的新问题

**问题 1：VideoFrame 的 Buffer 是什么类型？**
- 默认是 `VideoFrame.Buffer` 接口，实现可能是 `I420Buffer`、`NV12Buffer`、`TextureBuffer` 等；
- 用 `frame.getBuffer().toI420()` 强制转 I420；这个调用**会拷贝数据**到新的 I420 buffer；
- 转出来的 `I420Buffer` 仍归我们管，必须 `release()`。

**问题 2：怎么拿 Y/U/V 的 byte[]？**
- `i420.getDataY()` / `getDataU()` / `getDataV()` 返回 `ByteBuffer`；
- 不是 `byte[]`，需要 `rewind()` + `get(byte[])` 拷贝到 Java 数组；
- 或者直接用 ByteBuffer 传给 `glTexImage2D`（更省内存），但为简单起见我们走 byte[]。

**问题 3：分辨率可能变怎么办？**
- 每帧检查 `i420.getWidth() / getHeight()`；
- 如果变化，更新 `mVideoWidth/Height`；
- **注意**：MediaCodec 在 start 后不能改分辨率，所以这里仅打印警告，不重建编码器。

**问题 4：UV 平面大小计算？**
- 严格：`(width + 1) / 2 * (height + 1) / 2`；
- 但因为 I420 平面存储就是 round-up 的，直接用这个公式。

### 14.3 完整代码（到阶段 12 为止）

替换 `processFrames` 中的占位：

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
            try { Thread.sleep(5); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}

private void renderFrame(VideoFrame frame) {
    if (frame == null || frame.getBuffer() == null) {
        Log.w(TAG, "Frame or buffer is null");
        if (frame != null) frame.release();
        return;
    }

    VideoFrame.I420Buffer i420 = null;
    try {
        i420 = frame.getBuffer().toI420();
    } catch (Exception e) {
        Log.e(TAG, "toI420 failed", e);
        frame.release();
        return;
    }
    if (i420 == null) {
        Log.w(TAG, "I420 buffer is null");
        frame.release();
        return;
    }

    int width = i420.getWidth();
    int height = i420.getHeight();

    // 分辨率变了仅打印，不重建编码器
    if (width != mVideoWidth || height != mVideoHeight) {
        Log.w(TAG, "Resolution changed: " + mVideoWidth + "x" + mVideoHeight
                + " -> " + width + "x" + height);
        mVideoWidth = width;
        mVideoHeight = height;
    }

    int ySize = width * height;
    int uvSize = ((width + 1) / 2) * ((height + 1) / 2);

    ByteBuffer yBuf = i420.getDataY();
    ByteBuffer uBuf = i420.getDataU();
    ByteBuffer vBuf = i420.getDataV();

    if (yBuf == null || uBuf == null || vBuf == null
            || yBuf.capacity() < ySize
            || uBuf.capacity() < uvSize
            || vBuf.capacity() < uvSize) {
        Log.w(TAG, "YUV buffer invalid");
        i420.release();
        frame.release();
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
    frame.release();

    drawFrame(yData, uData, vData, width, height);
}
```

### 14.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `frame.getBuffer().toI420()` | 强制拿到 planar I420 buffer |
| `i420.release()` | 引用计数 |
| `frame.release()` | 释放原始 VideoFrame |
| 每帧 alloc `byte[3]` | 简单可靠；可用 ThreadLocal 复用数组优化 |
| 分辨率变化检测 | 至少要打印日志，让你知道实际跑的是什么分辨率 |
| capacity 检查 | 防御性编程 |

### 14.5 阶段 12 后能干嘛
**真正的渲染链路打通了！**
- onFrame → 队列 → 渲染线程 → I420 → byte[] → drawFrame → EGL → 编码器
- 但 MediaCodec 编码器里的数据还没人取出来 → Muxer 没数据 → MP4 文件是空的
- 进入阶段 13

### 14.6 关键经验
**为什么 toI420 会拷贝？**
- 原始 buffer 可能是 NV12（UV 交错）或 GPU 纹理；
- YUV 三平面布局不一样，要拆开重组；
- 拷贝会带来一次内存拷贝开销，但对录制场景无影响（30fps 完全足够）。

---

## 15. 阶段 13 —— drainEncoderInternal：把编码数据写进 MP4

### 15.1 本阶段目标
每渲染一帧后（或 stopRecording 时），把 MediaCodec 输出 buffer 取出来，写进 MediaMuxer。

### 15.2 遇到的新问题

**问题 1：第一次 dequeueOutputBuffer 会发生什么？**
- 返回 `INFO_OUTPUT_FORMAT_CHANGED`（值为负数）；
- 这是编码器告诉我们"现在可以拿到 output format 了"；
- 此时调 `mMediaCodec.getOutputFormat()` 拿 SPS/PPS/profile 等信息；
- 调 `mMediaMuxer.addTrack(format)` 添加视频轨；
- 调 `mMediaMuxer.start()`。

**问题 2：CODEC_CONFIG 是什么？**
- 第一帧的输出通常带 `BUFFER_FLAG_CODEC_CONFIG` flag；
- 这是 SPS/PPS 等配置数据，**不要**写入 Muxer（Muxer 在 addTrack 时已记录）；
- 我们把 `bufferInfo.size = 0` 让它通过但不写。

**问题 3：什么时候 drain？**
- 每帧 `drawFrame` 后（不然 buffer 会堆）；
- `stopRecording` 时用 `signalEndOfInputStream()` + endOfStream=true 模式 drain 干净。

**问题 4：怎么取 output buffer？**
- `dequeueOutputBuffer(bufferInfo, timeoutUs)` 返回 index；
- index ≥ 0：拿到有效 buffer；
- `INFO_TRY_AGAIN_LATER`：暂时没数据；
- `INFO_OUTPUT_FORMAT_CHANGED`：上面问题 1。

### 15.3 完整代码（到阶段 13 为止）

新增：
```java
private void drainEncoderInternal(boolean endOfStream) {
    if (mMediaCodec == null) return;

    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    while (true) {
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) break;  // 非结束模式：没数据就退出
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (mMuxerStarted) {
                throw new RuntimeException("Format changed twice");
            }
            MediaFormat newFormat = mMediaCodec.getOutputFormat();
            Log.i(TAG, "Encoder output format changed: " + newFormat);
            mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
            mMediaMuxer.start();
            mMuxerStarted = true;
        } else if (outputBufferIndex >= 0) {
            ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);

            // CODEC_CONFIG 不写 Muxer
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0;
            }

            if (bufferInfo.size != 0 && mMuxerStarted) {
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, bufferInfo);
            }

            mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);

            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;  // 流结束
            }
        }
    }
}
```

`drawFrame` 末尾、`eglSwapBuffers` 之后增加：
```java
drainEncoderInternal(false);
```

### 15.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `INFO_OUTPUT_FORMAT_CHANGED` 分支 | 第一次 dequeue 时建立 muxer 轨道 |
| `BUFFER_FLAG_CODEC_CONFIG` 跳过 | SPS/PPS 不重复写入 |
| `writeSampleData` | 把编码后的 H.264 帧写入 MP4 |
| `endOfStream` 参数 | stopRecording 时用 true 模式排空 |
| `dequeueOutputBuffer(timeoutUs=10000)` | 10ms 超时；非阻塞 + 容错 |

### 15.5 阶段 13 后能干嘛
**真正的可录制版本！**
- 来帧 → 渲染 → 编码 → 写入 MP4
- 录制出来的 MP4 可以用 `ffplay` 或系统播放器播放
- 但 `stopRecording` 还没写，文件会不完整（最后一段编码数据没排空）

### 15.6 关键经验
**为什么必须 drain？**
- MediaCodec 内部有输出 buffer 队列；
- 不取走就堆满 → 编码器会停止接受输入 → 视频卡顿；
- 每帧取一次保持健康。

**Muxer 写入时序**
- 一定要先 addTrack + start，然后才能 writeSampleData；
- 没 start 就 write 会抛 `IllegalStateException`。

---

## 16. 阶段 14 —— stopRecording + 资源回收 + 同步锁 + 动态分辨率

### 16.1 本阶段目标
把当前零散的资源释放逻辑整合成完整的 `stopRecording` + `release` 流程，加入 `mLock` 保护 codec/muxer。

### 16.2 遇到的新问题

**问题 1：stopRecording 必须按顺序**
1. `mIsRecording = false`：onFrame 不再 add 进队列
2. `mProcessFrames.set(false)`：渲染线程退出循环
3. `mRenderThread.join(2000)`：等渲染线程结束
4. `signalEndOfInputStream`：通知编码器"后面没输入了"
5. `drainEncoderInternal(true)`：用 endOfStream 模式排干净
6. `releaseRecorderInternal`：释放所有资源
- **顺序错了任何一步都会出错**——例如先释放 EGL 再 drain，会因为没 context 而崩溃。

**问题 2：为什么需要 mLock？**
- `stopRecording` 通常在 UI 线程调（按钮回调）；
- 但 codec/muxer 已被渲染线程持有（drainEncoder 在渲染线程跑）；
- 必须在"join 渲染线程完成 → 操作 codec"之间加锁保护临界区。

**问题 3：动态分辨率要不要支持？**
- 完整支持需要重建编码器（MIME_TYPE/I_FRAME_INTERVAL 重配）；
- 当前实现仅**检测并警告**，不重建；
- 如果你一定要支持，把整个 `startRecording` 流程（包括 EGL/GL 重建）在分辨率变化时跑一遍。

**问题 4：release() 公开方法的用途？**
- 让外部在 Activity onDestroy / 兜底时调一次；
- 必须 idempotent（多次调用安全）；
- 内部用 `synchronized (mLock)` 保护。

### 16.3 完整代码（到阶段 14 为止 —— 即最终版本）

字段区追加：
```java
private final Object mLock = new Object();
```

把 `releaseInternal` 重命名为 `releaseRecorderInternal`，并完整实现：

```java
private void releaseRecorderInternal() {
    // 1. 渲染线程
    if (mRenderThread != null) {
        HandlerThread t = mRenderThread;
        mRenderThread = null;
        t.quitSafely();
    }

    // 2. EGL
    if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (mEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
        }
        if (mEglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
        }
        EGL14.eglTerminate(mEglDisplay);
    }
    mEglDisplay = EGL14.EGL_NO_DISPLAY;
    mEglContext = EGL14.EGL_NO_CONTEXT;
    mEglSurface = EGL14.EGL_NO_SURFACE;

    // 3. MediaCodec
    if (mMediaCodec != null) {
        try { mMediaCodec.stop(); mMediaCodec.release(); }
        catch (Exception e) { Log.e(TAG, "MediaCodec release error", e); }
        mMediaCodec = null;
    }

    // 4. MediaMuxer
    if (mMediaMuxer != null) {
        try {
            if (mMuxerStarted) mMediaMuxer.stop();
            mMediaMuxer.release();
        } catch (Exception e) { Log.e(TAG, "MediaMuxer release error", e); }
        mMediaMuxer = null;
        mMuxerStarted = false;
    }

    mVideoTrackIndex = -1;
    Log.i(TAG, "Recorder released");
}

public void release() {
    synchronized (mLock) {
        releaseRecorderInternal();
    }
}
```

`stopRecording` 完整实现：
```java
public void stopRecording() {
    synchronized (mLock) {
        if (!mIsRecording) return;
        mIsRecording = false;
    }

    // 在锁外 join 渲染线程（避免死锁）
    mProcessFrames.set(false);
    if (mRenderThread != null) {
        try {
            mRenderThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized (mLock) {
        try {
            if (mMediaCodec != null) {
                mMediaCodec.signalEndOfInputStream();
                drainEncoderInternal(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecording drain error", e);
        } finally {
            releaseRecorderInternal();
            Log.i(TAG, "Recording saved to: " + mOutputPath);
        }
    }
}
```

### 16.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mLock` | 保护 codec/muxer 非线程安全对象 |
| `releaseRecorderInternal` | 完整释放顺序：线程 → EGL → Codec → Muxer |
| `stopRecording` 三段式 | 通知停 → 等线程退 → 排空 → 释放 |
| `signalEndOfInputStream` | 让编码器产生 EOS 帧触发最终 muxer 关闭 |
| `release()` 公开 | Activity 兜底用 |
| `eglMakeCurrent(NO_*, NO_*)` | 解绑后才能销毁 Context |

### 16.5 阶段 14 后能干嘛
**与最终文件完全一致。**
- 完整可工作的远端视频录制器
- MP4 文件可以正常播放
- 安全释放不会泄漏 EGL Context / Codec / Muxer

### 16.6 关键经验
**为什么 stopRecording 拆成两个 synchronized 块？**
- 第一段（`mIsRecording = false`）必须立刻执行，onFrame 立即停止 add；
- `join(2000)` 不能持锁（join 阻塞时会让 WebRTC 线程抢不到锁）；
- 第二段（drain + release）必须在渲染线程退完后才能操作 codec。

**eglTerminate 必须先解绑**
- 否则 Context/Surface 销毁时会抛 `EGL_BAD_ACCESS`；
- 显式 `eglMakeCurrent(... NO_ ...)` 是 EGL 释放的标准前置步骤。

---

## 17. 完整最终代码对照

到阶段 14 为止，代码已经和文件 `app/src/main/java/com/example/chapter07/RemoteVideoRecorder.java` 完全一致。下面是按行号 + 阶段映射的关系：

| 阶段 | 文件中对应的内容 | 行号（参考） |
|------|----------------|------------|
| 1 | `package` / `import` / 类声明 + `VideoSink` + `mContext/mSharedContext` | 1-20, 30-32, 41-44, 105-115 |
| 2 | 常量 TAG/MIME_TYPE/帧率/码率 + 默认 1280×720 | 22-26 |
| 3 | `mMediaCodec/mEncoderSurface/mIsRecording/mOutputPath` + startRecording 主结构 | 56-64, 130-145 |
| 4 | （与阶段 3 同一段代码，建立 Surface 输入心智模型） | 141-144 |
| 5 | `mMediaMuxer/mVideoTrackIndex/mMuxerStarted` + startRecording 中 Muxer 初始化 | 58-60, 176-186 |
| 6 | `mEglDisplay/mEglContext` + `initEGL` 前半段 | 49-50, 354-394 |
| 7 | `mEglSurface` + `initEGL` 后半段 (Window Surface + makeCurrent) | 51, 395-410 |
| 8 | 6 个 GL 句柄 + VERTEX_COORDS/TEXTURE_COORDS + `initGLResources` + `loadShader` | 67-88, 414-475, 611-616 |
| 9 | `mYTexIds/mUTexIds/mVTexIds` + `drawFrame` + `setTexParams` | 66-72, 480-557, (setTexParams 内联) |
| 10 | `mRenderThread/mRenderHandler` + HandlerThread + initLatch 同步 | 97-98, 146-170 |
| 11 | `mFrameQueue/mProcessFrames` + `processFrames` + `onFrame` 改 add | 99-100, 208-225, 267-272 |
| 12 | `renderFrame` 完整实现 | 277-348 |
| 13 | `drainEncoderInternal` + drawFrame 末尾的 drain 调用 | 562-606, 555-557 |
| 14 | `mLock` + `stopRecording` + `release` + `releaseRecorderInternal` | 103, 230-262, 620-628, 633-696 |

---

## 18. 演进回顾表

| 阶段 | 加了什么 | 行数变化 | 解决的痛点 | 阶段末能干嘛 |
|------|---------|---------|-----------|------------|
| 1 | 空骨架 + `VideoSink` 空实现 | ~30 | 占位，明确输入 | 编译通过 |
| 2 | 常量 + onFrame 日志 | ~50 | 验证链路 | 看到帧元数据 |
| 3 | MediaCodec 编码器 init | ~140 | 拿到编码器 | 编码器在跑，无数据 |
| 4 | 心智模型：Surface 输入 | ~140 | 理解为何必须 OpenGL | （认知升级） |
| 5 | MediaMuxer init（不 start） | ~180 | 创建 MP4 文件 | 文件已创建，未写入 |
| 6 | EGL Display/Config/Context | ~230 | 准备 GL 环境 | EGL 三件套就位 |
| 7 | EGLSurface 绑 MediaCodec + makeCurrent | ~270 | 线程可调 GL | 当前线程能调 GL |
| 8 | Shader + program + 句柄 | ~340 | 编译 GL 程序 | program 就绪 |
| 9 | 纹理上传 + drawFrame + swapBuffers | ~410 | **第一次把帧画到编码器** | 编码器收到帧 |
| 10 | HandlerThread + initLatch | ~450 | 不阻塞主线程 | GL 跑在独立线程 |
| 11 | 帧队列 + 停止标志 + processFrames | ~480 | WebRTC 线程只投递 | 帧跨线程安全传递 |
| 12 | renderFrame + I420→byte[] | ~520 | 真正消费 VideoFrame | **最小可用版本** |
| 13 | drainEncoderInternal | ~580 | 编码数据写 MP4 | MP4 文件可播放 |
| 14 | stopRecording + release + mLock + 同步 | ~700 | 工程化 | 与当前文件完全一致 |

**关键里程碑**：
- **阶段 9** = 第一个 GPU 渲染到编码器的版本（但还没数据流动）
- **阶段 12** = 第一个完整渲染链路的版本（但 MP4 还是空的）
- **阶段 13** = 第一个能产出可播放 MP4 的版本
- **阶段 14** = 与当前文件完全一致，工程化完成

---

## 19. 常见报错对照表

写代码时遇到下面任何一种错误，对照表看哪里出问题：

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `addSink(recorder)` 编译/运行失败 | 1 | 没 implements VideoSink |
| `onFrame` 没被调用 | 1 | 没 `track.addSink(recorder)`，或 track 不是远端 track |
| `CodecException: ... configureCodec failed` | 3 | MediaFormat 参数不合法（分辨率/码率/帧率） |
| `createInputSurface` 返回 null | 3 | 编码器不支持 Surface 输入（很罕见） |
| `MediaMuxer` 抛 `IllegalStateException` on write | 13 | 没先 start；确保 addTrack 后 start |
| `eglGetDisplay` 返回 `EGL_NO_DISPLAY` | 6 | 设备不支持 EGL14（极老的设备） |
| `eglMakeCurrent` 失败 | 7/10 | 之前有别的 Context 没解绑；调用 `eglMakeCurrent(NO_*, NO_*, NO_*)` 先解绑 |
| `eglCreateWindowSurface` 返回 `EGL_NO_SURFACE` | 7 | `mEncoderSurface` 已失效或被外部 release |
| `glGetError = GL_INVALID_OPERATION` | 8/9 | 没 makeCurrent 或 shader 编译失败 |
| `Shader 编译失败` (link status = 0) | 8 | shader 源码写错；查 logcat `glCompileShader` 输出 |
| `glDrawArrays` 黑屏 | 9 | texture 没上传 / uniform 没绑 / 视口不对 |
| `MediaCodec dequeueOutputBuffer 一直返回 TRY_AGAIN_LATER` | 13 | EGL 没 swapBuffers；编码器没收到输入 |
| `MP4 文件能播但花屏` | 12 | YUV 三个平面顺序错（常见：U 和 V 写反）；或者 (w+1)/2 算错 |
| `MP4 文件不能播` | 13 | 没收到 EOS 帧（stopRecording 没 signalEndOfInputStream）或 SPS/PPS 被重复写入 |
| `VideoFrame 内存泄漏 (OOM)` | 11/12 | `frame.release()` 或 `i420.release()` 漏调；每个分支都要 release |
| `onFrame 后渲染线程没反应` | 11 | 队列是空的（确认 add 了）；或 mProcessFrames 是 false |
| `stopRecording 后进程崩` | 14 | 没等渲染线程退出就释放 codec；或 EGL 解绑顺序错 |
| `GL_LUMINANCE deprecated warning` | 9 | 用 GL ES 3.0 时的告警，无害；要彻底消除改用 `GL_RED`（GL ES 3.0+） |
| `Bad position N/M` 类 ByteBuffer 异常 | 12 | `ByteBuffer.wrap(yData)` 之前没 `rewind`；或 `get(byte[], 0, len)` 超 capacity |
| `eglTerminate` 抛 `EGL_BAD_ACCESS` | 14 | 没先 `eglMakeCurrent(NO_*, NO_*, NO_*)` 解绑 |
| `Frame resolution changed` 警告反复出现 | 14 | 远端在协商不同分辨率；当前实现不支持中途切换编码器分辨率 |
| 录制文件时长明显短于实际通话 | 13/14 | 没等渲染线程 join；drainEncoderInternal(true) 没在 join 之后跑 |

---

## 附录：相关文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/example/chapter07/RemoteVideoRecorder.java` | 本指南所构建的类，约 700 行最终形态 |
| `app/src/main/java/com/example/chapter07/WebRtcAudioTrackReflector.java` | 远端音频反射器（姊妹组件，读 PCM 的） |
| `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` | 远端音频录制器（用 AAC 编码 + MP4 封装） |
| `app/src/main/java/com/example/chapter07/LocalAudioRecorder.java` | 本地音频录制器 |
| `app/src/main/java/com/example/chapter07/WebRtcAudioRecordSamplesBridge.java` | 本地录音反射器（姊妹组件） |
| `app/src/main/java/com/example/chapter07/peer.java` | 在 Builder / 按钮回调处实例化 Recorder 的入口 |
| `readme/record_video/README.md` | 远端视频录制整体实现总览（含 EGL 多线程模型详解） |
| `readme/record_video/GLES_VERSION_PROOF.md` | 项目支持的 GLES 版本证明 |
| `readme/record_audio/WebRtcAudioTrackReflector_从零实现指南.md` | 姊妹指南（10 阶段） |

---

## 附录：与音频录制指南的对照

| 维度 | 音频指南 | 视频指南（本文件） |
|------|---------|-------------------|
| 总阶段数 | 10 | 14 |
| 核心机制 | Java 反射 | MediaCodec Surface 输入 + OpenGL |
| 关键 API | `Class.forName` / `getDeclaredField` | `eglMakeCurrent` / `glDrawArrays` / `eglSwapBuffers` |
| 跨线程 | 单线程足够 | 必须独立渲染线程（EGL Context 线程私有） |
| 数据格式 | PCM byte[] | YUV 三个平面 byte[] |
| 编码器输入 | byte[] | Surface |
| 同步复杂度 | 单锁 | 锁 + HandlerThread + CountDownLatch |
| 资源回收 | 简单 try/finally | 严格顺序：线程→EGL→Codec→Muxer |

**为什么视频更复杂？**
1. Surface 输入模式本身就要求 OpenGL
2. OpenGL 又要求 EGL Context
3. EGL Context 又是线程私有
4. → 必须有独立线程 → 必须有跨线程同步 → 必须有复杂释放顺序

理解了"为什么必须走 GL"，后面 14 个阶段的拼装顺序就是**唯一合理的选择**。
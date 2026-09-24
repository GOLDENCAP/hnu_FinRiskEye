# 从零实现 LocalAudioRecorder —— 渐进式构建指南

> **目标读者**：了解 MediaCodec / MediaMuxer 基本用法、对 AAC 编码和 PCM 数据流有概念的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/LocalAudioRecorder.java`（422 行）从"空类"演进到当前规模。这是一个**音频录制器**：从 `WebRtcAudioRecordSamplesBridge` 拿 PCM 帧，编码成 AAC，封装到 MP4 文件。
>
> **核心数据流**：`PCM 队列 → 累积器 → MediaCodec(AAC) → MediaMuxer(MP4)`

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空类 + 构造方法](#3-阶段-1--空类--构造方法)
4. [阶段 2 —— 加常量（AAC 配置）](#4-阶段-2--加常量aac-配置)
5. [阶段 3 —— 加字段（队列 + 状态）](#5-阶段-3--加字段队列--状态)
6. [阶段 4 —— 加字段（MediaCodec/MediaMuxer）](#6-阶段-4--加字段mediacodecmediamuxer)
7. [阶段 5 —— 加字段（工作线程 + 同步）](#7-阶段-5--加字段工作线程--同步)
8. [阶段 6 —— 实现 startRecording（骨架）](#8-阶段-6--实现-startrecording骨架)
9. [阶段 7 —— startRecording: 探测采样率](#9-阶段-7--startrecording-探测采样率)
10. [阶段 8 —— startRecording: 配置 MediaCodec](#10-阶段-8--startrecording-配置-mediacodec)
11. [阶段 9 —— startRecording: 配置 MediaMuxer](#11-阶段-9--startrecording-配置-mediamuxer)
12. [阶段 10 —— startRecording: 启动工作线程](#12-阶段-10--startrecording-启动工作线程)
13. [阶段 11 —— 实现 probeSampleRate](#13-阶段-11--实现-probesamplerate)
14. [阶段 12 —— 实现 readAndEncodeAudio（主循环骨架）](#14-阶段-12--实现-readandencodeaudio主循环骨架)
15. [阶段 13 —— readAndEncodeAudio: 累积器逻辑](#15-阶段-13--readandencodeaudio-累积器逻辑)
16. [阶段 14 —— readAndEncodeAudio: 编码循环](#16-阶段-14--readandencodeaudio-编码循环)
17. [阶段 15 —— readAndEncodeAudio: 尾部 flush](#17-阶段-15--readandencodeaudio-尾部-flush)
18. [阶段 16 —— 实现 encodeData](#18-阶段-16--实现-encodedata)
19. [阶段 17 —— 实现 drainEncoder](#19-阶段-17--实现-drainencoder)
20. [阶段 18 —— 实现 stopRecording](#20-阶段-18--实现-stoprecording)
21. [阶段 19 —— 实现 releaseRecorderInternal](#21-阶段-19--实现-releaserecorderinternal)
22. [完整最终代码对照](#22-完整最终代码对照)
23. [演进回顾表](#23-演进回顾表)
24. [常见报错对照表](#24-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**从 `WebRtcAudioRecordSamplesBridge` 拿到 10ms 一帧的 PCM 数据，编码成 AAC，封装到 MP4 文件**。

为什么这么做？因为 Android 直接录 AAC 需要自己开 AudioRecord（和 WebRTC 抢麦克风）。
这个类**复用 WebRTC 的麦克风**，不抢，再编码成 MP4 文件。

---

## 2. 前置知识速查

### 2.1 Android MediaCodec

- Android 的硬编解码器 API
- 工作模式：输入 buffer → 编码 → 输出 buffer
- 用 `dequeueInputBuffer` / `queueInputBuffer` / `dequeueOutputBuffer` 操作

### 2.2 Android MediaMuxer

- 把编码后的音频/视频帧封装到容器（MP4）
- 必须先 `start()` 才能 `writeSampleData`

### 2.3 AAC LC

- AAC Low Complexity
- 最常用的 AAC 编码
- 一帧 1024 samples（mono 16bit = 2048 bytes）

### 2.4 AAC 帧累积原理

- 输入 PCM 必须是 1024 samples 的整数倍
- 否则编码器会拒绝
- 所以**累积器攒够整数倍才送编码**

### 2.5 跨线程同步

- `volatile` 标志：跨线程读最新值
- `AtomicBoolean`：原子切换标志
- `synchronized`：保护 MediaCodec（不是线程安全的）

---

## 3. 阶段 1 —— 空类 + 构造方法

### 3.1 本阶段目标
让这个类能被构造。

### 3.2 完整代码

```java
package com.example.chapter07;

public class LocalAudioRecorder {
    private final WebRtcAudioRecordSamplesBridge mBridge;

    public LocalAudioRecorder(WebRtcAudioRecordSamplesBridge bridge) {
        this.mBridge = bridge;
    }
}
```

### 3.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mBridge` 字段 | 持有 bridge 引用 |
| 构造方法 | 注入 bridge |

---

## 4. 阶段 2 —— 加常量（AAC 配置）

### 4.1 本阶段目标
定义 AAC 编码器的配置常量。

### 4.2 遇到的新问题
**问题 1：AAC 配置有哪些？**
- MIME_TYPE：AAC
- 采样率：实际探测
- 声道数：mono
- 比特率：128kbps
- Profile：AAC LC（低复杂度）

**问题 2：AAC_FRAME_BYTES = 2048 是怎么算的？**
- 1024 samples/帧 × 2 bytes/sample（16bit）= 2048 bytes

### 4.3 完整代码

```java
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
private static final int CHANNEL_COUNT = 1;
private static final int BIT_RATE = 128000;
private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
private static final int AAC_FRAME_BYTES = 2048;
```

---

## 5. 阶段 3 —— 加字段（队列 + 状态）

### 5.1 本阶段目标
加 PCM 帧队列和录制状态字段。

### 5.2 完整代码

```java
import java.util.concurrent.ConcurrentLinkedQueue;

private final ConcurrentLinkedQueue<byte[]> mPcmQueue = new ConcurrentLinkedQueue<>();
private volatile boolean mIsRecording = false;
private String mOutputPath;
```

### 5.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mPcmQueue` | PCM 帧队列（bridge → recorder） |
| `mIsRecording` | 录制状态（volatile 跨线程） |
| `mOutputPath` | 输出文件路径 |

---

## 6. 阶段 6 —— 加字段（MediaCodec/MediaMuxer）

### 6.1 本阶段目标
加 MediaCodec、MediaMuxer、轨道索引相关字段。

### 6.2 完整代码

```java
import android.media.MediaCodec;
import android.media.MediaMuxer;

private MediaCodec mMediaCodec;
private MediaMuxer mMediaMuxer;
private int mAudioTrackIndex = -1;  // -1 表示未添加
private boolean mMuxerStarted = false;
```

---

## 7. 阶段 7 —— 加字段（工作线程 + 同步）

### 7.1 本阶段目标
加工作线程、Handler、同步锁、统计字段。

### 7.2 完整代码

```java
import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.atomic.AtomicBoolean;

private HandlerThread mRecordThread;
private Handler mRecordHandler;
private final AtomicBoolean mProcessRecording = new AtomicBoolean(false);

private final Object mLock = new Object();

private int mActualSampleRate = 0;
private long mTotalBytesRecorded = 0;
private int mFramesEncoded = 0;
```

---

## 8. 阶段 8 —— 实现 startRecording（骨架）

### 8.1 本阶段目标
实现 `startRecording` 的入口逻辑：状态判断 + 输出路径保存。

### 8.2 遇到的新问题
**问题 1：为什么要 `synchronized (mLock)`？**
- 答：`MediaCodec` 和 `MediaMuxer` 不是线程安全的。
- 加锁保护它们。
- `startRecording` 和 `stopRecording` 都用同一把锁。

**问题 2：为什么先判 `mIsRecording`？**
- 防止重复启动。

### 8.3 完整代码（骨架）

```java
public boolean startRecording(String outputPath) {
    synchronized (mLock) {
        if (mIsRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }

        this.mOutputPath = outputPath;
        mTotalBytesRecorded = 0;
        mFramesEncoded = 0;
        mPcmQueue.clear();
        mBridge.resetState();

        try {
            // 探测采样率 + 配置 MediaCodec + ...（下几步）
            // ...
            mIsRecording = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            mBridge.setActiveSink(null);
            releaseRecorderInternal();
            return false;
        }
    }
}
```

---

## 9. 阶段 9 —— startRecording: 探测采样率

### 9.1 本阶段目标
等待第一次 PCM 回调，确定采样率。

### 9.2 遇到的新问题
**问题 1：为什么要探测？**
- WebRTC 实际采样率可能不是 48000（虽然通常是）。
- 我们要等第一帧回调拿到真实值，再配置 MediaCodec。

**问题 2：等多久？**
- 当前实现：最多 500ms
- 超时后用默认 48000（兜底）

### 9.3 完整代码

```java
int probe = probeSampleRate(500);
if (probe <= 0) {
    Log.w(TAG, "No callback received in 500ms, using default 48000Hz");
    mActualSampleRate = 48000;
} else {
    mActualSampleRate = probe;
}
```

---

## 10. 阶段 10 —— startRecording: 配置 MediaCodec

### 10.1 本阶段目标
配置 AAC 编码器。

### 10.2 完整代码

```java
MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, mActualSampleRate, CHANNEL_COUNT);
format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
mMediaCodec.start();
```

---

## 11. 阶段 11 —— startRecording: 配置 MediaMuxer

### 11.1 本阶段目标
创建 MP4 封装器。

### 11.2 完整代码

```java
import java.io.File;

File outputFile = new File(outputPath);
File dir = outputFile.getParentFile();
if (dir != null && !dir.exists()) {
    dir.mkdirs();
}
mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

---

## 12. 阶段 12 —— startRecording: 启动工作线程

### 12.1 本阶段目标
启动录制线程，把主循环 post 进去。

### 12.2 完整代码

```java
mRecordThread = new HandlerThread("LocalAudioRecorderThread");
mRecordThread.start();
mRecordHandler = new Handler(mRecordThread.getLooper());

mIsRecording = true;
mProcessRecording.set(true);
mRecordingStartTime = System.currentTimeMillis();

mBridge.setActiveSink(mPcmQueue);
mRecordHandler.post(this::readAndEncodeAudio);
```

### 12.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `HandlerThread` | 专用工作线程 |
| `setActiveSink(mPcmQueue)` | 让 bridge 开始入队 |
| `mRecordHandler.post(...)` | 启动主循环 |

---

## 13. 阶段 11 —— 实现 probeSampleRate

### 13.1 本阶段目标
阻塞探测 bridge 的采样率（最多等 timeoutMs）。

### 13.2 完整代码

```java
private int probeSampleRate(int timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
        int sr = mBridge.getSampleRate();
        if (sr > 0) {
            return sr;
        }
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    return -1;
}
```

---

## 14. 阶段 12 —— 实现 readAndEncodeAudio（主循环骨架）

### 14.1 本阶段目标
实现录制线程的主循环。

### 14.2 完整代码（骨架）

```java
private void readAndEncodeAudio() {
    long presentationTimeUs = 0;
    byte[] accum = new byte[AAC_FRAME_BYTES * 4];
    int accumLen = 0;

    while (mProcessRecording.get()) {
        byte[] frame = mPcmQueue.poll();
        if (frame == null) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            continue;
        }
        // 累积 + 编码（下几步）
    }

    // 退出时 flush 残余数据
    if (accumLen > 0) { /* ... */ }
}
```

---

## 15. 阶段 13 —— readAndEncodeAudio: 累积器逻辑

### 15.1 本阶段目标
把每帧 PCM 累积到 accum，直到够 2 个 AAC 帧（4096 bytes）才编码。

### 15.2 完整代码

```java
if (accumLen + frame.length <= accum.length) {
    System.arraycopy(frame, 0, accum, accumLen, frame.length);
    accumLen += frame.length;
}

int totalSamples = accumLen / 2;
presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate;

while (accumLen >= AAC_FRAME_BYTES * 2) {
    int sendBytes = AAC_FRAME_BYTES * 2;
    encodeData(accum, sendBytes, presentationTimeUs);
    mFramesEncoded++;
    System.arraycopy(accum, sendBytes, accum, 0, accumLen - sendBytes);
    accumLen -= sendBytes;
    mTotalBytesRecorded += sendBytes;
}
```

### 15.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `accum` 累积器 | 攒够整数倍 AAC 帧 |
| `arraycopy` 滑动窗口 | 编码后剩余数据移到前面 |
| 时间戳计算 | 用作 MP4 帧时间戳 |

---

## 16. 阶段 14 —— readAndEncodeAudio: 编码循环

已在阶段 13 中。

---

## 17. 阶段 15 —— readAndEncodeAudio: 尾部 flush

### 17.1 本阶段目标
主循环退出后，把残余 PCM 补零对齐并编码。

### 17.2 完整代码

```java
if (accumLen > 0) {
    int pad = (AAC_FRAME_BYTES - (accumLen % AAC_FRAME_BYTES)) % AAC_FRAME_BYTES;
    if (accumLen + pad <= accum.length) {
        for (int i = 0; i < pad; i++) accum[accumLen + i] = 0;
        int totalSamples = (accumLen + pad) / 2;
        presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate;
        encodeData(accum, accumLen + pad, presentationTimeUs);
        mFramesEncoded++;
        mTotalBytesRecorded += accumLen + pad;
    }
}
```

---

## 18. 阶段 16 —— 实现 encodeData

### 18.1 本阶段目标
把 PCM 送入 MediaCodec 编码。

### 18.2 完整代码

```java
private void encodeData(byte[] pcmData, int length, long timestamp) {
    if (mMediaCodec == null) return;

    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
    if (inputBufferIndex >= 0) {
        ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
        if (inputBuffer != null) {
            inputBuffer.clear();
            inputBuffer.put(pcmData, 0, length);
            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length, timestamp, 0);
        }
    }
    drainEncoder(false);
}
```

---

## 19. 阶段 17 —— 实现 drainEncoder

### 19.1 本阶段目标
排空 MediaCodec 输出，把 AAC 帧写入 MP4。

### 19.2 完整代码（已在文件中）

要点：
- `INFO_TRY_AGAIN_LATER`：非结束模式 break
- `INFO_OUTPUT_FORMAT_CHANGED`：添加轨道到 muxer，启动 muxer
- 正常 buffer：写 sample 到 muxer，释放 buffer
- `BUFFER_FLAG_END_OF_STREAM`：退出循环

---

## 20. 阶段 18 —— 实现 stopRecording

### 20.1 本阶段目标
发送 EOS，刷新编码器，停止 muxer，释放资源。

### 20.2 完整代码（核心部分）

```java
public void stopRecording() {
    synchronized (mLock) {
        if (!mIsRecording) return;
        mIsRecording = false;
    }

    mBridge.setActiveSink(null);
    mProcessRecording.set(false);

    try {
        if (mRecordThread != null) {
            mRecordThread.join(3000);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }

    synchronized (mLock) {
        try {
            if (mMediaCodec != null) {
                int idx = mMediaCodec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    mMediaCodec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drainEncoder(true);
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            }
            if (mMediaMuxer != null) {
                if (mMuxerStarted) mMediaMuxer.stop();
                mMediaMuxer.release();
                mMediaMuxer = null;
                mMuxerStarted = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during stopRecording", e);
        }
    }

    mPcmQueue.clear();
}
```

---

## 21. 阶段 19 —— 实现 releaseRecorderInternal

### 21.1 本阶段目标
统一释放资源的内部方法（出错时用）。

### 21.2 完整代码（核心部分）

```java
private void releaseRecorderInternal() {
    try {
        if (mMediaCodec != null) {
            try { mMediaCodec.stop(); } catch (Exception ignored) {}
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mMediaMuxer != null) {
            if (mMuxerStarted) {
                try { mMediaMuxer.stop(); } catch (Exception ignored) {}
            }
            mMediaMuxer.release();
            mMediaMuxer = null;
            mMuxerStarted = false;
        }
        if (mRecordThread != null) {
            try { mRecordThread.quitSafely(); } catch (Exception ignored) {}
            mRecordThread = null;
        }
    } catch (Exception e) {
        Log.e(TAG, "releaseRecorderInternal error", e);
    }
}
```

---

## 22. 完整最终代码对照

到阶段 19 为止，与 `app/src/main/java/com/example/chapter07/LocalAudioRecorder.java` 完全一致。所有代码已在前面 19 个阶段给出。

---

## 23. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空类 + 构造 | 8 | 基本骨架 |
| 2 | AAC 常量 | 14 | 配置参数 |
| 3 | 队列 + 状态 | 22 | PCM 流 |
| 4 | MediaCodec/Muxer | 28 | 编码器 |
| 5 | 工作线程 | 38 | 异步执行 |
| 6 | startRecording 骨架 | 60 | 启动入口 |
| 7 | 探测采样率 | 70 | 自适应 |
| 8 | 配置 MediaCodec | 85 | AAC 编码 |
| 9 | 配置 MediaMuxer | 100 | MP4 输出 |
| 10 | 启动工作线程 | 115 | 主循环 |
| 11 | probeSampleRate | 130 | 探测逻辑 |
| 12 | readAndEncodeAudio 骨架 | 160 | 主循环 |
| 13 | 累积器 | 200 | 攒够整数倍 |
| 14-15 | 编码循环 + flush | 230 | 完整编码 |
| 16 | encodeData | 250 | 送编码器 |
| 17 | drainEncoder | 290 | 输出处理 |
| 18 | stopRecording | 350 | 优雅停止 |
| 19 | releaseRecorderInternal | 380 | 错误恢复 |

**关键里程碑**：
- 阶段 7 = 自适应采样率
- 阶段 13 = 整数倍 AAC 帧累积
- 阶段 18 = 完整生命周期

---

## 24. 常见报错对照表

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `MediaCodec.INFO_TRY_AGAIN_LATER` 一直返回 | 14 | 输入 PCM 不够。检查采样率探测是否成功 |
| `IllegalStateException: MediaCodec released` | 17 | 编码器释放后再调用。要检查 null |
| MP4 文件只有几 KB | 18 | 没调 muxer.stop()。mMediaMuxer.stop() 必须在 release 前 |
| `Invalid AudioSampleRate` | 7 | 采样率不是合法值（8000/16000/22050/44100/48000）。检查探测结果 |
| 录音没声音 | 10 | mBridge 没 setActiveSink，或者 WebRTC 没启动 |
| AAC 解码报错 | 13 | accum 不是整数倍 AAC 帧。检查 flush 逻辑 |
| 应用崩在 `MediaCodec.dequeueInputBuffer` | 16 | 编码器已 stop。必须 start 才能 dequeue |

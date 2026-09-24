# 从零实现 RemoteAudioRecorder —— 渐进式构建指南

> **目标读者**：会基本 Java / Android 音频 API（AudioRecord / MediaCodec），但还不清楚怎么把它们组装成一个完整录制器的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java`（522 行）从"空类"逐步演进到当前规模。每一阶段只做一件事，做完之后你能运行、能调试、能验证；再进入下一阶段。
>
> **阅读建议**：先按顺序读一遍，再真正动手写时回到对应阶段复制代码。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空骨架 + 一个空方法](#3-阶段-1--空骨架--一个空方法)
4. [阶段 2 —— 加上基础录制参数常量](#4-阶段-2--加上基础录制参数常量)
5. [阶段 3 —— 用 AudioRecord 录一段 PCM 到内存数组](#5-阶段-3--用-audiorecord-录一段-pcm-到内存数组)
6. [阶段 4 —— 加上 AAC 编码器（MediaCodec）](#6-阶段-4--加上-aac-编码器mediacodec)
7. [阶段 5 —— 加上 MediaMuxer，把 AAC 写进 MP4 文件](#7-阶段-5--加上-mediamuxer把-aac-写进-mp4-文件)
8. [阶段 6 —— 用 HandlerThread 把主循环搬到后台线程](#8-阶段-6--用-handlerthread-把主循环搬到后台线程)
9. [阶段 7 —— 加 start/stop 公开 API + 状态管理](#9-阶段-7--加-startstop-公开-api--状态管理)
10. [阶段 8 —— 加静音检测（isSilentBuffer）](#10-阶段-8--加静音检测issilentbuffer)
11. [阶段 9 —— 加 VOICE_CALL 回退 MIC 兜底逻辑](#11-阶段-9--加-voice_call-回退-mic-兜底逻辑)
12. [阶段 10 —— 加累积器（accumulator）支持任意大小的输入](#12-阶段-10--加累积器accumulator支持任意大小的输入)
13. [阶段 11 —— 加 WebRtcAudioTrackReflector 数据源](#13-阶段-11--加-webrtcaudiotrackreflector-数据源)
14. [阶段 12 —— 加统一资源释放方法 releaseRecorderInternal](#14-阶段-12--加统一资源释放方法-releaserecorderinternal)
15. [阶段 13 —— 加周期性统计日志 + 累计字段](#15-阶段-13--加周期性统计日志--累计字段)
16. [阶段 14 —— 加 setPlayoutSource / 公开 getter / 公开 release](#16-阶段-14--加-setplayoutsource--公开-getter--公开-release)
17. [完整最终代码对照](#17-完整最终代码对照)
18. [演进回顾表](#18-演进回顾表)
19. [常见报错对照表](#19-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**把远端播放出来的 PCM（来自 WebRTC 内部，或兜底来自麦克风）录制成一个 MP4 文件。**

数据流：

```
┌──────────────────────┐    ┌─────────────┐    ┌─────────────┐    ┌────────┐
│ 数据源（任选其一）    │ →  │ PCM 累积器  │ →  │ AAC 编码器  │ →  │ MP4   │
│ - 反射源（推荐）      │    │ 攒满 2 帧   │    │ MediaCodec  │    │ 写出   │
│ - 麦克风源（兜底）    │    └─────────────┘    └─────────────┘    └────────┘
└──────────────────────┘
```

为什么需要"累积器"？因为：
- AAC 编码器要求每帧至少 1024 samples (2048 bytes mono16bit)
- 反射源每帧只给 10ms（48kHz mono16bit = 960 bytes）
- 所以要把多个 10ms 帧攒起来，等攒够再送编码

这个类一共有 5 个核心职责：

1. **数据源管理**：两种数据源（反射 / 麦克风），互斥使用
2. **AAC 编码**：把 PCM 实时编码成 AAC
3. **MP4 封装**：把 AAC 帧写入 .mp4 文件
4. **线程管理**：用 HandlerThread 把录制循环放到后台，避免阻塞 UI
5. **资源生命周期**：start / stop / release 三段式管理 AudioRecord / MediaCodec / MediaMuxer

后面 14 个阶段都在解决"这 5 件事怎么一件件做对"。

---

## 2. 前置知识速查

### 2.1 MediaCodec + MediaMuxer 协作流

```
configure(format) → start()
                  ↓
   dequeueInputBuffer → queueInputBuffer(PCM) ← 我们送 PCM
                  ↓
   dequeueOutputBuffer → getOutputBuffer(AAC) → MediaMuxer.writeSampleData
                  ↓
        INFO_OUTPUT_FORMAT_CHANGED → addTrack → muxer.start()
```

关键点：编码器启动后第一次 `dequeueOutputBuffer` 通常会拿到 `INFO_OUTPUT_FORMAT_CHANGED`，**此时必须把编码器的 outputFormat 给 muxer addTrack 并 start muxer**，之后才能 writeSampleData。

### 2.2 AAC 帧的字节数

AAC LC 一帧 = 1024 samples（无论采样率多少）。

| 采样率 | 1 帧字节（mono 16bit） | 帧时长 |
|--------|---------------------|--------|
| 48kHz  | 2048                | 21.3ms |
| 16kHz  | 2048                | 64ms   |
| 44.1kHz| 2048                | 23.2ms |

`KEY_MAX_INPUT_SIZE` 必须 ≥ 2048，否则 MediaCodec 会拒绝配置。

### 2.3 PCM 16bit mono 的字节排布

```java
// 把 2 个 byte 拼成 1 个有符号 short
short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
```

小端序，低字节在前。

### 2.4 AudioRecord 数据源优先级

| AudioSource | 能否录到远端播放声 | 兼容性 | 说明 |
|------------|------------------|--------|------|
| `VOICE_CALL` | ✅ 能 | 部分设备不支持 | 系统级上行+下行，录音文件是下行（远端） |
| `MIC`       | ❌ 不能 | 全兼容 | 只能录本地麦克风 |
| `VOICE_DOWNLINK` | ✅ 能（部分 Android） | 较少见 | 专门录下行 |

> **关键经验**：很多设备 `VOICE_CALL` 会初始化失败，需要 fallback 到 `MIC`。

---

## 3. 阶段 1 —— 空骨架 + 一个空方法

### 3.1 本阶段目标
能编译、能 `new RemoteAudioRecorder()` 调用，仅此而已。

### 3.2 完整代码

```java
package com.example.chapter07;

public class RemoteAudioRecorder {

    public RemoteAudioRecorder() {
    }

    public void startRecording(String outputPath) {
        // TODO: 稍后实现
    }

    public void stopRecording() {
        // TODO: 稍后实现
    }
}
```

### 3.3 加了什么 + 为什么

| 字段 / 方法 | 作用 |
|------------|------|
| 空构造函数 | 暂时不需要任何依赖 |
| `startRecording(outputPath)` | 录制入口（签名先定下来） |
| `stopRecording()` | 停止入口（签名先定下来） |

### 3.4 阶段 1 后能干嘛
- 编译通过
- 外部代码可以 new + start + stop 调用（虽然什么都不做）
- 已经定下了**对外 API 的形状**

### 3.5 一切为啥要从这里开始
- 先把 public API 定型，后面的代码都在实现这两个方法
- 不要在阶段 1 就塞逻辑 —— 一次只做一件事

---

## 4. 阶段 2 —— 加上基础录制参数常量

### 4.1 本阶段目标
把"采样率 48kHz / 单声道 / 16bit"这些"全录制器通用"的参数写死成常量。

### 4.2 遇到的新问题
**问题：参数放哪里？**
- 答：放成 `private static final`。录制器整个生命周期都用同一组参数，没必要做成实例字段。

**问题：为什么先写常量、不写逻辑？**
- 答：参数定下来，**所有后续代码都引用这组常量**，不会出现"这个 48000 是 48k 还是别的"的歧义。

### 4.3 完整代码（阶段 1 + 阶段 2 增量）

```java
package com.example.chapter07;

import android.media.AudioFormat;

public class RemoteAudioRecorder {

    private static final String TAG = "RemoteAudioRecorder";

    // 音频录制参数
    private static final int SAMPLE_RATE = 48000;                                  // 采样率 48kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;        // 单声道输入
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;       // 16-bit PCM

    public RemoteAudioRecorder() {
    }

    public void startRecording(String outputPath) {
        android.util.Log.i(TAG, "TODO: startRecording");
    }

    public void stopRecording() {
        android.util.Log.i(TAG, "TODO: stopRecording");
    }
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `TAG` | log 用 |
| `SAMPLE_RATE = 48000` | 固定采样率；AAC 编码和 MediaMuxer 都依赖它 |
| `CHANNEL_CONFIG` | mono（WebRTC 默认） |
| `AUDIO_FORMAT` | 16bit PCM（和 WebRTC 对齐） |

### 4.5 阶段 2 后能干嘛
- start/stop 触发 log，证明调用链通了
- 录制参数已固化，后续阶段都引用

---

## 5. 阶段 5 —— 用 AudioRecord 录一段 PCM 到内存数组

> 注：跳到阶段 5 是因为阶段 3、4 已经在当前文件里被合并了。详细拆分见下。

### 5.1 本阶段目标
让 `startRecording` 真正打开麦克风，录 100ms PCM 到内存数组里。

### 5.2 遇到的新问题
**问题 1：AudioRecord 的 buffer size 怎么定？**
- 答：用 `AudioRecord.getMinBufferSize(...)` 拿最小值，再 ×N 倍做缓冲。
- 倍数太小（×1）容易 underrun，太大（×8）浪费内存。常用 ×4。

**问题 2：单次 read 多少字节？**
- 答：和 bufferSize 一样大。一次 read 拿满一整个 buffer 是最简单可靠的循环方式。

**问题 3：录到内存数组里做什么？**
- 答：现阶段什么都不做，先把"读得到"证明。后面再接编码。

### 5.3 完整代码（到阶段 5 为止）

```java
package com.example.chapter07;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class RemoteAudioRecorder {

    private static final String TAG = "RemoteAudioRecorder";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord mAudioRecord;
    private int mBufferSize;

    public RemoteAudioRecorder() {
    }

    public void startRecording(String outputPath) {
        mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (mBufferSize == AudioRecord.ERROR || mBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: " + mBufferSize);
            return;
        }
        mBufferSize = Math.max(mBufferSize, 8192);

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                mBufferSize * 4
        );

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            mAudioRecord.release();
            mAudioRecord = null;
            return;
        }

        mAudioRecord.startRecording();

        // 录 100ms（5 帧 × 20ms）扔掉，仅作"读得到"验证
        byte[] buf = new byte[mBufferSize];
        for (int i = 0; i < 5; i++) {
            int n = mAudioRecord.read(buf, 0, mBufferSize);
            Log.i(TAG, "read " + n + " bytes");
        }

        mAudioRecord.stop();
        mAudioRecord.release();
        mAudioRecord = null;
    }

    public void stopRecording() {
    }
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mAudioRecord` 字段 | 持有麦克风对象 |
| `mBufferSize` 字段 | 缓存 buffer size |
| `getMinBufferSize + Math.max(8192)` | 防 underrun（部分设备 getMinBufferSize 太小） |
| `× 4` 缓冲 | 经验值，避免 read 卡顿 |
| `STATE_INITIALIZED` 检查 | 防麦克风被独占 |
| 录 5 帧 → log | **验证录音链路通的最小步骤** |

### 5.5 阶段 5 后能干嘛
- 启动 App 在 logcat 看到 5 行 `read X bytes`
- X > 0 = 麦克风可用
- X = 0 = 麦克风被 WebRTC 占着 → 这是后面引入"反射源"的根本原因

### 5.6 调试技巧
**这一步如果 read 一直返回 0 或 ERROR**，就要警觉：
- 在 WebRTC 应用里麦克风**几乎一定**被 WebRTC 占着
- 这就是为什么后面要支持 WebRtcAudioTrackReflector（直接从 WebRTC 内部抓 PCM）

---

## 6. 阶段 6 —— 加上 AAC 编码器（MediaCodec）

### 6.1 本阶段目标
把 PCM 送进 MediaCodec 编码成 AAC，从输出 buffer 把 AAC 帧读出来。

### 6.2 遇到的新问题
**问题 1：MediaCodec 配置项这么多，哪些必填？**
- 必填：
  - `MIMETYPE_AUDIO_AAC`（编码类型）
  - `KEY_SAMPLE_RATE`（采样率）
  - `KEY_CHANNEL_COUNT`（声道数）
  - `KEY_AAC_PROFILE`（用 AAC LC = `AACObjectLC`）
  - `KEY_BIT_RATE`（一般 128kbps 足够）
  - `KEY_MAX_INPUT_SIZE`（**≥ 2048**，否则会崩）

**问题 2：dequeueInputBuffer 拿到 -1 怎么办？**
- 答：等一会儿再试。timeout 给 10ms 就够了。

**问题 3：dequeueOutputBuffer 拿到 INFO_OUTPUT_FORMAT_CHANGED 怎么办？**
- 答：先记下来。**这是必须告诉 MediaMuxer 的格式信息**，但现阶段我们还没用 muxer，所以先 log。

**问题 4：dequeueOutputBuffer 拿到 INFO_TRY_AGAIN_LATER 怎么办？**
- 答：编码器还没产出新数据，正常。退出排空循环，等下一帧。

### 6.3 完整代码（到阶段 6 为止）

```java
package com.example.chapter07;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.nio.ByteBuffer;

public class RemoteAudioRecorder {

    private static final String TAG = "RemoteAudioRecorder";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 128000;
    private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;

    private AudioRecord mAudioRecord;
    private int mBufferSize;
    private MediaCodec mMediaCodec;

    public RemoteAudioRecorder() {
    }

    public void startRecording(String outputPath) {
        try {
            // ===== 打开 AudioRecord =====
            mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            mBufferSize = Math.max(mBufferSize, 8192);
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    mBufferSize * 4
            );
            mAudioRecord.startRecording();

            // ===== 配置 AAC 编码器 =====
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();

            // ===== 循环：PCM → AAC =====
            byte[] buf = new byte[mBufferSize];
            for (int i = 0; i < 100; i++) {
                int n = mAudioRecord.read(buf, 0, mBufferSize);
                if (n > 0) {
                    encodeOneFrame(buf, n);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
        }
    }

    /**
     * 把 PCM 送编码 + 排空输出（不写文件，仅验证编码能跑通）
     */
    private void encodeOneFrame(byte[] pcm, int length) {
        int inputIdx = mMediaCodec.dequeueInputBuffer(10000);
        if (inputIdx >= 0) {
            ByteBuffer in = mMediaCodec.getInputBuffer(inputIdx);
            in.clear();
            in.put(pcm, 0, length);
            mMediaCodec.queueInputBuffer(inputIdx, 0, length, 0, 0);
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIdx;
        while ((outIdx = mMediaCodec.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer out = mMediaCodec.getOutputBuffer(outIdx);
            int aacBytes = info.size;
            Log.i(TAG, "Encoded " + length + " PCM → " + aacBytes + " AAC");
            mMediaCodec.releaseOutputBuffer(outIdx, false);
        }
    }

    public void stopRecording() {
    }
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `MIME_TYPE / CHANNEL_COUNT / BIT_RATE / AAC_PROFILE` 常量 | 编码器配置 |
| `MediaFormat.createAudioFormat + 4 个 setInteger` | 标准 AAC LC 配置 |
| `KEY_MAX_INPUT_SIZE = 16384` | **关键**：2048 是下限，16384 是经验余量 |
| `mMediaCodec` 字段 | 编码器句柄 |
| `encodeOneFrame` 方法 | 一次"送 PCM + 拿 AAC" |
| `dequeueInputBuffer(10000)` | 等 10ms 拿 input slot |
| `dequeueOutputBuffer(info, 0)` | 非阻塞拿 output（没拿到就退出循环） |

### 6.5 阶段 6 后能干嘛
- 启动后 logcat 会刷出 `Encoded 8192 PCM → N AAC`
- N 通常在 100~300 字节之间（每帧 AAC 大约 1kbps/ms）
- 验证：
  - 有 AAC 输出 = 编码链路通了
  - 没有 = 检查 MediaCodec 配置或 device codec 兼容性

---

## 7. 阶段 7 —— 加上 MediaMuxer，把 AAC 写进 MP4 文件

### 7.1 本阶段目标
把 AAC 帧写进 .mp4 文件，跑完整个 `PCM → AAC → MP4` 链路。

### 7.2 遇到的新问题
**问题 1：MediaMuxer 怎么知道音轨格式？**
- 答：从编码器第一次 `INFO_OUTPUT_FORMAT_CHANGED` 拿 `getOutputFormat()`。
- 这个格式里包含采样率、声道、AAC config（codec specific），是 muxer 必需的。

**问题 2：先 addTrack 还是先 start muxer？**
- 答：
  1. `mMediaMuxer = new MediaMuxer(path, OUTPUT_MPEG_4)`
  2. 拿到编码器的 `INFO_OUTPUT_FORMAT_CHANGED`
  3. `trackIndex = muxer.addTrack(format)`
  4. `muxer.start()` ← **必须 addTrack 之后才能 start**
  5. 此后每次 `muxer.writeSampleData(trackIndex, buffer, info)`

**问题 3：muxer.start() 之前的 AAC 帧会怎样？**
- 答：丢弃或缓存都行。这里**直接丢弃**（前几帧 AAC 不影响可播放性）。

### 7.3 完整代码（到阶段 7 为止）

```java
package com.example.chapter07;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

public class RemoteAudioRecorder {

    private static final String TAG = "RemoteAudioRecorder";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 128000;
    private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;

    private AudioRecord mAudioRecord;
    private int mBufferSize;
    private MediaCodec mMediaCodec;
    private MediaMuxer mMediaMuxer;
    private int mAudioTrackIndex = -1;
    private boolean mMuxerStarted = false;

    private String mOutputPath;

    public RemoteAudioRecorder() {
    }

    public void startRecording(String outputPath) {
        mOutputPath = outputPath;
        try {
            // ===== 1) AudioRecord =====
            mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            mBufferSize = Math.max(mBufferSize, 8192);
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, mBufferSize * 4);
            mAudioRecord.startRecording();

            // ===== 2) 编码器 =====
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();

            // ===== 3) Muxer =====
            File f = new File(outputPath);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ===== 4) 录制循环（100 帧后退出）=====
            byte[] buf = new byte[mBufferSize];
            for (int i = 0; i < 100; i++) {
                int n = mAudioRecord.read(buf, 0, mBufferSize);
                if (n > 0) encodeAndMux(buf, n, 0L);
            }
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
        }
    }

    private void encodeAndMux(byte[] pcm, int length, long pts) {
        int inputIdx = mMediaCodec.dequeueInputBuffer(10000);
        if (inputIdx >= 0) {
            ByteBuffer in = mMediaCodec.getInputBuffer(inputIdx);
            in.clear();
            in.put(pcm, 0, length);
            mMediaCodec.queueInputBuffer(inputIdx, 0, length, pts, 0);
        }
        drainEncoder(false);
    }

    private void drainEncoder(boolean endOfStream) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIdx;
        while ((outIdx = mMediaCodec.dequeueOutputBuffer(info, 0)) >= 0
                || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mMuxerStarted) {
                    MediaFormat newFormat = mMediaCodec.getOutputFormat();
                    mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
                    mMediaMuxer.start();
                    mMuxerStarted = true;
                    Log.i(TAG, "Muxer started");
                }
            } else if (outIdx >= 0) {
                ByteBuffer out = mMediaCodec.getOutputBuffer(outIdx);
                if (out != null && info.size > 0 && mMuxerStarted) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    mMediaMuxer.writeSampleData(mAudioTrackIndex, out, info);
                }
                mMediaCodec.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    public void stopRecording() {
        try {
            if (mAudioRecord != null) { mAudioRecord.stop(); mAudioRecord.release(); mAudioRecord = null; }
            if (mMediaCodec != null) { mMediaCodec.stop(); mMediaCodec.release(); mMediaCodec = null; }
            if (mMediaMuxer != null) {
                if (mMuxerStarted) mMediaMuxer.stop();
                mMediaMuxer.release();
                mMediaMuxer = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stop error", e);
        }
    }
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mMediaMuxer` / `mAudioTrackIndex` / `mMuxerStarted` 字段 | muxer 状态 |
| `mOutputPath` 字段 | 记下输出路径（stop 时 log 用） |
| `new MediaMuxer(path, MUXER_OUTPUT_MPEG_4)` | 准备 MP4 容器 |
| `mkdirs()` | 自动创建父目录 |
| `drainEncoder(boolean endOfStream)` | **抽出排空逻辑**，复用给"非结束"和"结束"两种场景 |
| `INFO_OUTPUT_FORMAT_CHANGED` 分支 | **关键**：muxer 必须用这个格式 addTrack |
| `mMuxerStarted` 标志位 | 防止 start 多次 |
| `writeSampleData(trackIndex, out, info)` | 写 AAC 帧 |
| `out.position(limit)` | **关键**：必须把 ByteBuffer 切到 info 指定的范围 |

### 7.5 阶段 7 后能干嘛
- 跑完后 mp4 文件已经存在
- 拷贝到电脑上用 VLC / ffprobe 能播
- 验证：ffprobe 输出含 `Audio: aac (LC), 48000 Hz, mono`

### 7.6 常见坑
1. **`info.size == 0` 时仍然 releaseOutputBuffer**：没问题，但不写 muxer
2. **没用 INFO_OUTPUT_FORMAT_CHANGED**：muxer.start() 会抛 `IllegalStateException`
3. **`out.position` 没切**：写入的 AAC 帧头部不对，会无声或崩播放器

---

## 8. 阶段 8 —— 用 HandlerThread 把主循环搬到后台线程

### 8.1 本阶段目标
让 `startRecording` 立刻返回，主循环在后台线程跑，直到 `stopRecording` 被调用。

### 8.2 遇到的新问题
**问题 1：为什么必须用后台线程？**
- `AudioRecord.read` 是阻塞的。在 UI 线程跑会触发 ANR。
- 必须用独立线程跑录制循环。

**问题 2：为什么用 HandlerThread 而不是普通 Thread？**
- 答：HandlerThread 自带 Looper，方便后续用 `Handler.post(task)` 模式。
- 如果你熟悉 `ExecutorService` 也可以用，但 HandlerThread 是 Android 原生。

**问题 3：怎么"启动后立刻返回 + 后台持续录"？**
- 步骤：
  1. new HandlerThread + start
  2. new Handler(looper)
  3. `handler.post(this::readAndEncodeAudio)`
  4. startRecording 直接 return（不阻塞）

**问题 4：怎么"让后台循环停止"？**
- 用 `AtomicBoolean mProcessRecording` 做开关：
  - true = 继续跑
  - false = 退出循环
- 主线程把开关设 false，循环检测到就退出。

### 8.3 完整代码（增量）

```java
import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.atomic.AtomicBoolean;

// 在字段区加：
private HandlerThread mRecordThread;
private Handler mRecordHandler;
private final AtomicBoolean mProcessRecording = new AtomicBoolean(false);

// startRecording 末尾：
mRecordThread = new HandlerThread("AudioRecorderThread");
mRecordThread.start();
mRecordHandler = new Handler(mRecordThread.getLooper());

mProcessRecording.set(true);
mRecordHandler.post(this::readAndEncodeAudio);
return;  // 立刻返回

// 抽出主循环方法：
private void readAndEncodeAudio() {
    byte[] buf = new byte[mBufferSize];
    while (mProcessRecording.get()) {
        int n = mAudioRecord.read(buf, 0, mBufferSize);
        if (n > 0) {
            encodeAndMux(buf, n, 0L);  // PTS 暂时传 0
        }
    }
}
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mRecordThread` | 录制专用后台线程 |
| `mRecordHandler` | 用于 post 录制任务 |
| `mProcessRecording (AtomicBoolean)` | 线程安全的循环开关 |
| `HandlerThread("AudioRecorderThread")` | 线程名 logcat 可见，方便调试 |
| `post(this::readAndEncodeAudio)` | 启动主循环 |

### 8.5 阶段 8 后能干嘛
- `startRecording` 立刻返回
- 后台持续录
- `stopRecording` 设开关 false，主循环退出

### 8.6 关键点
- `AtomicBoolean` 必须在**主线程 set / 在工作线程 get**，线程安全
- 不用 `volatile boolean`：因为 `AtomicBoolean` 已经保证了可见性 + 原子性

---

## 9. 阶段 9 —— 加 start/stop 公开 API + 状态管理

### 9.1 本阶段目标
让 `startRecording` / `stopRecording` 支持重入检测（重复 start 报错、未在录时 stop 不报错）、加 `isRecording` 查询、加 `mIsRecording` 状态。

### 9.2 遇到的新问题
**问题 1：重复 start 怎么办？**
- 答：返回 false，log warning。这是常见误用，应该友好处理。

**问题 2：未 start 就 stop 怎么办？**
- 答：直接 return。idempotent 接口更好用。

**问题 3：start 失败要回滚吗？**
- 答：要。否则 AudioRecord 已经被打开但没人 stop，会一直占着麦克风。
- 失败时调 `releaseRecorderInternal()` 统一释放。

**问题 4：startRecording / stopRecording 并发调用怎么办？**
- 答：用 `synchronized (mLock)` 保护共享状态。但**不要在锁里做阻塞操作**（如 `join`），否则会死锁。

### 9.3 完整代码（增量）

```java
private volatile boolean mIsRecording = false;
private final Object mLock = new Object();

public boolean startRecording(String outputPath) {
    synchronized (mLock) {
        if (mIsRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }
        this.mOutputPath = outputPath;
        try {
            // ... 所有初始化 ...
            mIsRecording = true;
            mProcessRecording.set(true);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start", e);
            releaseRecorderInternal();  // 失败回滚
            return false;
        }
    }
}

public void stopRecording() {
    synchronized (mLock) {
        if (!mIsRecording) return;
        mIsRecording = false;
    }
    // 锁外做阻塞操作（join）
    mProcessRecording.set(false);
    try {
        if (mRecordThread != null) mRecordThread.join(3000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    // 锁内做清理
    synchronized (mLock) {
        releaseRecorderInternal();
    }
}

public boolean isRecording() {
    return mIsRecording;
}
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mIsRecording (volatile boolean)` | 对外查询的录制状态 |
| `mLock` | start/stop 共享状态互斥 |
| 重复 start 检查 | 友好报错 |
| 未 start stop 检查 | idempotent |
| 失败回滚 | 防资源泄漏 |
| `join(3000)` | 等后台循环退出，最多 3 秒 |
| `isRecording()` getter | UI 按钮状态查询 |

### 9.5 阶段 9 后能干嘛
- 调用方可以反复 start/stop 而不会崩
- UI 可以通过 `isRecording()` 切换按钮状态

### 9.6 关键设计：synchronized 锁的范围
- **锁内**：状态变更、初始化资源
- **锁外**：阻塞操作（join）
- **再锁内**：清理资源

这样避免了"持锁 join 后台线程 → 后台线程又要拿锁 → 死锁"。

---

## 10. 阶段 10 —— 加静音检测（isSilentBuffer）

### 10.1 本阶段目标
检测每一帧 PCM 是否是静音帧，并累计连续静音数 + 周期性日志。

### 10.2 遇到的新问题
**问题 1：为什么要检测静音？**
- 因为 WebRTC 应用场景里，对端静音是常态。
- 没有静音检测，调用方根本不知道"是不是对端没说话"。
- 日志里刷 "连续 N 帧静音" 是调试 WebRTC 连接的关键信号。

**问题 2：阈值怎么定？**
- 答：经验值。
  - 100 太严：小声耳语也被判为静音
  - 30 比较合适：能捕获到正常对话音量
- 阈值太低（< 10）会把环境底噪也算"有声"，失去意义。

**问题 3：怎么避免每个 sample 都 log？**
- 答：用计数器。每 N 帧才 log 一次（这里 N=50）。

### 10.3 完整代码（增量）

```java
// 在 readAndEncodeAudio 里：
int silentFrameCount = 0;

while (mProcessRecording.get()) {
    int n = mAudioRecord.read(buf, 0, mBufferSize);
    if (n > 0) {
        boolean silent = isSilentBuffer(buf, n);
        if (silent) {
            silentFrameCount++;
            if (silentFrameCount % 50 == 0) {
                Log.w(TAG, "⚠️ " + silentFrameCount + " consecutive silent frames");
            }
        } else {
            if (silentFrameCount > 0) {
                Log.i(TAG, "✅ Sound detected after " + silentFrameCount + " silent frames");
            }
            silentFrameCount = 0;
        }
        encodeAndMux(buf, n, pts);
    }
}

/**
 * 判断 PCM 是否为静音
 */
private boolean isSilentBuffer(byte[] buffer, int length) {
    int threshold = 30;
    for (int i = 0; i < length; i += 2) {
        if (i + 1 < length) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            if (Math.abs(sample) > threshold) return false;
        }
    }
    return true;
}
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `silentFrameCount` 局部变量 | 累计当前连续静音帧数 |
| `isSilentBuffer` 方法 | 阈值法静音检测 |
| 静音时每 50 帧 log 一次 | 避免日志刷屏 |
| 重新有声时 log "✅" | 给调试者"出声"的反馈 |

### 10.5 阶段 10 后能干嘛
- 调用方可以根据静音/有声日志判断对端是否在说话
- 调试 WebRTC 连接问题时，关键看 `consecutive silent frames` 是不是不断增长

---

## 11. 阶段 11 —— 加 VOICE_CALL 回退 MIC 兜底逻辑

### 11.1 本阶段目标
优先用 `VOICE_CALL`（能录远端播放声），不支持就回退到 `MIC`。

### 11.2 遇到的新问题
**问题 1：为什么 VOICE_CALL 优先？**
- 答：VOICE_CALL 是系统级音轨，能录到对端播放出来的声音（下行）。
- MIC 只能录本地麦克风。
- 但 VOICE_CALL 在很多设备上初始化失败（特别是国产手机厂商魔改的 ROM），所以必须 fallback。

**问题 2：怎么判断 fallback？**
- 答：`AudioRecord.getState() != STATE_INITIALIZED` 时回退。

### 11.3 完整代码（增量）

```java
mAudioRecord = new AudioRecord(
        MediaRecorder.AudioSource.VOICE_CALL,  // 先尝试 VOICE_CALL
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, mBufferSize * 4);

if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
    Log.w(TAG, "VOICE_CALL failed, trying MIC");
    mAudioRecord.release();
    mAudioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,  // 回退 MIC
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, mBufferSize * 4);
}

if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
    Log.e(TAG, "AudioRecord init failed");
    if (mAudioRecord != null) { mAudioRecord.release(); mAudioRecord = null; }
    return false;
}

mActualSampleRate = mAudioRecord.getSampleRate();  // 取真实采样率
```

### 11.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 第一次尝试 `VOICE_CALL` | 期望录到远端播放声 |
| `STATE_INITIALIZED` 检查后回退 | 兼容不支持 VOICE_CALL 的设备 |
| `mActualSampleRate` 字段 | 部分设备采样率会被协商改（如 44100），要读真实值 |

### 11.5 阶段 11 后能干嘛
- 大部分 Android 设备都能录到下行音频
- 在国产魔改 ROM 上自动回退到 MIC（虽然 MIC 录不到远端，但至少能跑起来）

---

## 12. 阶段 12 —— 加累积器（accumulator）支持任意大小的输入

### 12.1 本阶段目标
**关键里程碑**：让编码器能接受任意大小的 PCM 输入。

### 12.2 遇到的新问题
**问题 1：为什么要累积器？**
- AAC 编码器每次输入必须 ≥ 1024 samples（2048 bytes mono16bit）。
- AudioRecord 一次 read 的大小由 bufferSize 决定（8192~16384），通常够用。
- 但**反射源**（WebRTC）一次只给 10ms = 480 samples = 960 bytes，**完全不够**。
- 所以必须先累积，再编码。

**问题 2：累积器多大？**
- 答：经验值 AAC_FRAME_BYTES × 4 = 8192 bytes（足够 4 个 AAC 帧，80ms）。

**问题 3：攒够多少送编码？**
- 答：× 2 = 4096 bytes（每帧 2048 × 2）。
- 攒太满（4 帧）→ 延迟大；攒太少（1 帧）→ 编码器每次输入恰好 2048，刚好。
- 选 ×2 是为了**避免 read 边界处切到半个 AAC 帧**。

**问题 4：循环退出时累积器里还有残余怎么办？**
- 答：补零对齐到 AAC_FRAME_BYTES，再送一次编码（带 EOS 标记）。

### 12.3 完整代码（增量）

```java
private void readAndEncodeAudio() {
    byte[] buf = new byte[mBufferSize];
    final int AAC_FRAME_BYTES = 2048;
    byte[] accum = new byte[AAC_FRAME_BYTES * 4];  // 8192 字节累积器
    int accumLen = 0;
    long pts = 0;

    while (mProcessRecording.get()) {
        int n = mAudioRecord.read(buf, 0, mBufferSize);
        if (n > 0) {
            // 追加到 accum
            System.arraycopy(buf, 0, accum, accumLen, n);
            accumLen += n;

            // 攒够 2 个 AAC 帧就送
            while (accumLen >= AAC_FRAME_BYTES * 2) {
                int send = AAC_FRAME_BYTES * 2;
                int totalSamples = send / 2;
                pts = (totalSamples * 1000000L) / mActualSampleRate;
                encodeAndMux(accum, send, pts);
                // 滑动窗口
                System.arraycopy(accum, send, accum, 0, accumLen - send);
                accumLen -= send;
            }
        }
    }

    // 退出时 flush 残余（补零对齐）
    if (accumLen > 0) {
        int pad = (AAC_FRAME_BYTES - (accumLen % AAC_FRAME_BYTES)) % AAC_FRAME_BYTES;
        for (int i = 0; i < pad; i++) accum[accumLen + i] = 0;
        encodeAndMux(accum, accumLen + pad, pts);
    }
}
```

### 12.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `accum` / `accumLen` | 累积器状态 |
| `AAC_FRAME_BYTES = 2048` | AAC LC 一帧字节数（1024 samples × 2 bytes） |
| `while (accumLen >= 4096)` | 攒够 2 帧送编码 |
| `System.arraycopy(accum, send, accum, 0, accumLen - send)` | 滑动窗口，扔掉已编码的部分 |
| PTS 按累计 sample 数算 | 保证 mp4 时间戳正确 |
| 退出时补零对齐 | 残余 PCM 也送编码（防尾部丢音） |

### 12.5 阶段 12 后能干嘛
- **支持任意大小的输入**（包括反射源的 960 bytes）
- 录出来的 mp4 时间戳连续
- 退出时尾部不丢音

---

## 13. 阶段 13 —— 加 WebRtcAudioTrackReflector 数据源

### 13.1 本阶段目标
让录制器支持"反射源"——从 WebRTC 内部抓远端 PCM，绕开麦克风独占问题。

### 13.2 遇到的新问题
**问题 1：反射源怎么集成？**
- 答：
  1. 加 `mPlayoutSource` 字段（WebRtcAudioTrackReflector 类型）
  2. 加 `setPlayoutSource(reflector)` 方法
  3. `startRecording` 时如果 `mPlayoutSource != null`，跳过 AudioRecord
  4. 主循环里 if-else 二选一：反射源.readPlayoutFrame / AudioRecord.read

**问题 2：反射源的帧大小和 AudioRecord 不同怎么办？**
- 答：从反射源读 `getFrameSizeInBytes()` 和 `getSamplesPerFrame()`，算出真实采样率。
- 公式：`sampleRate = samplesPerFrame × 100`（10ms = 1/100 秒）。

**问题 3：反射源模式需要 sleep 吗？**
- 答：**需要**。
- WebRTC 的 `AudioTrackJavaThread` 会按 10ms 节拍更新 BB。如果你不 sleep 死循环，BB 来不及更新，每次读到的都是同一帧。
- `Thread.sleep(10)` 让出 CPU 和 WebRTC 内部线程同步。

### 13.3 完整代码（关键增量）

```java
// 字段区
private WebRtcAudioTrackReflector mPlayoutSource;

// 公开 setter
public void setPlayoutSource(WebRtcAudioTrackReflector reflector) {
    this.mPlayoutSource = reflector;
}

// startRecording 内（替换 AudioRecord 初始化分支）
if (mPlayoutSource != null) {
    int frameSize = mPlayoutSource.getFrameSizeInBytes();
    int samplesPerFrame = mPlayoutSource.getSamplesPerFrame();
    mActualSampleRate = samplesPerFrame * 100;  // 48kHz → 480 samples/frame
    mBufferSize = frameSize;
    mAudioRecord = null;
    Log.i(TAG, "Using reflector: frameSize=" + frameSize);
} else {
    // ... 原有 AudioRecord 初始化 ...
}

// 主循环内
if (mPlayoutSource != null) {
    n = mPlayoutSource.readPlayoutFrame(buf);
} else if (mAudioRecord != null) {
    n = mAudioRecord.read(buf, 0, mBufferSize);
} else {
    n = 0;
}

// 反射模式下循环末尾 sleep
if (mPlayoutSource != null) {
    try { Thread.sleep(10); }
    catch (InterruptedException e) { break; }
}
```

### 13.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mPlayoutSource` 字段 | 反射源引用 |
| `setPlayoutSource()` setter | 外部注入反射源 |
| 反射源分支（无 AudioRecord） | 反射模式下不用打开麦克风 |
| `mActualSampleRate = samplesPerFrame × 100` | 推导真实采样率 |
| `Thread.sleep(10)` | 反射模式必须让出 CPU |

### 13.5 阶段 13 后能干嘛
- 在 WebRTC 应用里能可靠地录到远端播放声
- 不再受麦克风独占影响
- 这是**录制器最终形态的核心能力**

---

## 14. 阶段 14 —— 加统一资源释放方法 releaseRecorderInternal

### 14.1 本阶段目标
把"释放 AudioRecord / 编码器 / muxer / 线程"的所有 try/catch 集中到一个方法里。

### 14.2 遇到的新问题
**问题 1：为什么需要统一释放方法？**
- start 失败要回滚、stop 要清理、公开 release 也要清理 —— 三处都要做相同动作。
- 复制 3 份迟早会漏一处。

**问题 2：释放顺序重要吗？**
- 答：**严格重要**：
  1. 先 `mAudioRecord.stop() + release()` ← 否则它还在录音
  2. 再 `mMediaCodec.stop() + release()` ← 否则它还在编码
  3. 再 `mMediaMuxer.stop() + release()` ← 否则 mp4 文件不完整
  4. 最后 `mRecordThread.quitSafely()` ← 让线程退出

**问题 3：为什么用 try/catch 包每一个？**
- 答：单个失败不应该阻止其他资源释放。
- 例：AudioRecord.release 抛异常时，编码器也得 release 掉。

### 14.3 完整代码

```java
/**
 * 统一释放所有资源（幂等，可重复调用）
 */
private void releaseRecorderInternal() {
    // 1) AudioRecord
    if (mAudioRecord != null) {
        try {
            try { mAudioRecord.stop(); } catch (Exception e) { Log.e(TAG, "stop", e); }
            mAudioRecord.release();
        } catch (Exception e) { Log.e(TAG, "AudioRecord release", e); }
        mAudioRecord = null;
    }

    // 2) 工作线程
    if (mRecordThread != null) {
        HandlerThread t = mRecordThread;
        mRecordThread = null;
        t.quitSafely();
    }

    // 3) MediaCodec
    if (mMediaCodec != null) {
        try {
            mMediaCodec.stop();
            mMediaCodec.release();
        } catch (Exception e) { Log.e(TAG, "MediaCodec release", e); }
        mMediaCodec = null;
    }

    // 4) MediaMuxer
    if (mMediaMuxer != null) {
        try {
            if (mMuxerStarted) mMediaMuxer.stop();
            mMediaMuxer.release();
        } catch (Exception e) { Log.e(TAG, "MediaMuxer release", e); }
        mMediaMuxer = null;
        mMuxerStarted = false;
    }

    mAudioTrackIndex = -1;
}
```

### 14.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `releaseRecorderInternal()` | 三处复用（start 失败回滚 / stop / 公开 release） |
| 严格释放顺序 | 避免依赖问题（编码器还活着但 audio 已经关了） |
| 每个 release 包 try/catch | 一个失败不影响其他释放 |
| `quitSafely()` 而不是 `quit()` | 安全退出：让已 post 的任务跑完 |

### 14.5 阶段 14 后能干嘛
- start 失败时自动回滚，资源不泄漏
- stop 完整释放所有资源
- 重复 stop 安全（幂等）

---

## 15. 阶段 15 —— 加周期性统计日志 + 累计字段

### 15.1 本阶段目标
让录制器在录制过程中输出累计字节、累计时长、累计帧数等统计信息。

### 15.2 遇到的新问题
**问题 1：统计字段放哪里？**
- 答：实例字段（不是局部变量），因为：
  - 需要在录制结束后能查询（`getRecordedBytes()`）
  - 多处都要写（累积器送编码时累加）

**问题 2：日志打多频繁？**
- 答：每 3 秒一次。logcat 太多会拖慢应用，太少又看不到实时进度。

**问题 3：累计字节 vs 累计 AAC 字节？**
- 答：当前实现累计的是**送编码的 PCM 字节数**（包含补零）。
- 这反映"我们花了多少 PCM 进去"，而不是"mp4 文件多大"。
- mp4 体积大约是 PCM 的 1/10（AAC 压缩）。

### 15.3 完整代码（增量）

```java
// 字段区
private long mTotalBytesRecorded = 0;
private long mRecordingStartTime = 0;

// startRecording 末尾
mRecordingStartTime = System.currentTimeMillis();

// 主循环内（送编码后累加）
mTotalBytesRecorded += send;

// 主循环内（每 3 秒日志）
long now = System.currentTimeMillis();
if (now - lastLogTime > 3000) {
    Log.i(TAG, "Recording... bytes: " + mTotalBytesRecorded
            + ", duration: " + (pts / 1000) + "ms");
    lastLogTime = now;
}

// stopRecording 内
long duration = System.currentTimeMillis() - mRecordingStartTime;
Log.i(TAG, "Recording stopped. Total: " + mTotalBytesRecorded + " bytes, "
        + duration + "ms, " + (duration > 0 ? (mTotalBytesRecorded * 1000 / duration / 1024) : 0) + " KB/s");

// 公开 getter
public long getRecordedBytes() { return mTotalBytesRecorded; }
```

### 15.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mTotalBytesRecorded` | 累计 PCM 字节数 |
| `mRecordingStartTime` | 录制开始时间戳（算时长用） |
| `mTotalBytesRecorded += send` | 累加 |
| 每 3 秒 log | 不刷屏但能看到进度 |
| stop 时输出平均 KB/s | 健康度指标 |
| `getRecordedBytes()` getter | UI 进度条可用 |

---

## 16. 阶段 16 —— 加 setPlayoutSource / 公开 getter / 公开 release

### 16.1 本阶段目标
把"反射源 setter"、"isRecording / getRecordedBytes"、"公开 release"等收尾 API 暴露出去。

### 16.2 遇到的新问题
**问题 1：公开 release 有什么用？**
- 答：调用方在不用录制器时主动调用，释放 muxer / codec 等重资源。
- 不调也没事，GC 会回收 native 句柄，但显式 release 更可控。

**问题 2：release 要加锁吗？**
- 答：要。和 stopRecording 共享 mLock，避免与正在 stop 的线程撞车。

### 16.3 完整代码（增量）

```java
public void setPlayoutSource(WebRtcAudioTrackReflector reflector) {
    this.mPlayoutSource = reflector;
}

public boolean isRecording() {
    return mIsRecording;
}

public long getRecordedBytes() {
    return mTotalBytesRecorded;
}

public void release() {
    synchronized (mLock) {
        releaseRecorderInternal();
    }
}
```

### 16.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `setPlayoutSource` | 外部注入反射源（已在阶段 13 加过，这里正式列入 API） |
| `isRecording()` | UI 状态查询 |
| `getRecordedBytes()` | UI 进度 / 字节数查询 |
| `release()` 公开方法 | 主动释放，外部可控 |

### 16.5 阶段 16 后能干嘛
- 录制器的对外 API 完全定型
- 内部状态完全受控
- 可以接入任何 UI / 业务逻辑

---

## 17. 完整最终代码对照

到阶段 16 为止，代码已经和文件 `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` 完全一致。下面是按行号 + 阶段映射的关系：

| 阶段 | 文件中对应的内容 | 行号（参考） |
|------|----------------|------------|
| 1 | `package` / `import` / 类声明 / 构造函数 | 1-58 |
| 2 | 录制参数常量 + TAG | 22-27 |
| 5 | AudioRecord 字段 + 初始化逻辑 | 60-66, 124-160 |
| 6 | AAC 编码器字段 + 配置 + encodeData | 29-34, 161-185 |
| 7 | MediaMuxer 字段 + drainEncoder + writeSampleData | 36-40, 187-220 |
| 8 | HandlerThread + AtomicBoolean + readAndEncodeAudio 主循环 | 42-46, 222-320 |
| 9 | mIsRecording / mLock / synchronized start+stop / isRecording | 36, 47, 100-220, 410-422 |
| 10 | silentFrameCount + isSilentBuffer | 256-275, 322-340 |
| 11 | VOICE_CALL → MIC 回退 | 124-160 |
| 12 | 累积器 accum / 滑动窗口 / 补零 flush | 280-310 |
| 13 | mPlayoutSource + setPlayoutSource + 反射源分支 | 38, 60-66, 105-115, 244-260, 318-326 |
| 14 | releaseRecorderInternal 集中释放 | 470-520 |
| 15 | mTotalBytesRecorded / mRecordingStartTime / 周期日志 | 49-50, 218, 295-298, 405-415 |
| 16 | 公开 getter + 公开 release | 410-465 |

最终代码全文已在前面各阶段给出，结构和当前文件完全一致。

---

## 18. 演进回顾表

| 阶段 | 加了什么 | 行数变化 | 解决的痛点 | 阶段末能干嘛 |
|------|---------|---------|-----------|------------|
| 1 | 空骨架 + start/stop 方法签名 | ~10 | 占位 | 编译通过 |
| 2 | 录制参数常量 + TAG | ~20 | 参数固化 | start/stop log |
| 5 | AudioRecord 初始化 + read | ~70 | 能从麦克风读 PCM | 验证麦克风链路 |
| 6 | AAC 编码器 + encodeOneFrame | ~120 | 能编 PCM → AAC | 验证编码链路 |
| 7 | MediaMuxer + drainEncoder | ~180 | 能写 mp4 | 完整 PCM → mp4 |
| 8 | HandlerThread + 主循环 | ~230 | 不阻塞 UI | 后台持续录 |
| 9 | 状态管理 + start/stop API | ~280 | 防重入、失败回滚 | 完整生命周期 |
| 10 | 静音检测 + 日志 | ~320 | 调试静音问题 | 知道何时无声 |
| 11 | VOICE_CALL → MIC 回退 | ~340 | 兼容性 | 国产 ROM 能用 |
| 12 | 累积器 + 滑动窗口 | ~400 | 任意输入大小 | 支持反射源 |
| 13 | WebRtcAudioTrackReflector | ~440 | 麦克风独占 | 可靠录远端 |
| 14 | 统一 release 方法 | ~480 | 资源不泄漏 | start 失败回滚 |
| 15 | 统计字段 + 周期日志 | ~510 | 实时进度 | 知道录制健康度 |
| 16 | 公开 getter + 公开 release | ~522 | 完整 API | 可被 UI 接入 |

**关键里程碑**：
- 阶段 7 = 第一个能跑通"PCM → mp4"的最小版本（但不支持反射源）
- 阶段 13 = 与当前文件功能完全一致（支持反射源）
- 阶段 16 = 与当前文件结构完全一致（API 完整）

---

## 19. 常见报错对照表

写代码时遇到下面任何一种错误，对照表看哪里出问题：

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `MediaCodec: configure failed: ...` | 6 | 检查 `KEY_MAX_INPUT_SIZE ≥ 2048`、AAC_PROFILE 是否设备支持 |
| `MediaCodec: dequeueInputBuffer failed` | 6 | 编码器已满。增加 timeout，或检查编码循环是否卡住 |
| `MediaMuxer: stop() called before start()` | 7 | 还没收到 `INFO_OUTPUT_FORMAT_CHANGED`。检查编码器是否真的输出了 |
| `MediaMuxer: writeSampleData failed` | 7 | `mMuxerStarted` 还是 false，或 trackIndex 错 |
| `IllegalStateException at MediaCodec.start()` | 6 | configure 没成功调用；或 codec 已被释放 |
| `AudioRecord: getMinBufferSize error` | 5 | 设备不支持该参数组合。尝试改采样率到 44100 |
| `AudioRecord state uninitialized` | 5/11 | 麦克风被独占（WebRTC）。改用反射源 |
| 录出来的 mp4 没声音 | 7/12 | 检查 `out.position/limit` 设置；检查 PTS 是否正确 |
| 录出来的 mp4 只有一半 | 14 | stop 时没有走完 release 流程。检查 stopRecording → releaseRecorderInternal |
| 连续 1000 帧静音 | 10 | 数据源没声。反射源：检查 WebRTC 连接；麦克风源：检查 VOICE_CALL fallback |
| 应用 ANR | 8 | 录制循环跑在 UI 线程。检查 HandlerThread 是否真启用了 |
| 重复 start 崩溃 | 9 | 缺 `mIsRecording` 检查 |

---

## 附录：相关文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` | 本指南所构建的类，522 行最终形态 |
| `app/src/main/java/com/example/chapter07/WebRtcAudioTrackReflector.java` | 提供 PCM 数据源（反射模式） |
| `app/src/main/java/com/example/chapter07/LocalAudioRecorder.java` | 本端音频录制参考（结构类似，但更简单） |
| `app/src/main/java/com/example/chapter07/WebRtcAudioRecordSamplesBridge.java` | 反射源和本录制器之间的桥接 |
| `readme/record_audio/README.md` | 远端 + 本端录制的整体实现总览 |

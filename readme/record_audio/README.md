# WebRTC 远端音频与本端音频录制实现详解

## 目录
1. [项目背景与目标](#1-项目背景与目标)
2. [核心概念与原理](#2-核心概念与原理)
3. [远端音频录制实现](#3-远端音频录制实现)
4. [远端录制关键 Bug 与定位过程](#4-远端录制关键-bug-与定位过程)
5. [本端音频录制实现](#5-本端音频录制实现)
6. [远端 vs 本端：方案差异对比](#6-远端-vs-本端方案差异对比)
7. [总结与最佳实践](#7-总结与最佳实践)

---

## 1. 项目背景与目标

### 1.1 需求描述
在基于 WebRTC 的视频通话应用（`peer.java`）中，实现两个独立功能：
- **远端音频录制**：从对方传来的音频流（WebRTC 内部 playout 路径）抓取 PCM，编码保存为 AAC/MP4 文件
- **本端音频录制**：从本地麦克风采集的音频流（WebRTC 内部 record 路径）抓取 PCM，编码保存为 AAC/MP4 文件

### 1.2 核心挑战
1. **不能开第二个 AudioRecord**：Android 不允许同一 Mic 被两个 `AudioRecord` 同时占用，WebRTC 已经占用了
2. **不能修改 WebRTC 库源码**：必须通过反射或公开 API 拿到数据
3. **数据在 WebRTC 内部线程中**：不能用普通方法读到，必须避免并发破坏 WebRTC 状态机
4. **AAC 编码器要求 1024 samples/帧**：WebRTC 的 10ms 帧只有 480 samples，需要攒包
5. **MediaCodec 状态机复杂**：`INFO_OUTPUT_FORMAT_CHANGED`、`INFO_TRY_AGAIN_LATER`、`BUFFER_FLAG_END_OF_STREAM` 处理

---

## 2. 核心概念与原理

### 2.1 WebRTC 内部音频流走向
```
┌─────────────────────────────────────────────────────────────────┐
│                        远端 (Playout) 方向                          │
│                                                                 │
│  网络接收 → WebRTC audio engine → playout ring buffer            │
│                                       │                         │
│                                       ▼                         │
│                          WebRtcAudioTrack.byteBuffer (10ms PCM) │
│                                       │                         │
│                                       ▼                         │
│                           AudioTrack.write() → 扬声器            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        本端 (Record) 方向                          │
│                                                                 │
│  麦克风 → Android AudioRecord → WebRtcAudioRecord.byteBuffer     │
│                                       │                         │
│                                       ▼                         │
│                            nativeDataIsRecorded()               │
│                                       │                         │
│                                       ▼                         │
│                            WebRTC audio engine → 网络发送          │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 MediaCodec / MediaMuxer 状态机
```
configure() → start() → dequeueInputBuffer() → queueInputBuffer()
                          ↓
                   dequeueOutputBuffer()
                          ↓
              ┌───────────┼────────────┐
              ↓           ↓            ↓
       INFO_OUTPUT_   INFO_TRY_    outputBufferIndex
       FORMAT_CHANGED  AGAIN_LATER     ≥ 0
              ↓           ↓            ↓
       addTrack()       busy-wait   writeSampleData()
       start()                      releaseOutputBuffer()
```

### 2.3 AAC 编码的最小帧大小
- AAC-LC 一帧必须 = **1024 samples**
- mono16bit = **2048 bytes**
- 48kHz 采样率下：每 21.33ms 才能产出 1 个 AAC 帧
- WebRTC 给的 10ms 帧只有 480 samples（960 bytes）→ **必须攒包**

---

## 3. 远端音频录制实现

### 3.1 总体架构
```
┌──────────────────┐    反射调用 native    ┌─────────────────────┐
│  WebRtcAudioTrack│ ◄────────────────── │ WebRtcAudioTrack    │
│  Reflector       │   nativeGetPlayoutData  Reflector（反射类）   │
└──────────────────┘                     └─────────────────────┘
         │ 10ms PCM (duplicate view)
         ▼
┌──────────────────┐  攒包到 4096 bytes   ┌─────────────────────┐
│ RemoteAudio      │ ──────────────────► │ MediaCodec(AAC)     │
│ Recorder         │                     │ encoder             │
└──────────────────┘                     └─────────────────────┘
                                                │ AAC frames
                                                ▼
                                         ┌─────────────────────┐
                                         │ MediaMuxer(MP4)     │
                                         └─────────────────────┘
```

### 3.2 核心类：`WebRtcAudioTrackReflector`

#### 3.2.1 反射获取 WebRtcAudioTrack 实例
WebRTC 的 `JavaAudioDeviceModule` 把 `audioOutput` 字段设为 `private final`，类型是 `org.webrtc.audio.WebRtcAudioTrack`（包私有类，无法直接 import）。

**反射流程**：
```java
// 1. 拿 JavaAudioDeviceModule 的 audioOutput 字段
Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
audioOutputField.setAccessible(true);
Object audioOutput = audioOutputField.get(adm);

// 2. 通过字符串拿包私有类
Class<?> webRtcAudioTrackClass = Class.forName("org.webrtc.audio.WebRtcAudioTrack");

// 3. 拿 native handle
Field nativeAudioTrackField = webRtcAudioTrackClass.getDeclaredField("nativeAudioTrack");
long handle = nativeAudioTrackField.getLong(audioOutput);

// 4. 拿 private native 方法
Method mNativeGetPlayoutData = webRtcAudioTrackClass.getDeclaredMethod(
        "nativeGetPlayoutData", long.class, int.class);
mNativeGetPlayoutData.setAccessible(true);

// 5. 拿 BB 字段引用
Field mByteBufferField = webRtcAudioTrackClass.getDeclaredField("byteBuffer");
```

#### 3.2.2 读取一帧 PCM
```java
public int readPlayoutFrame(byte[] buffer) {
    ByteBuffer bb = (ByteBuffer) mByteBufferField.get(mAudioOutput);

    int sizeToRead = mFrameSizeInBytes;  // 10ms 帧大小
    mNativeGetPlayoutData.invoke(null, mNativeAudioTrackHandle, sizeToRead);

    // 关键：用 duplicate() 创建视图读取
    ByteBuffer view = bb.duplicate();
    view.position(0);
    int got = Math.min(sizeToRead, view.remaining());
    view.get(buffer, 0, got);
    return got;
}
```

### 3.3 核心类：`RemoteAudioRecorder`

#### 3.3.1 MediaCodec + MediaMuxer 初始化
```java
MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE_AAC, sampleRate, 1);
format.setInteger(MediaFormat.KEY_AAC_PROFILE, AACObjectLC);
format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);  // 必须 ≥1024 samples

mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE_AAC);
mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
mMediaCodec.start();

mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

#### 3.3.2 PCM 攒包 + 编码循环
```java
byte[] accum = new byte[8192];   // 攒包 buffer
int accumLen = 0;

while (mProcessRecording.get()) {
    int bytesRead = mPlayoutSource.readPlayoutFrame(buffer);

    // 累加
    System.arraycopy(buffer, 0, accum, accumLen, bytesRead);
    accumLen += bytesRead;

    // 攒够 4096 bytes 就送编码
    while (accumLen >= 4096) {
        encodeData(accum, 4096, timestampUs);
        // 滑动窗口
        System.arraycopy(accum, 4096, accum, 0, accumLen - 4096);
        accumLen -= 4096;
    }
}
```

#### 3.3.3 编码器输出处理（drainEncoder）
```java
private void drainEncoder(boolean endOfStream) {
    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    while (true) {
        int idx = mMediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUs);

        if (idx == INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) break;
        } else if (idx == INFO_OUTPUT_FORMAT_CHANGED) {
            // 第一次：把轨加入 muxer
            MediaFormat newFormat = mMediaCodec.getOutputFormat();
            mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
            mMediaMuxer.start();   // ⚠️ 必须在 addTrack 后才能 start
            mMuxerStarted = true;
        } else if (idx >= 0) {
            ByteBuffer out = mMediaCodec.getOutputBuffer(idx);
            if (out != null && bufferInfo.size > 0 && mMuxerStarted) {
                out.position(bufferInfo.offset);
                out.limit(bufferInfo.offset + bufferInfo.size);
                mMediaMuxer.writeSampleData(mAudioTrackIndex, out, bufferInfo);
            }
            mMediaCodec.releaseOutputBuffer(idx, false);

            if ((bufferInfo.flags & BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }
}
```

---

## 4. 远端录制关键 Bug 与定位过程

### 4.1 Bug 现象
**点击"录制远端音频"按钮 → 应用立即崩溃 → 弹出 "App keeps stopping" 系统对话框**

### 4.2 第一次崩溃堆栈
```
FATAL EXCEPTION: AudioTrackJavaThread
Process: com.example.chapter07, PID: 3135
java.lang.AssertionError: Expected condition to be true
    at org.webrtc.audio.WebRtcAudioTrack.assertTrue(WebRtcAudioTrack.java:520)
    at org.webrtc.audio.WebRtcAudioTrack$AudioTrackThread.run(WebRtcAudioTrack.java:116)
```

### 4.3 第二次崩溃堆栈
```
FATAL EXCEPTION: AudioTrackJavaThread
Process: com.example.chapter07, PID: 3375
java.lang.IllegalArgumentException: Bad position 1920/960
    at java.nio.Buffer.position(Buffer.java:287)
    at java.nio.ByteBuffer.position(ByteBuffer.java:857)
    at android.media.AudioTrack.write(AudioTrack.java:3524)
    at org.webrtc.audio.WebRtcAudioTrack$AudioTrackThread.run(WebRtcAudioTrack.java:122)
```

### 4.4 定位过程

#### 第一步：识别崩溃线程
崩溃发生在 **`AudioTrackJavaThread`**——这是 WebRTC 内部的播放线程，不是我自己的代码线程。

#### 第二步：定位到具体行
对照 WebRTC 源码（`chromium.googlesource.com`）查 `WebRtcAudioTrack.java:116`：
```java
// WebRtcAudioTrack$AudioTrackThread.run()
final int sizeInBytes = byteBuffer.capacity();  // sizeInBytes = 960
while (keepAlive) {
    nativeGetPlayoutData(nativeAudioTrack, sizeInBytes);
    assertTrue(sizeInBytes <= byteBuffer.remaining());  // ← 第116行
    ...
    int bytesWritten = writeBytes(audioTrack, byteBuffer, sizeInBytes);  // ← 第122行
    ...
    byteBuffer.rewind();
}
```

#### 第三步：分析崩溃原因
崩溃**不是我的代码抛出的**，而是 WebRTC 内部 `assertTrue` 和 `AudioTrack.write` 抛出。

**关键发现**：
- BB capacity = 960（10ms 帧大小）
- 崩溃时 `position=1920`、`limit=960`
- `Bad position 1920/960` 是 `Buffer.position()` 在 `position + size > limit` 时抛出的

**还原事故现场**：

| 时刻 | 线程 | 操作 | BB position |
|------|------|------|-------------|
| T1 | AudioTrackThread | `AudioTrack.write(bb, 960)` | 0 → 960 |
| T2 | AudioTrackThread | `byteBuffer.rewind()` | 960 → 0 |
| T3 | 我的线程 | `bb.position(0); bb.get(...)` | 0 → 960 |
| T4 | AudioTrackThread | `AudioTrack.write(bb, 960)` | 内部把 position 推到 960+960=1920，**`position > limit=960` → 崩溃** |

**根因**：我读 PCM 时修改了 BB position（`bb.position(0); bb.get()`），破坏了 WebRTC 内部 `AudioTrack.write` 对 position 状态的假设。

#### 第四步：第一次错误修复尝试
最初以为只是 **"读时改了 position"**，想用 `bb.duplicate()` + **读写完后 `view.position(0)`** 来复位：

```java
ByteBuffer view = bb.duplicate();
view.position(0);
view.get(buffer, 0, got);
view.position(0);  // ← 仍然修改了 view 的 position（虽然不影响原 BB，但无法解决下一个问题）
```

**但这还是错的**——因为下一次 AudioTrackThread 还会写，还是会冲突。

### 4.5 真正的修复：使用 `ByteBuffer.duplicate()`

**`ByteBuffer.duplicate()` 的语义**：
- 与原 BB 共享底层数据（同一块 native 内存）
- 但有**独立的 position / limit / mark**
- 修改视图的 position **完全不影响原 BB**

```java
ByteBuffer view = bb.duplicate();   // 共享数据，独立 position
view.position(0);                    // 只改视图的 position
int got = Math.min(sizeToRead, view.remaining());
view.get(buffer, 0, got);           // 只读视图，不动原 BB
// 函数返回时，原 bb.position 仍然保持 WebRTC 期望的状态
```

**修复后的 BB 状态**：
- 原 bb.position 不变（WebRTC 线程继续安全地 `AudioTrack.write(bb, 960)`）
- 我每次读的 view 都从 position=0 开始，逻辑上"重新读一次"

### 4.6 为什么远端要反射，本端不反射

| | 远端 (Playout) | 本端 (Record) |
|---|---|---|
| BB 写入方 | WebRTC `AudioTrack.write(bb, 960)` 推进 position | Android `AudioRecord.read(bb, 960)` **不改 position**（Android 文档明示） |
| BB 读取方 | 我读时如果动 position → 破坏 WebRTC 写入 | 我读时动 position → 不影响 AudioRecord |
| 是否需要反射 | **需要**（公开 API 拿不到 10ms 帧） | **不需要**（有公开 `SamplesReadyCallback`） |
| 数据副本 | duplicate view（共享底层内存） | callback 已传 byte[] 副本 |

### 4.7 定位问题的关键技巧

1. **看崩溃堆栈的线程名**：`AudioTrackJavaThread` 不是我的线程，是 WebRTC 内部
2. **看行号对照 WebRTC 源码**：`WebRtcAudioTrack.java:116` / `:122` 在 GitHub 上能直接搜到
3. **看错误信息**：`Bad position 1920/960` 中 1920 是 `position+size`，960 是 limit——明确说明 position 被破坏了
4. **印 `position / limit / capacity` 在日志里**：下次再写带 BB 并发读写的代码就 print 一遍
5. **二分定位**：先注释掉我的 `bb.get(...)`，看崩溃是否还在；还在就是其他问题，不在了就是 BB 并发问题

---

## 5. 本端音频录制实现

### 5.1 总体架构
```
┌──────────────────────┐     公开回调 API      ┌──────────────────────────┐
│ WebRTC 内部           │ ───────────────────► │ WebRtcAudioRecordSamples │
│ AudioRecordThread    │  onWebRtcAudio       │ Bridge                   │
│                      │  RecordSamplesReady  │ (JavaAudioDeviceModule.  │
└──────────────────────┘                      │  SamplesReadyCallback)   │
                                              └──────────────────────────┘
                                                       │ 10ms PCM 副本
                                                       ▼
                                              ┌──────────────────────┐
                                              │ ConcurrentLinkedQueue│
                                              └──────────────────────┘
                                                       │
                                                       ▼
                                              ┌──────────────────────┐
                                              │ LocalAudioRecorder   │
                                              │ (攒包 → AAC → MP4)    │
                                              └──────────────────────┘
```

### 5.2 核心类：`WebRtcAudioRecordSamplesBridge`

**原理**：实现 `JavaAudioDeviceModule.SamplesReadyCallback` 接口，在 JavaAudioDeviceModule.Builder 阶段注入。

#### 5.2.1 关键实现
```java
public class WebRtcAudioRecordSamplesBridge implements
        JavaAudioDeviceModule.SamplesReadyCallback {

    // 录音通道：null = 丢弃样本（关闭录音）
    private final AtomicReference<ConcurrentLinkedQueue<byte[]>> mActiveSink =
            new AtomicReference<>(null);

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        ConcurrentLinkedQueue<byte[]> sink = mActiveSink.get();
        if (sink == null) {
            return;  // 未开启录音，直接丢
        }

        byte[] data = samples.getData();
        byte[] copy = new byte[data.length];   // 关键：自己再 copy 一份
        System.arraycopy(data, 0, copy, 0, data.length);
        sink.offer(copy);
    }

    public void setActiveSink(ConcurrentLinkedQueue<byte[]> sink) {
        mActiveSink.set(sink);   // 切换录音状态
    }
}
```

#### 5.2.2 关键点：byte[] 副本
WebRTC 传入的 `samples.getData()` 是回调现场拷贝好的 byte[]，但**这个 byte[] 的内部 buffer 在 WebRTC 内部可能被复用**，所以我们**必须自己再 copy 一份**再入队。

### 5.3 核心类：`LocalAudioRecorder`

#### 5.3.1 启动流程
```java
public boolean startRecording(String outputPath) {
    synchronized (mLock) {
        mPcmQueue.clear();
        mBridge.resetState();

        // 探测采样率（等第一次 callback 到达）
        int probe = probeSampleRate(500);
        mActualSampleRate = (probe > 0) ? probe : 48000;

        // 创建 MediaCodec + MediaMuxer（同远端）
        ...

        // 通知 bridge 开始入队
        mBridge.setActiveSink(mPcmQueue);   // ← 关键：这才真正开始录音

        // 启动消费线程
        mRecordHandler.post(this::readAndEncodeAudio);
        return true;
    }
}
```

#### 5.3.2 消费线程
```java
private void readAndEncodeAudio() {
    byte[] accum = new byte[8192];
    int accumLen = 0;

    while (mProcessRecording.get()) {
        byte[] frame = mPcmQueue.poll();  // 拉一帧
        if (frame == null) {
            Thread.sleep(10);             // 队列空就短睡
            continue;
        }

        // 累加到 accumulator
        System.arraycopy(frame, 0, accum, accumLen, frame.length);
        accumLen += frame.length;

        // 攒够 4096 bytes 就编码
        while (accumLen >= 4096) {
            encodeData(accum, 4096, presentationTimeUs);
            // 滑动窗口
            System.arraycopy(accum, 4096, accum, 0, accumLen - 4096);
            accumLen -= 4096;
        }
    }
}
```

#### 5.3.3 停止流程
```java
public void stopRecording() {
    mBridge.setActiveSink(null);      // 1. 停止入队
    mProcessRecording.set(false);      // 2. 通知消费线程退出

    mRecordThread.join(3000);          // 3. 等消费线程结束

    // 4. flush MediaCodec + 停止 muxer
    int idx = mMediaCodec.dequeueInputBuffer(10000);
    if (idx >= 0) {
        mMediaCodec.queueInputBuffer(idx, 0, 0, 0, BUFFER_FLAG_END_OF_STREAM);
    }
    drainEncoder(true);                // 5. 排出残余编码帧

    mMediaCodec.stop();
    mMediaCodec.release();
    mMediaMuxer.stop();
    mMediaMuxer.release();
}
```

### 5.4 在 peer.java 中的接入

#### 5.4.1 在 Builder 阶段注入 callback
```java
mLocalAudioBridge = new WebRtcAudioRecordSamplesBridge();
mJavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        ...
        .setSamplesReadyCallback(mLocalAudioBridge)   // ← 必须在 builder 阶段设置
        ...
        .createAudioDeviceModule();
```

#### 5.4.2 按钮绑定（独立按钮，调用独立类）
```java
mRecordLocalAudioToggleBtn = findViewById(R.id.RecordLocalAudioToggleBtn);
mRecordLocalAudioToggleBtn.setOnClickListener(v -> {
    if (!mIsLocalAudioRecording) {
        startLocalAudioRecording();   // → new LocalAudioRecorder(bridge).startRecording()
    } else {
        stopLocalAudioRecording();
    }
});
```

### 5.5 编译期的兼容性坑

第一次提交时遇到一个 `ConcurrentLinkedQueue.poll(long, TimeUnit)` 报错：
```
错误: 无法将 ConcurrentLinkedQueue<E>中的方法 poll应用到给定类型;
```

**原因**：这个带超时的 `poll` 方法是 **Java 8+** 才加的，但项目 `minSdk=21`（Android 5.0）只支持 **Java 7 语法**。

**修复**：改用 `poll()` + `Thread.sleep(10)`：
```java
byte[] frame = mPcmQueue.poll();    // Java 7 兼容
if (frame == null) {
    Thread.sleep(10);
    continue;
}
```

**教训**：写代码前查 `minSdk` 对应的 Java 版本支持表，避免 Java 8 语法（lambda、Stream、`::` 方法引用是 Android 24+ 才能用 desugar）。

---

## 6. 远端 vs 本端：方案差异对比

### 6.1 数据获取方式

| | 远端 | 本端 |
|---|---|---|
| 数据源 | WebRtcAudioTrack 内部 BB | JavaAudioDeviceModule.SamplesReadyCallback |
| API 性质 | 反射（私有字段 + private native） | 公开 Builder API |
| 数据形式 | BB 直接共享内存（需要 duplicate view） | byte[] 回调（已传副本） |
| 并发风险 | 高（必须用 duplicate） | 低（已经隔离） |

### 6.2 代码独立性

按用户要求"独立建一个类"：
- 远端：`WebRtcAudioTrackReflector`（反射）+ `RemoteAudioRecorder`（编码）
- 本端：`WebRtcAudioRecordSamplesBridge`（回调）+ `LocalAudioRecorder`（编码）

**两套类互不依赖**，未来要替换其中任何一方都不会影响另一方。

### 6.3 触发时机

| | 远端 | 本端 |
|---|---|---|
| 触发点 | 每次 `nativeGetPlayoutData(handle, 960)` | WebRTC AudioRecordThread 每 10ms |
| 频率 | 100 Hz | 100 Hz |
| 触发线程 | 我的反射调用线程 | WebRTC `AudioRecordThread`（Java） |

---

## 7. 总结与最佳实践

### 7.1 反射访问 WebRTC 内部
- **用 `Class.forName()` 加载包私有类**，不要 import
- **`setAccessible(true)` 必须在 getDeclaredField/getDeclaredMethod 之后**
- **`static native` 方法用 `Method.invoke(null, ...)`，第一个参数传 null**
- **Java 字段用 `Field.get(Object)` / `Field.getLong(Object)`，区分 get 与 getLong**

### 7.2 MediaCodec / MediaMuxer 使用要点
1. **`INFO_OUTPUT_FORMAT_CHANGED` 必须先 `addTrack` 再 `mMediaMuxer.start()`**——顺序反了文件就是 0 字节
2. **`BUFFER_FLAG_END_OF_STREAM` 通过 `queueInputBuffer(idx, 0, 0, 0, flag)` 发送，size=0**
3. **`writeSampleData` 必须用 `out.position(off); out.limit(off+size)`** 限定区间，否则会写穿
4. **`releaseOutputBuffer(idx, false)` 必须调用**，否则 buffer 会卡死
5. **`stop() → release()` 顺序不能反**，否则会抛 IllegalStateException

### 7.3 并发安全原则
- **绝对不要修改 WebRTC 持有的 ByteBuffer position**
- **要用 `ByteBuffer.duplicate()` 创建视图**，只读不改原 BB
- **callback 传入的 byte[] 要自己再 copy 一份**（防止源 buffer 被复用）
- **用 `ConcurrentLinkedQueue` 或 `BlockingQueue` 做生产/消费解耦**

### 7.4 Bug 定位方法论
1. **看崩溃线程名**：是 WebRTC 内部还是自己代码
2. **看行号 → 找 WebRTC 源码**：行号 `WebRtcAudioTrack.java:116` 直接搜 GitHub
3. **看错误码格式**：`Bad position 1920/960` = `position` 超 `limit`，明确说明 BB 被破坏
4. **二分定位**：注释掉一半代码看崩溃是否还在
5. **日志埋点**：在关键变量变化处 Log.i，下一次出问题直接 grep 日志

### 7.5 兼容性检查清单
- `minSdk` 对应的 Java 版本（21 = Java 7）
- WebRTC API 是否在该版本可用
- AAC 编码要求 ≥1024 samples/帧（2048 bytes mono16bit）
- `KEY_MAX_INPUT_SIZE` 至少 16384 防止 codec 内部 buffer 不够

### 7.6 文件输出路径
代码使用 `getExternalFilesDir(null)`：
- Android 10+ 不需要存储权限
- 文件落在 `/storage/emulated/0/Android/data/com.example.chapter07/files/`
- 应用卸载时自动清理

---

## 附录：关键文件清单

| 文件 | 作用 |
|---|---|
| `WebRtcAudioTrackReflector.java` | 反射抓取远端 10ms PCM |
| `WebRtcAudioRecordSamplesBridge.java` | 本端 PCM 回调桥接器 |
| `RemoteAudioRecorder.java` | 远端 PCM → AAC → MP4 录制器 |
| `LocalAudioRecorder.java` | 本端 PCM → AAC → MP4 录制器 |
| `peer.java` | 业务逻辑，按钮与 WebRTC PeerConnection 管理 |
| `activity_peer.xml` | UI 布局（按钮定义） |

# 从零实现 WebRtcAudioRecordSamplesBridge —— 渐进式构建指南

> **目标读者**：了解 Java 并发基础（volatile / Atomic）、对 WebRTC JavaAudioDeviceModule 有基本概念的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/WebRtcAudioRecordSamplesBridge.java`（135 行）从"空类"演进到当前规模。这是一个**桥接器**：把 WebRTC 内部 AudioRecordThread 回调出来的 PCM 数据转发给业务层。
>
> **关键设计点**：使用 `AtomicReference<Queue>` 做 sink 控制，避免 WebRTC 线程和业务线程之间的竞态条件。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 实现 SamplesReadyCallback 接口（空实现）](#3-阶段-1--实现-samplesreadycallback-接口空实现)
4. [阶段 2 —— 加 AtomicReference sink 字段](#4-阶段-2--加-atomicreference-sink-字段)
5. [阶段 3 —— 加 setActiveSink 方法](#5-阶段-3--加-setactivesink-方法)
6. [阶段 4 —— 加采样率/声道探测字段](#6-阶段-4--加采样率声道探测字段)
7. [阶段 5 —— 实现 onWebRtcAudioRecordSamplesReady（主体）](#7-阶段-5--实现-onwebrtcaudiorecordsamplesready主体)
8. [阶段 6 —— 第一次回调探测采样率/声道](#8-阶段-6--第一次回调探测采样率声道)
9. [阶段 7 —— 数据拷贝与入队](#9-阶段-7--数据拷贝与入队)
10. [阶段 8 —— 加原子计数器（统计）](#10-阶段-8--加原子计数器统计)
11. [阶段 9 —— 加 resetState + getter](#11-阶段-9--加-resetstate--getter)
12. [完整最终代码对照](#12-完整最终代码对照)
13. [演进回顾表](#13-演进回顾表)
14. [常见问答](#14-常见问答)

---

## 1. 这个类到底要做什么

一句话：**实现 WebRTC `SamplesReadyCallback` 接口，把 WebRTC 内部 AudioRecordThread 每 10ms 调一次的 PCM 数据拷贝到业务层提供的队列里**。

为什么需要这个？因为 WebRTC 默认不让外部"看到"麦克风采集到的 PCM 数据。如果业务层想做本端录音，就拿不到原始 PCM。

这个桥接器让业务层能在不破坏 WebRTC 工作流的前提下，**拿到一份 PCM 副本**。

---

## 2. 前置知识速查

### 2.1 WebRTC SamplesReadyCallback 接口

```java
public interface SamplesReadyCallback {
    void onWebRtcAudioRecordSamplesReady(AudioSamples samples);
}
```

`AudioSamples` 包含：
- `getSampleRate()`：采样率
- `getChannelCount()`：声道数
- `getData()`：byte[] PCM 数据
- `getAudioFormat()`：格式

### 2.2 怎么注册

```java
JavaAudioDeviceModule.Builder builder = ...;
WebRtcAudioRecordSamplesBridge bridge = new WebRtcAudioRecordSamplesBridge();
builder.setSamplesReadyCallback(bridge);  // 在 build 时注入
```

注意：**必须在 builder 阶段注入**，不能在 JavaAudioDeviceModule 创建之后注入。

### 2.3 线程模型

| 线程 | 操作 |
|------|------|
| WebRTC AudioRecordThread | 每 10ms 调 `onWebRtcAudioRecordSamplesReady` |
| 业务线程（录音） | `setActiveSink(queue)` 切换 sink |

两个线程并发，必须用线程安全的容器（`AtomicReference`）保证可见性。

### 2.4 AtomicReference vs volatile

| 类型 | 用法 | 适合场景 |
|------|------|---------|
| `volatile Object obj` | 直接读写引用 | 单一引用 |
| `AtomicReference<T> ref` | `set/get/compareAndSet` | 引用本身要原子操作 |

我们这里用 `AtomicReference`：
- `setActiveSink(null)` 要能"原子地"取消
- 回调线程读到的 sink 永远是"那一刻的最新值"

---

## 3. 阶段 1 —— 实现 SamplesReadyCallback 接口（空实现）

### 3.1 本阶段目标
让这个类能被 builder 接受，编译通过。

### 3.2 完整代码

```java
package com.example.chapter07;

import org.webrtc.audio.JavaAudioDeviceModule;

public class WebRtcAudioRecordSamplesBridge
        implements JavaAudioDeviceModule.SamplesReadyCallback {
    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        // 暂不处理
    }
}
```

### 3.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements SamplesReadyCallback` | 能被 builder 接受 |
| `onWebRtcAudioRecordSamplesReady` 空实现 | 编译通过 |

### 3.5 阶段 1 后能干嘛
- 注入到 builder 不报错
- 但回调里的数据被丢弃，业务拿不到

---

## 4. 阶段 2 —— 加 AtomicReference sink 字段

### 4.1 本阶段目标
加一个可切换的"输出目标"字段。

### 4.2 遇到的新问题
**问题 1：sink 是什么类型？**
- 答：`ConcurrentLinkedQueue<byte[]>`
- 业务层提供一个队列，bridge 把数据拷贝进去
- 用 `ConcurrentLinkedQueue` 是因为它线程安全

**问题 2：为什么 AtomicReference 包一层？**
- 答：业务层可能随时调 `setActiveSink(null)` 关闭录音
- WebRTC 线程在回调里要读"当前 sink"
- 必须原子地切换引用

### 4.3 完整代码

```java
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

private final AtomicReference<ConcurrentLinkedQueue<byte[]>> mActiveSink = new AtomicReference<>(null);
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `AtomicReference<ConcurrentLinkedQueue<byte[]>>` | 线程安全的 sink 引用 |
| 初始值 `null` | 启动时无 sink = 不入队 |

---

## 5. 阶段 5 —— 加 setActiveSink 方法

### 5.1 本阶段目标
让业务层能切换 sink。

### 5.2 完整代码

```java
public void setActiveSink(ConcurrentLinkedQueue<byte[]> sink) {
    mActiveSink.set(sink);  // 原子地切换
    if (sink != null) {
        Log.i(TAG, "Active sink set, will enqueue PCM samples");
    } else {
        Log.i(TAG, "Active sink cleared, samples will be dropped");
    }
}
```

### 5.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `setActiveSink` 方法 | 业务层控制开关 |
| `mActiveSink.set(...)` | 原子切换 |
| 日志 | 调试用 |

---

## 6. 阶段 6 —— 加采样率/声道探测字段

### 6.1 本阶段目标
让业务层能拿到"WebRTC 实际用的采样率和声道数"。

### 6.2 遇到的新问题
**问题 1：为什么要探测？**
- 答：业务层在录音时需要知道"每秒多少字节"才能写 wav header。
- 采样率 / 声道数**第一次回调时才能确定**。
- 第一次回调前是 0。

**问题 2：为什么用 volatile？**
- WebRTC 线程写，业务线程读
- volatile 保证可见性

### 6.3 完整代码

```java
private volatile int mSampleRate = 0;
private volatile int mChannelCount = 0;
private volatile int mLastFrameBytes = 0;
```

---

## 7. 阶段 7 —— 实现 onWebRtcAudioRecordSamplesReady（主体）

### 7.1 本阶段目标
实现真正的回调方法：探测 + 入队。

### 7.2 完整代码

```java
@Override
public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
    mTotalFramesObserved.incrementAndGet();

    // 第一次回调时探测
    if (mSampleRate == 0 && samples.getSampleRate() > 0) {
        mSampleRate = samples.getSampleRate();
        mChannelCount = samples.getChannelCount();
        Log.i(TAG, "First callback: sampleRate=" + mSampleRate + " channels=" + mChannelCount);
    }

    // 读 sink
    ConcurrentLinkedQueue<byte[]> sink = mActiveSink.get();
    if (sink == null) {
        mTotalFramesDropped.incrementAndGet();
        return;
    }

    // 数据拷贝 + 入队
    byte[] data = samples.getData();
    if (data == null || data.length == 0) {
        return;
    }
    mLastFrameBytes = data.length;

    byte[] copy = new byte[data.length];
    System.arraycopy(data, 0, copy, 0, data.length);
    sink.offer(copy);
}
```

---

## 8. 阶段 8 —— 第一次回调探测采样率/声道

### 8.1 本阶段目标
说明为什么要探测，以及怎么探测。

### 8.2 遇到的新问题
**问题 1：为什么 `mSampleRate == 0` 是"第一次"标志？**
- 答：初始化时是 0，第一次回调后变成实际值。
- 用 `==` 比较是 volatile int 的安全用法。

**问题 2：为什么要 `samples.getSampleRate() > 0` 防御？**
- 万一 WebRTC 给的 sampleRate 是 0（异常情况），我们不要写入
- 保留 0 表示"未探测到"

### 8.3 完整代码（已在阶段 7 中）

---

## 9. 阶段 9 —— 数据拷贝与入队

### 9.1 本阶段目标
把 PCM 数据拷贝到 byte[]，入队。

### 9.2 遇到的新问题
**问题 1：为什么不能直接 `sink.offer(samples.getData())`？**
- 答：samples.getData() 返回的 byte[] 是 **WebRTC 内部复用的 buffer**。
- WebRTC 会在下一帧复用这块内存（覆盖数据）。
- 如果我们直接入队引用，下游读取时可能读到被覆盖的数据。
- **必须拷贝一份**。

**问题 2：`System.arraycopy` vs 循环？**
- `System.arraycopy` 用 native 代码实现，比 Java 循环快 ~10 倍。

### 9.3 完整代码（已在阶段 7 中）

---

## 10. 阶段 10 —— 加原子计数器（统计）

### 10.1 本阶段目标
加两个统计计数器：总回调帧数 + 总丢弃帧数。

### 10.2 遇到的新问题
**问题 1：为什么用 AtomicLong？**
- 答：跨线程递增，普通 long++ 不原子。
- `incrementAndGet()` 原子地 +1。

### 10.3 完整代码

```java
import java.util.concurrent.atomic.AtomicLong;

private final AtomicLong mTotalFramesObserved = new AtomicLong(0);
private final AtomicLong mTotalFramesDropped = new AtomicLong(0);
```

---

## 11. 阶段 11 —— 加 resetState + getter

### 11.1 本阶段目标
加业务层需要的辅助方法。

### 11.2 完整代码

```java
public void resetState() {
    mSampleRate = 0;
    mChannelCount = 0;
    mLastFrameBytes = 0;
    mTotalFramesObserved.set(0);
    mTotalFramesDropped.set(0);
}

public int getSampleRate() { return mSampleRate; }
public int getChannelCount() { return mChannelCount; }
public int getLastFrameBytes() { return mLastFrameBytes; }
public long getTotalFramesObserved() { return mTotalFramesObserved.get(); }
public long getTotalFramesDropped() { return mTotalFramesDropped.get(); }
```

### 11.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `resetState` | 重新录音时清零状态 |
| 5 个 getter | 业务层读状态 |

---

## 12. 完整最终代码对照

到阶段 11 为止，与 `app/src/main/java/com/example/chapter07/WebRtcAudioRecordSamplesBridge.java` 完全一致。所有代码已在前面 11 个阶段给出。

---

## 13. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空实现 callback | 8 | builder 能接受 |
| 2 | AtomicReference sink | 12 | 线程安全 sink |
| 3 | setActiveSink | 20 | 业务控制开关 |
| 4 | 探测字段 | 24 | 第一次回调信息 |
| 5 | 主体实现 | 60 | 真正工作 |
| 6 | 探测逻辑 | （在5里） | 拿到 sampleRate |
| 7 | 数据拷贝 | （在5里） | 防止 buffer 复用 |
| 8 | 统计计数器 | 70 | 调试统计 |
| 9 | reset + getter | 135 | 业务层接口 |

---

## 14. 常见问答

### Q1：为什么用 `AtomicReference` 而不是 `volatile Queue`？
A：两者都能用，但 `AtomicReference` 更明确表达"原子切换引用"的意图，且能复用 CAS 操作（虽然这里没用到）。

### Q2：每次都 `new byte[]` 不会浪费内存吗？
A：会。每帧 ~960 字节 × 30 fps = 28KB/s。
生产环境可以考虑用"环形缓冲池"复用 byte[]，减少 GC 压力。
但当前实现优先保证正确性（不漏数据），性能优化留到后面。

### Q3：为什么 mLastFrameBytes 是 volatile？
A：WebRTC 线程写，业务线程读（同 mSampleRate）。
volatile 保证读到的总是最新值。

### Q4：resetState 必须在 setActiveSink(null) 之后调吗？
A：建议的顺序是：
```java
bridge.setActiveSink(null);  // 停止入队
Thread.sleep(100);            // 等正在执行的回调跑完
bridge.resetState();          // 重置状态
```

### Q5：能不能用 synchronized 替代 AtomicReference？
A：可以，但锁的开销大。`AtomicReference` 是无锁的，单次读写更快。

### Q6：业务层怎么停止录音？
A：调 `bridge.setActiveSink(null)`。下一帧回调时，sink 就是 null，bridge 走"丢弃"分支，不再入队。

### Q7：bridge 创建后必须立即注入到 builder 吗？
A：可以后注入，但必须在 `JavaAudioDeviceModule.createAudioDeviceModule()` 之前。
一旦 `createAudioDeviceModule()` 调用，回调就启动了，再注入就晚了。

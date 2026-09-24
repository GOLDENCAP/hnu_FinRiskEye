# 从零实现 YuvFrameProcessor —— 渐进式构建指南

> **目标读者**：理解 WebRTC `VideoSink` 接口基本概念、对 YUV 数据格式有印象的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/YuvFrameProcessor.java`（200 行）从"空类"演进到当前规模。这是一个**装饰器**模式的实现 —— 把 WebRTC 视频帧拦截下来转成 YUV 交给业务层处理，再传回给下游。
>
> **与 YuvFrameProcessorWithEffect 的关系**：本类是"基础版"（只能透传或交给回调），加上特效处理就是 WithEffect 版（详见 `YuvFrameProcessorWithEffect_从零实现指南.md`）。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空类实现 VideoSink](#3-阶段-1--空类实现-videosink)
4. [阶段 2 —— 加下游 sink + 构造方法](#4-阶段-2--加下游-sink--构造方法)
5. [阶段 3 —— 加 YuvFrameCallback 字段 + setYuvFrameCallback](#5-阶段-3--加-yuvframecallback-字段--setyuvframecallback)
6. [阶段 4 —— 实现 onFrame：快速路径（无回调直接透传）](#6-阶段-4--实现-onframe快速路径无回调直接透传)
7. [阶段 5 —— 慢速路径：toI420 转换](#7-阶段-5--慢速路径toi420-转换)
8. [阶段 6 —— 分配 byte[] 缓冲区（按 stride 大小）](#8-阶段-6--分配-byte-缓冲区按-stride-大小)
9. [阶段 7 —— 从 ByteBuffer 读出数据到 byte[]](#9-阶段-7--从-bytebuffer-读出数据到-byte)
10. [阶段 8 —— 调用业务回调](#10-阶段-8--调用业务回调)
11. [阶段 9 —— 透传原帧 vs 构造新帧](#11-阶段-9--透传原帧-vs-构造新帧)
12. [阶段 10 —— 实现 SimpleI420Buffer](#12-阶段-10--实现-simplei420buffer)
13. [阶段 11 —— 释放 i420Buffer](#13-阶段-11--释放-i420buffer)
14. [完整最终代码对照](#14-完整最终代码对照)
15. [演进回顾表](#15-演进回顾表)
16. [常见报错对照表](#16-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**实现 `VideoSink` 接口，把 WebRTC 的视频帧拦截下来，转成 YUV byte[] 交给业务层回调处理，再把处理后的帧传给下游 sink**。

为什么要这么做？因为：
- WebRTC 的视频帧默认走它自己的渲染管线
- 我们想在帧送到屏幕前**插一脚**（加水印 / 加滤镜 / 做 AI 识别）
- 但又不想破坏原有的链路（还要把帧送到下游）

这就是 **装饰器模式**：
```
[WebRTC VideoTrack] → [本类 Processor] → [下游 Sink]
                          ↑
                      [业务回调]
```

---

## 2. 前置知识速查

### 2.1 WebRTC VideoSink 接口

```java
public interface VideoSink {
    void onFrame(VideoFrame frame);
}
```

任何 `VideoSink` 实例都可以 `addSink()` 到 `VideoTrack`，每帧视频数据会回调 `onFrame()`。

### 2.2 装饰器模式

- 不改变原接口
- 在调用前后加自己的逻辑
- 把调用转发给被装饰对象（这里是下游 sink）

### 2.3 VideoFrame 和 I420Buffer

```java
VideoFrame {
    VideoFrame.Buffer getBuffer();  // 拿到 buffer
    int getRotation();              // 旋转角度
    long getTimestampNs();          // 时间戳
}

VideoFrame.Buffer.toI420() → I420Buffer  // 转成 I420 格式
```

I420 = YUV420 planar = Y 平面 + U 平面 + V 平面。

### 2.4 ByteBuffer 的 position / limit

- `position`：下次读/写的位置
- `limit`：读/写的终点
- `rewind()`：position = 0，limit 不变
- `remaining()`：还剩多少可读 = limit - position

WebRTC 给的 ByteBuffer 可能 position != 0（之前被人读过），所以读前必须 `rewind()`。

### 2.5 stride 是什么

- 每行像素在内存里占的字节数
- 可能 ≥ width（有 padding 时）
- 处理时**必须按 stride 算大小**，不能用 width

---

## 3. 阶段 1 —— 空类实现 VideoSink

### 3.1 本阶段目标
让这个类能被 `addSink()` 到 WebRTC VideoTrack。

### 3.2 遇到的新问题
**问题 1：为什么要 implements VideoSink？**
- 答：VideoTrack.addSink 接受 `VideoSink` 参数。
- 我们的类实现了这个接口，就能 addSink。

**问题 2：onFrame() 不实现会怎样？**
- 编译失败 —— 必须实现接口的所有方法。

### 3.3 完整代码

```java
package com.example.chapter07;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class YuvFrameProcessor implements VideoSink {
    @Override
    public void onFrame(VideoFrame frame) {
        // 暂不处理
    }
}
```

### 3.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `implements VideoSink` | 能被 addSink |
| `onFrame` 空实现 | 编译通过 |

### 3.5 阶段 1 后能干嘛
- 可以 `videoTrack.addSink(new YuvFrameProcessor(...))`
- 但帧不会往下游传 —— 业务会"卡住"（没有画面）

---

## 4. 阶段 2 —— 加下游 sink + 构造方法

### 4.1 本阶段目标
让本类能"装饰"一个下游 sink，构造时传进来。

### 4.2 遇到的新问题
**问题 1：为什么要下游 sink？**
- 装饰器模式的核心：**最终把请求转发给被装饰对象**。
- 我们的处理完帧后，必须传给下游，否则画面卡住。

**问题 2：构造时强制传，还是允许 null？**
- 当前实现：**强制传**（构造参数里就要求）。
- 这样下游 sink 永远不会 null，省去一堆 null 检查。

### 4.3 完整代码

```java
public class YuvFrameProcessor implements VideoSink {
    private final VideoSink mTargetSink;

    public YuvFrameProcessor(VideoSink targetSink) {
        mTargetSink = targetSink;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 暂时直接透传
        mTargetSink.onFrame(frame);
    }
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mTargetSink` 字段 | 保存下游引用 |
| 构造方法 | 注入下游 sink |
| `mTargetSink.onFrame(frame)` | 透传 |

### 4.5 阶段 2 后能干嘛
- 完整的"装饰器 + 透传"：上游来帧 → 直接透传给下游
- 业务上等价于"什么都没做"，但链路通了

---

## 5. 阶段 3 —— 加 YuvFrameCallback 字段 + setYuvFrameCallback

### 5.1 本阶段目标
加一个可注入的 YUV 回调，业务层能塞进来自己的逻辑。

### 5.2 遇到的新问题
**问题 1：回调字段为什么不是 final？**
- 答：回调要可替换（可能在不同场景下换不同实现）。
- 用普通字段 + setter 注入。

**问题 2：回调为 null 怎么办？**
- onFrame 时检查 `if (mCallback == null)`，走快速透传路径。

### 5.3 完整代码

```java
private YuvFrameCallback mCallback;

public void setYuvFrameCallback(YuvFrameCallback callback) {
    mCallback = callback;
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mCallback` 字段 | 保存回调引用 |
| `setYuvFrameCallback` | 注入回调 |

---

## 6. 阶段 4 —— 实现 onFrame：快速路径（无回调直接透传）

### 6.1 本阶段目标
没回调时走最快的路径，不做任何处理。

### 6.2 遇到的新问题
**问题 1：为什么要快速路径？**
- 性能优化。
- 没回调时（没业务需求），根本不需要 toI420、分配 byte[]、复制数据。
- 直接 `mTargetSink.onFrame(frame)` 就完事，省一大堆 CPU。

### 6.3 完整代码

```java
@Override
public void onFrame(VideoFrame frame) {
    if (mCallback == null) {
        // 快速路径：没回调时直接透传
        mTargetSink.onFrame(frame);
        return;
    }
    // ... 慢速路径（下一步）
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `if (mCallback == null)` 快速路径 | 性能优化 |

---

## 7. 阶段 5 —— 慢速路径：toI420 转换

### 7.1 本阶段目标
把任意 buffer 转成 I420 格式，方便后续按 Y/U/V 处理。

### 7.2 遇到的新问题
**问题 1：为什么必须转 I420？**
- WebRTC 的 buffer 可能是 I420、NV21、RGB 等等
- I420 是"planar YUV"，最容易按三个平面分别处理
- 业务回调的 `onYuvFrame(y, u, v, ...)` 也是按 I420 的签名设计的

**问题 2：toI420 会自动转换吗？**
- 答：会！WebRTC 内部会根据源格式做转换。
- 转换会有少量 CPU 开销，但比业务自己处理省心。

**问题 3：try/finally 包住的原因？**
- 答：i420Buffer 是 native 内存，必须 release。
- 用 try/finally 保证无论中间出啥异常都能 release。

### 7.3 完整代码

```java
VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
try {
    // ... 处理逻辑
} finally {
    i420Buffer.release();
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `frame.getBuffer().toI420()` | 拿到 YUV420 planar buffer |
| `try/finally` | 保证释放 native 内存 |

---

## 8. 阶段 6 —— 分配 byte[] 缓冲区（按 stride 大小）

### 8.1 本阶段目标
按当前帧的 stride 大小分配 Y/U/V 三个 byte[]。

### 8.2 遇到的新问题
**问题 1：为什么用 byte[] 而不是 ByteBuffer？**
- 业务回调的签名是 `byte[]`，业务层写起来更方便
- ByteBuffer 在 Java 里 API 繁琐（要管 position/limit）

**问题 2：为什么要"复用"缓冲区？**
- 每帧都 new byte[] 会造成 GC 压力
- 视频是 30 FPS，每秒 30 次分配
- 复用：只在分辨率变化时才重新分配

**问题 3：什么是 "按 stride 大小"？**
- Y 平面：`byte[strideY * height]`
- UV 平面：`byte[strideUV * (height+1)/2]`
- 不能按 `width * height` 算 —— 如果 stride > width 会有数组越界

### 8.3 完整代码

```java
int width = i420Buffer.getWidth();
int height = i420Buffer.getHeight();
int yStride = i420Buffer.getStrideY();
int uvStride = i420Buffer.getStrideU();
int uvHeight = (height + 1) / 2;

int strideYSize = yStride * height;
int strideUVSize = uvStride * uvHeight;

// 按需分配
if (mYData == null || mYData.length < strideYSize) {
    mYData = new byte[strideYSize];
    mUData = new byte[strideUVSize];
    mVData = new byte[strideUVSize];
    mCurrentWidth = width;
    mCurrentHeight = height;
}
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 5 个变量（width/height/strides/uvHeight） | 拿到分辨率和步长 |
| `mYData.length < strideYSize` 判断 | 按需重新分配 |
| 三个 `new byte[]` | 缓冲区复用 |

---

## 9. 阶段 7 —— 从 ByteBuffer 读出数据到 byte[]

### 9.1 本阶段目标
把 native ByteBuffer 里的数据拷贝到 Java byte[]。

### 9.2 遇到的新问题
**问题 1：为什么先 `rewind()`？**
- ByteBuffer 是有状态的：position 表示"下次读的位置"
- WebRTC 给的 ByteBuffer 可能 position 不是 0（之前被读过）
- rewind 把 position 重置到 0，从头开始读

**问题 2：为什么要 `remaining()` 检查？**
- 防御性：如果 buffer 的实际数据不够（极端情况），强读会抛异常
- 用 `Math.min(remaining, strideYSize)` 兜底

### 9.3 完整代码

```java
ByteBuffer yBuffer = i420Buffer.getDataY();
ByteBuffer uBuffer = i420Buffer.getDataU();
ByteBuffer vBuffer = i420Buffer.getDataV();

yBuffer.rewind();
uBuffer.rewind();
vBuffer.rewind();

if (yBuffer.remaining() < strideYSize) {
    int actualReadYSize = Math.min(yBuffer.remaining(), strideYSize);
    yBuffer.get(mYData, 0, actualReadYSize);
} else {
    yBuffer.get(mYData, 0, strideYSize);
}

// U/V 平面同理
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 三个 `rewind()` | 重置 position |
| `remaining()` 检查 | 防越界 |
| `Math.min(remaining, stride)` | 兜底 |

---

## 10. 阶段 8 —— 调用业务回调

### 10.1 本阶段目标
调 `mCallback.onYuvFrame(...)`，让业务层处理 YUV 数据。

### 10.2 遇到的新问题
**问题 1：为什么传 stride 而不是 width？**
- 业务层要按 stride 拷贝 / 修改数据
- 传 stride 而不是 width 是惯例

**问题 2：回调返回的 boolean 干嘛用？**
- true = 我改了数据，请重新构造帧
- false = 我没改，直接透传原帧

### 10.3 完整代码

```java
boolean modified = mCallback.onYuvFrame(mYData, mUData, mVData,
        width, height, yStride, uvStride);
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mCallback.onYuvFrame(...)` | 调用业务 |
| `boolean modified` | 记住是否修改 |

---

## 11. 阶段 9 —— 透传原帧 vs 构造新帧

### 11.1 本阶段目标
根据 `modified` 决定怎么传给下游 sink。

### 11.2 遇到的新问题
**问题 1：未修改时为什么要 `frame.retain()`？**
- 答：WebRTC 的 VideoFrame 用引用计数管理生命周期。
- 直接把同一个 frame 传给下游，下游"消费完"会 release，引用计数归零。
- 但我们的 onFrame 还没退出，frame 不能被释放。
- **retain() 让引用计数 +1，下游 release 后还剩 1，本类的 frame 引用依然有效**。

**问题 2：修改后为什么 release？**
- 答：构造的新 VideoFrame 没人 retain。
- 我们传给下游，下游不 release 就内存泄漏。
- 实际上，下游通常会 retain 自己的引用。
- 但当前实现选择 release —— 假设下游会 retain。

### 11.3 完整代码

```java
if (modified) {
    // 构造新帧
    SimpleI420Buffer modifiedBuffer = new SimpleI420Buffer(
            mYData, mUData, mVData, width, height, yStride, uvStride);
    VideoFrame modifiedFrame = new VideoFrame(modifiedBuffer,
            frame.getRotation(), frame.getTimestampNs());
    mTargetSink.onFrame(modifiedFrame);
    modifiedFrame.release();
} else {
    // 透传原帧
    frame.retain();
    mTargetSink.onFrame(frame);
}
```

### 11.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `if (modified)` 分支 | 性能优化（未改就透传） |
| `frame.retain()` | 引用计数 +1 |
| `modifiedFrame.release()` | 释放我们创建的帧 |

---

## 12. 阶段 10 —— 实现 SimpleI420Buffer

### 12.1 本阶段目标
写一个简单的 I420Buffer 实现，把 byte[] 包装成 WebRTC 期望的接口。

### 12.2 遇到的新问题
**问题 1：为什么要 `SimpleI420Buffer`？**
- 答：WebRTC 的 VideoFrame 构造需要 `VideoFrame.Buffer`。
- 我们修改了 Y/U/V 数据，需要把这些数据包装成新的 Buffer 给下游。
- 直接复用原来的 i420Buffer 不行（它指向的是原始数据）。

**问题 2：`getDataY/U/V` 为什么每次都新建 ByteBuffer？**
- 答：避免引用泄露。
- 如果 `getDataY()` 直接 wrap 我们的 byte[]，下游会持有这个 byte[] 的引用。
- 下一帧我们改 byte[]，下游就看到了错误的数据。
- 每次新建 + 拷贝，保证下游拿到的是**那一刻的快照**。

**问题 3：`allocateDirect` 是什么？**
- 分配 native 堆外内存（不受 GC 管理）
- WebRTC 的 native 层读这种 buffer 效率最高

### 12.3 完整代码

```java
private static class SimpleI420Buffer implements VideoFrame.I420Buffer {
    private final byte[] yData, uData, vData;
    private final int width, height, yStride, uvStride;

    SimpleI420Buffer(byte[] y, byte[] u, byte[] v,
                     int width, int height, int yStride, int uvStride) {
        this.yData = y; this.uData = u; this.vData = v;
        this.width = width; this.height = height;
        this.yStride = yStride; this.uvStride = uvStride;
    }

    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }

    @Override public ByteBuffer getDataY() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(yData.length);
        buffer.put(yData);
        buffer.rewind();
        return buffer;
    }
    // getDataU / getDataV 类似

    @Override public int getStrideY() { return yStride; }
    @Override public int getStrideU() { return uvStride; }
    @Override public int getStrideV() { return uvStride; }

    @Override public VideoFrame.I420Buffer toI420() { return this; }
    @Override public void retain() {}
    @Override public void release() {}

    @Override
    public VideoFrame.Buffer cropAndScale(...) { return null; }
}
```

### 12.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `SimpleI420Buffer` 内部类 | 把 byte[] 包装成 I420Buffer |
| `getDataY/U/V` 每次新建 ByteBuffer | 避免引用泄露 |
| `retain/release` 空实现 | byte[] 由 GC 管理 |

---

## 13. 阶段 11 —— 释放 i420Buffer

### 13.1 本阶段目标
在 try/finally 里确保 i420Buffer 被释放。

### 13.2 遇到的新问题
**问题 1：为什么必须 release？**
- 答：i420Buffer 是 native 内存。
- 不 release 会导致 native 堆泄漏，Android Profiler 能看到 Native Heap 增长。

### 13.3 完整代码

```java
} finally {
    i420Buffer.release();
}
```

---

## 14. 完整最终代码对照

到阶段 11 为止，与 `app/src/main/java/com/example/chapter07/YuvFrameProcessor.java` 完全一致。所有代码已在前面 11 个阶段给出。

---

## 15. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 实现 VideoSink | 10 | 能 addSink |
| 2 | 下游 sink + 构造 | 20 | 装饰器骨架 |
| 3 | YuvFrameCallback 字段 | 28 | 业务注入点 |
| 4 | 快速路径 | 35 | 性能优化 |
| 5 | toI420 + try/finally | 50 | YUV 转换 + 内存安全 |
| 6 | 分配 byte[] | 70 | 缓冲区复用 |
| 7 | ByteBuffer 读取 | 95 | 数据拷贝 |
| 8 | 业务回调 | 105 | 业务层接入 |
| 9 | 透传/构造帧分支 | 130 | 帧生命周期 |
| 10 | SimpleI420Buffer | 180 | byte[] 包装 |
| 11 | release | 200 | 内存释放 |

**关键里程碑**：
- 阶段 4 = 装饰器性能优化
- 阶段 8 = 业务层接入点
- 阶段 9 = 帧生命周期正确

---

## 16. 常见报错对照表

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `IllegalStateException: Buffer is released` | 5 | i420Buffer 用了两次。确保只 release 一次 |
| 画面卡住 | 4 | 快速路径忘了调 `mTargetSink.onFrame(frame)` |
| 画面黑屏 | 9 | 透传时忘了 `frame.retain()` |
| `ArrayIndexOutOfBoundsException` | 8 | 用了 width 而不是 stride 算 byte[] 大小 |
| Native Heap 持续增长 | 13 | i420Buffer 没 release。检查 try/finally |
| YUV 数据错位 | 7 | 忘了 `rewind()` 或 stride 用错 |
| `retain() not invoked` | 12 | SimpleI420Buffer 的 retain 实现成空方法，下游会找不到引用 |

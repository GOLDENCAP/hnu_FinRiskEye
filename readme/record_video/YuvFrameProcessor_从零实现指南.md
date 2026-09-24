# 从零实现 YuvFrameProcessor —— 渐进式构建指南

> **目标读者**：会用 Java、知道 WebRTC 的 `VideoSink` 是什么，但不知道"如何在 WebRTC 视频管线中间拦截、改写、并继续传递帧"的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/YuvFrameProcessor.java`（246 行）从"空类"逐步演进到当前规模。每一阶段只做一件事，做完之后你能编译、能运行、能验证；再进入下一阶段。
>
> **本文不教**：WebRTC `VideoSink` 接口本身 / Java 装饰器模式 / I420 颜色格式 / Java NIO ByteBuffer 的入门（默认你已经知道它们的"是什么"）。本文教的是：**当你已经知道单个零件之后，怎么把它们拼成一个能拦截 YUV 数据流的处理器，以及拼装顺序为什么是这个**。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 仅占位的 VideoSink 空类](#3-阶段-1--仅占位的-videosink-空类)
4. [阶段 2 —— 直接透传（最小可用透传器）](#4-阶段-2--直接透传最小可用透传器)
5. [阶段 3 —— 引入 YuvFrameCallback 接口 + 注册方法](#5-阶段-3--引入-yuvframecallback-接口--注册方法)
6. [阶段 4 —— 转换 I420 并提取宽高（不读数据）](#6-阶段-4--转换-i420-并提取宽高不读数据)
7. [阶段 5 —— 读取 YUV 数据到 byte[]（无步长处理）](#7-阶段-5--读取-yuv-数据到-byte无步长处理)
8. [阶段 6 —— 步长对齐 + 复用 byte[] 缓冲](#8-阶段-6--步长对齐--复用-byte-缓冲)
9. [阶段 7 —— 引入回调路径（boolean 返回值分流）](#9-阶段-7--引入回调路径boolean-返回值分流)
10. [阶段 8 —— 未修改透传路径：`frame.retain()` 后转发](#10-阶段-8--未修改透传路径frameretain-后转发)
11. [阶段 9 —— 已修改路径：构造 SimpleI420Buffer + 新 VideoFrame](#11-阶段-9--已修改路径构造-simplei420buffer--新-videoframe)
12. [阶段 10 —— SimpleI420Buffer 防御性拷贝 + 收尾](#12-阶段-10--simplei420buffer-防御性拷贝--收尾)
13. [完整最终代码对照](#13-完整最终代码对照)
14. [演进回顾表](#14-演进回顾表)
15. [常见报错对照表](#15-常见报错对照表)
16. [附录：相关文件 + 与姊妹指南的对照](#16-附录相关文件--与姊妹指南的对照)

---

## 1. 这个类到底要做什么

一句话：**在 WebRTC 视频管线中拦截 `VideoFrame`，把它的 YUV 数据以 byte[] 形式暴露给业务层（让业务层能读 / 能改），然后再把处理后的帧传递给下游的 `VideoSink`，整个过程对调用方完全透明。**

为什么要"拦截"？因为有些业务需求（比如加水印、加美颜、做 AI 推理后覆盖）需要拿到原始 YUV 数据，但 WebRTC 没暴露任何"在管线中间插入处理"的 API。

怎么办？用**装饰器模式（Decorator Pattern）**：
- 我们自己也实现 `VideoSink` 接口；
- 上游把帧传给我们；
- 我们处理完再传给下游；
- 上游下游完全不知道中间多了一个环节。

但装饰器不是简单"包一层"——它涉及**两个非常容易错的细节**：

| 难点 | 后果 |
|------|------|
| WebRTC `VideoFrame` 是 native 引用计数 | 漏 release → native 内存泄漏；漏 retain → 下游访问已释放内存 |
| I420 有 stride（行步长）的概念 | 用 `width` 而非 `stride` 计算 buffer 大小 → 数据错位、画面花屏、崩溃 |

后面 10 个阶段就是在**逐个解决这两个问题**，每一步只解决一个。

---

## 2. 前置知识速查

### 2.1 装饰器模式在 WebRTC 管线中的位置

```
   VideoTrack.addSink(processor)         // 上游
            │
            ▼
   YuvFrameProcessor.onFrame(frame)     // ← 我们在这里
            │
            ├─ (无 callback) → 直接透传
            │
            └─ (有 callback) → 提取 I420 → 业务回调
                                    │
                                    ├─ false → retain 原帧 → 透传
                                    │
                                    └─ true  → 构造新帧 → 透传
            │
            ▼
   mTargetSink.onFrame(...)             // 下游（比如 RemoteVideoRecorder）
```

### 2.2 VideoFrame / I420Buffer / 引用计数

WebRTC 的 `VideoFrame` 内部用 native 内存，**不能依赖 GC 回收**，必须手动管理引用计数：

| 方法 | 作用 |
|------|------|
| `frame.getBuffer()` | 取内部 Buffer（`VideoFrame.Buffer` 接口） |
| `buffer.toI420()` | 转成 `I420Buffer`（会**拷贝**数据；新对象必须 `release`） |
| `frame.retain()` | 引用计数 +1（**别处还要用**当前 frame 时调） |
| `frame.release()` | 引用计数 -1；降到 0 时 native 内存释放 |
| `buffer.release()` | 释放 toI420 返回的 I420Buffer |

**核心规则**：
- 谁 new（toI420）谁 release；
- 谁 retain 谁 release；
- 同一个对象不能 release 两次。

### 2.3 I420 / stride 速记

```
Y 平面（width × height）：
┌─────────────────────────┐ ←─ strideY >= width，可能有 padding
│ 像素 (0,0)              │
│ ...                     │
│ 像素 (0,height-1)       │
└─────────────────────────┘

U 平面（uvWidth × uvHeight）：
┌──────────┐ ←─ uvStride >= uvWidth，可能有 padding
│ ...      │
└──────────┘

V 平面同上
```

- `width * height` = 实际像素字节数（不包含 padding）
- `strideY * height` = **buffer 实际占用**的字节数（包含 padding）
- 拷贝数据时必须按 `stride * height` 拷；不能用 `width * height`

---

## 3. 阶段 1 —— 仅占位的 VideoSink 空类

### 3.1 本阶段目标
能编译、能 `new YuvFrameProcessor(sink)` 调用，`VideoTrack.addSink(processor)` 不报 ClassCastException。

### 3.2 完整代码

新建文件 `YuvFrameCallback.java`（虽然本阶段还没用，先建好）：

```java
package com.example.chapter07;

/**
 * YUV 帧回调接口。返回 true 表示数据已被修改，需要构造新帧透传。
 */
public interface YuvFrameCallback {
    boolean onYuvFrame(byte[] y, byte[] u, byte[] v,
                       int width, int height,
                       int yStride, int uvStride);
}
```

`YuvFrameProcessor.java`：

```java
package com.example.chapter07;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * YUV 帧处理器：在 WebRTC 视频管线中间拦截 VideoFrame，
 * 暴露给业务层处理后再传递给下游 VideoSink。
 * 当前阶段：仅占位，暂不实际处理。
 */
public class YuvFrameProcessor implements VideoSink {

    /** 下游 sink，最终帧都传给它 */
    private final VideoSink mTargetSink;

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
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
| `mTargetSink` final | 持有下游 sink，处理完都要给它 |
| `onFrame` 空实现 | 接口要求；不实现就编译不过 |

### 3.4 阶段 1 后能干嘛
- 编译通过
- 把 processor 挂到 VideoTrack 上不会崩
- 但**所有来帧都被直接丢弃**，下游永远收不到

### 3.5 一切为啥要从这里开始
- 不实现 `VideoSink` 接口 → 编译失败
- 不持有 `mTargetSink` → 即使想透传也无路可走
- 字段先定下来，后面所有逻辑才有"锚点"

---

## 4. 阶段 2 —— 直接透传（最小可用透传器）

### 4.1 本阶段目标
把 `onFrame` 改为"直接透传给下游"，**成为一个完美的透明代理**。

### 4.2 遇到的新问题

**问题 1：透传时需要 retain 吗？**
- **不需要**。直接 `mTargetSink.onFrame(frame)` 即可。
- 原因：frame 的所有权在 WebRTC 一侧，它 retain 了一次后会再 release；我们只是中间环节，下游用完释放后计数会归零。

**问题 2：如果下游是同步处理，会不会阻塞 WebRTC 线程？**
- 会的。但这是下游的事。我们这一层**不阻塞**（不读数据、不拷贝）就赢了。

### 4.3 完整代码（到阶段 2 为止）

```java
package com.example.chapter07;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class YuvFrameProcessor implements VideoSink {

    private final VideoSink mTargetSink;

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 透传：原封不动交给下游
        mTargetSink.onFrame(frame);
    }
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mTargetSink.onFrame(frame)` | 把帧交给下游 |

### 4.5 阶段 2 后能干嘛
**第一个可工作的版本。**
- 链路是：`VideoTrack → processor → RemoteVideoRecorder`
- 业务上看，跟没有 processor 时**完全一样**
- 验证：录制仍然正常工作 → 装饰器没破坏管线

### 4.6 经验教训
**为什么这里就是最小可用？**
- 装饰器模式的一个隐藏好处：**未启用任何处理逻辑时，零开销**。
- 你的代码可以在视频链路中"占位"，等真正需要拦截时再开启 —— 不用重构。

---

## 5. 阶段 3 —— 引入 YuvFrameCallback 接口 + 注册方法

### 5.1 本阶段目标
让外部能"注册回调"，告诉 processor："以后每帧都让我看一眼"。

### 5.2 遇到的新问题

**问题 1：怎么让业务层介入？**
- 引入回调接口 `YuvFrameCallback`（阶段 1 已建）；
- 加 `setYuvFrameCallback(callback)` 方法；
- processor 内部持有这个引用（可空）。

**问题 2：回调什么时候触发？**
- `setCallback(null)`：完全透传（阶段 2 行为）
- `setCallback(非 null)`：拦截数据后调用回调

**问题 3：回调能改数据吗？**
- **能**，返回 `true` 表示已修改，需要重新构造帧传递。
- 返回 `false` 表示只是"看了看"，原帧透传即可。

### 5.3 完整代码（到阶段 3 为止）

`YuvFrameProcessor.java`：

```java
package com.example.chapter07;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class YuvFrameProcessor implements VideoSink {

    private final VideoSink mTargetSink;
    private YuvFrameCallback mCallback;  // 可空：null 表示禁用拦截

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }

    /** 注册 YUV 帧回调。传 null 表示禁用（直接透传）。 */
    public void setYuvFrameCallback(YuvFrameCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 当前阶段：仍直接透传，下一阶段才真正用上 callback
        mTargetSink.onFrame(frame);
    }
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mCallback` 字段 | 持有业务回调引用（可空） |
| `setYuvFrameCallback` | 注册/注销回调的统一入口 |

### 5.5 阶段 3 后能干嘛
- 业务层可以 `processor.setYuvFrameCallback(myCallback)`
- 但目前 callback **永远不会被调用**（onFrame 里还没用）
- 下游行为完全不变

### 5.6 关键设计：回调接口的"返回值语义"
- `boolean onYuvFrame(...)`：true = 已改，false = 未改
- 比 "out 参数 + 标记位" 更简洁
- 业务层只需写：`return false;`（只看不动）或 `return true;`（改后返回）

---

## 6. 阶段 4 —— 转换 I420 并提取宽高（不读数据）

### 6.1 本阶段目标
当 callback 非空时，把 frame 转成 I420，拿到 width/height，**仅做连通性测试**。

### 6.2 遇到的新问题

**问题 1：frame 的 buffer 一定是 I420 吗？**
- **不一定**。可能是 I420、NV12、Texture（GPU 纹理）等。
- 必须 `frame.getBuffer().toI420()` 强制转。
- 这个调用**会拷贝数据**到新 buffer（性能开销点）。

**问题 2：toI420 返回的对象归谁？**
- 归我们 —— 必须 `release()`。
- 必须放在 `try-finally` 里，防止中途抛异常导致泄漏。

**问题 3：现在不读数据，怎么让回调能跑起来？**
- 先打通"调用 → 拿到回调参数"链路
- 用占位 callback（打印 w/h）
- 数据读取留到阶段 5

### 6.3 完整代码（到阶段 4 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class YuvFrameProcessor implements VideoSink {

    private static final String TAG = "YuvFrameProcessor";

    private final VideoSink mTargetSink;
    private YuvFrameCallback mCallback;

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }

    public void setYuvFrameCallback(YuvFrameCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        // 快速路径：未注册回调 → 直接透传
        if (mCallback == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        // 转换为 I420（会拷贝数据，必须 release）
        VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            Log.i(TAG, "I420 frame: " + width + "x" + height);
        } finally {
            i420Buffer.release();
        }
    }
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mCallback == null` 提前 return | 优化：未注册时零开销 |
| `frame.getBuffer().toI420()` | 拿到 I420 buffer |
| `try-finally release` | 防止 native 内存泄漏 |
| `Log.i` 测试桩 | 验证链路（下一阶段替换为真实读数据） |

### 6.5 阶段 4 后能干嘛
- 注册 callback 后能在 logcat 看到 `I420 frame: 1280x720` 这样的输出
- **如果不打 → callback 没注册成功** 或 toI420 抛异常
- 此时下游**还收不到任何帧**（这一阶段没转发）

### 6.6 关键经验
**`try-finally` 模式**
- WebRTC 的 native buffer 走引用计数，**完全独立于 JVM GC**
- 任何路径（包括异常）都必须 release
- 这是 Java + native 混合编程最容易踩的坑

---

## 7. 阶段 5 —— 读取 YUV 数据到 byte[]（无步长处理）

### 7.1 本阶段目标
把 I420 三个平面的数据拷贝到 byte[]，**暂不考虑 stride**。

### 7.2 遇到的新问题

**问题 1：buffer 怎么读？**
- `i420Buffer.getDataY()` / `getDataU()` / `getDataV()` 返回 `ByteBuffer`
- `ByteBuffer` 用 `rewind()` + `get(byte[], offset, length)` 读取

**问题 2：每个平面多大？**
- Y 平面：`width * height` 字节
- U/V 平面：`uvWidth * uvHeight` 字节，其中 `uvWidth = (width+1)/2`，`uvHeight = (height+1)/2`

**问题 3：每帧都 new byte[] 行不行？**
- 能跑，但每秒 30 × 3 = 90 个数组，GC 压力很大
- 阶段 6 会优化；本阶段先正确性后性能

### 7.3 完整代码（到阶段 5 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;

public class YuvFrameProcessor implements VideoSink {

    private static final String TAG = "YuvFrameProcessor";

    private final VideoSink mTargetSink;
    private YuvFrameCallback mCallback;

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }

    public void setYuvFrameCallback(YuvFrameCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (mCallback == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();

            int ySize = width * height;
            int uvWidth = (width + 1) / 2;
            int uvHeight = (height + 1) / 2;
            int uvSize = uvWidth * uvHeight;

            byte[] yData = new byte[ySize];
            byte[] uData = new byte[uvSize];
            byte[] vData = new byte[uvSize];

            ByteBuffer yBuf = i420Buffer.getDataY();
            ByteBuffer uBuf = i420Buffer.getDataU();
            ByteBuffer vBuf = i420Buffer.getDataV();

            yBuf.rewind(); uBuf.rewind(); vBuf.rewind();
            yBuf.get(yData, 0, ySize);
            uBuf.get(uData, 0, uvSize);
            vBuf.get(vData, 0, uvSize);

            // 测试桩：仅打印大小
            Log.i(TAG, "YUV data: Y=" + yData.length
                    + ", U=" + uData.length + ", V=" + vData.length);

        } finally {
            i420Buffer.release();
        }
    }
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 3 个 byte[] 分配 | 容器，用于回调和后续处理 |
| `uvWidth/uvHeight` 计算 | I420 UV 平面是 Y 的一半（向上取整） |
| `rewind()` | ByteBuffer position 归 0 |
| `get(byte[], 0, len)` | 把数据从 ByteBuffer 拷到 Java 数组 |

### 7.5 阶段 5 后能干嘛
- logcat 能看到 `YUV data: Y=921600, U=230400, V=230400`（1280×720 的典型值）
- **但还没传给 callback，没转发给下游**
- 同时**没考虑 stride**，在某些机型（特别是 OpenGL 输出的纹理）会读错数据

### 7.6 经验教训
**为什么 `(width + 1) / 2` 而不是 `width / 2`？**
- 奇数宽度时（比如 721）`721/2 = 360` 但 UV 平面实际是 361 像素
- 整数除法向下取整，会少一个像素
- `(width + 1) / 2` 保证向上取整

---

## 8. 阶段 6 —— 步长对齐 + 复用 byte[] 缓冲

### 8.1 本阶段目标
- 用 `strideY * height` 而不是 `width * height` 来分配 buffer
- 不再每帧 new byte[]，改为**复用 + 按需重分配**

### 8.2 遇到的新问题

**问题 1：什么是 stride（行步长）？**
- WebRTC 的 I420 buffer 不保证 `strideY == width`
- 实际可能 `strideY = width + padding`（GPU / 硬件编解码器常做对齐）
- padding 字节在每行末尾，**拷贝时必须一起拷**，否则 UV/U 平面错位

**问题 2：怎么知道 stride？**
- `i420Buffer.getStrideY()` / `getStrideU()` / `getStrideV()`
- 一般 U/V stride 相同（`uvStride`）

**问题 3：buffer 复用策略？**
- 缓存当前 width/height（不是 stride —— stride 可能变）
- 当新帧 width/height 超过 buffer 大小时重分配
- 否则复用现有 buffer

**问题 4：复用 buffer 安全吗？**
- 如果 callback 修改了 byte[]，下一帧的 i420 数据会覆盖它
- 所以回调必须**同步**处理完（不能异步保存引用）
- 这是我们的设计前提（如果未来要异步，需要拷贝回调返回的数据）

### 8.3 完整代码（到阶段 6 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;

public class YuvFrameProcessor implements VideoSink {

    private static final String TAG = "YuvFrameProcessor";

    private final VideoSink mTargetSink;
    private YuvFrameCallback mCallback;

    // 复用缓冲区：避免每帧 new byte[]
    private byte[] mYData;
    private byte[] mUData;
    private byte[] mVData;
    private int mCurrentWidth = 0;
    private int mCurrentHeight = 0;

    public YuvFrameProcessor(VideoSink targetSink) {
        this.mTargetSink = targetSink;
    }

    public void setYuvFrameCallback(YuvFrameCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (mCallback == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int yStride = i420Buffer.getStrideY();
            int uvStride = i420Buffer.getStrideU();
            int uvHeight = (height + 1) / 2;

            // 按 stride 计算 buffer 实际大小（包含 padding）
            int strideYSize = yStride * height;
            int strideUVSize = uvStride * uvHeight;

            // 按需重分配
            if (mYData == null || mYData.length < strideYSize) {
                mYData = new byte[strideYSize];
                mUData = new byte[strideUVSize];
                mVData = new byte[strideUVSize];
                mCurrentWidth = width;
                mCurrentHeight = height;
                Log.i(TAG, "Allocated buffers: Y=" + strideYSize
                        + ", UV=" + strideUVSize);
            }

            ByteBuffer yBuf = i420Buffer.getDataY();
            ByteBuffer uBuf = i420Buffer.getDataU();
            ByteBuffer vBuf = i420Buffer.getDataV();

            yBuf.rewind(); uBuf.rewind(); vBuf.rewind();

            // 防御性：用 remaining() 兜底
            if (yBuf.remaining() < strideYSize) {
                yBuf.get(mYData, 0, Math.min(yBuf.remaining(), strideYSize));
            } else {
                yBuf.get(mYData, 0, strideYSize);
            }
            if (uBuf.remaining() < strideUVSize) {
                uBuf.get(mUData, 0, Math.min(uBuf.remaining(), strideUVSize));
            } else {
                uBuf.get(mUData, 0, strideUVSize);
            }
            if (vBuf.remaining() < strideUVSize) {
                vBuf.get(mVData, 0, Math.min(vBuf.remaining(), strideUVSize));
            } else {
                vBuf.get(mVData, 0, strideUVSize);
            }

            Log.i(TAG, "Frame " + width + "x" + height
                    + ", strideY=" + yStride + ", uvStride=" + uvStride);

        } finally {
            i420Buffer.release();
        }
    }
}
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mYData/mUData/mVData` 字段 | 复用的 byte[] 缓冲 |
| `mCurrentWidth/Height` | 跟踪当前缓冲适配的分辨率 |
| `strideYSize = yStride * height` | **关键**：按 stride 算 buffer 大小 |
| 按需分配逻辑 | 分辨率变大才重分配，减少 GC |
| `remaining()` 防御 | 防止某些异常 buffer 短于预期时崩溃 |
| `Math.min` 兜底 | 极端情况下读多少算多少 |

### 8.5 阶段 6 后能干嘛
- **这是性能与正确性的关键阶段**
- logcat 能看到 `strideY=1280, uvStride=640`（1280×720 无 padding）或 `strideY=1320`（有 padding）
- 第一次分配后，连续同分辨率帧**零 byte[] 分配**
- 缓冲区数据正确（即使有 padding）

### 8.6 关键经验
**为什么不用 `width * height` 分配？**
- 如果 stride > width，用 `width * height` 分配 buffer 会**装不下** stride 行
- 即使能装下，也**没读 padding 字节**，下一行数据会错位写入
- 实际表现：UV 平面错位 → 整画面严重偏色

**为什么可以复用 byte[]？**
- 回调必须**同步**处理数据（不能异步存引用）
- 我们整个 onFrame 都在 WebRTC 线程跑（一个线程依次处理帧），不存在并发
- 如果未来要异步，必须每次 `Arrays.copyOf` 或用队列传递

---

## 9. 阶段 7 —— 引入回调路径（boolean 返回值分流）

### 9.1 本阶段目标
真正调用 callback，根据返回值走不同路径（透传 / 修改）。

### 9.2 遇到的新问题

**问题 1：现在 callback 已经能跑，怎么和透传逻辑结合？**
- 拆成两条路径：
  - callback == null：原帧直接透传
  - callback != null：提取数据 → 回调 → 根据返回值决定

**问题 2：本阶段先打通"boolean 分流"骨架，下游还收不到**
- 阶段 7：完成 onFrame 内的 if/else 骨架（modified / unmodified）
- 阶段 8：在 unmodified 分支加 `frame.retain()` + 透传
- 阶段 9：在 modified 分支构造新 frame 透传

### 9.3 完整代码（到阶段 7 为止）

替换 `Log.i(TAG, "Frame ...")` 之后的代码：

```java
            // 调用回调，传入 Y/U/V 三个 byte[] 和元数据
            boolean modified = mCallback.onYuvFrame(
                    mYData, mUData, mVData,
                    width, height, yStride, uvStride);

            if (modified) {
                // 已修改 → 阶段 9 实现：用 mYData 等构造新帧
                Log.i(TAG, "Frame modified, but forwarding not implemented yet");
            } else {
                // 未修改 → 阶段 8 实现：retain + 透传
                Log.i(TAG, "Frame not modified, but forwarding not implemented yet");
            }
```

完整 `onFrame`：

```java
    @Override
    public void onFrame(VideoFrame frame) {
        if (mCallback == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
        try {
            // ... 阶段 6 的宽高/stride/buffer 分配/读数据代码 ...

            boolean modified = mCallback.onYuvFrame(
                    mYData, mUData, mVData,
                    width, height, yStride, uvStride);

            if (modified) {
                Log.i(TAG, "Frame modified (TODO stage 9)");
            } else {
                Log.i(TAG, "Frame not modified (TODO stage 8)");
            }
        } finally {
            i420Buffer.release();
        }
    }
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `boolean modified = mCallback.onYuvFrame(...)` | 触发业务回调 |
| `if (modified) ... else ...` | 两路分流的骨架 |
| `TODO` 日志 | 占位；阶段 8 / 9 替换为真实逻辑 |

### 9.5 阶段 7 后能干嘛
- 业务回调**真的被调用**了
- logcat 能看到 `Frame modified` 或 `Frame not modified`
- 但下游**还收不到任何帧**（两路都没实现透传）

### 9.6 设计要点
**callback 在哪个线程上被调用？**
- WebRTC 的 `onFrame` 通常在 WebRTCWorker / Encoder 线程
- 也就是说 **callback 也在那个线程上跑**
- 如果 callback 做重活，会**卡 WebRTC 视频管线**
- 业务层应该在 callback 内快速完成（或 post 到自己的线程）

---

## 10. 阶段 8 —— 未修改透传路径：`frame.retain()` 后转发

### 10.1 本阶段目标
完成 unmodified 分支：`frame.retain()` → `mTargetSink.onFrame(frame)`。

### 10.2 遇到的新问题

**问题 1：为什么需要 retain？**
- 因为**当前函数返回后，frame 会被 WebRTC release 一次**
- 我们要保留它给下游用，必须 retain +1，让计数平衡
- 下游用完后会再 release 一次，最终归零

**问题 2：不 retain 会怎样？**
- 下游开始用 frame 时，frame 可能已被 release
- 访问 native 内存 → 崩溃或花屏
- **这是装饰器模式最隐蔽的 bug**

**问题 3：不 release 行不行？**
- 不行，会内存泄漏
- 规则：**谁 retain 谁 release**

### 10.3 完整代码（到阶段 8 为止）

替换 `Log.i(TAG, "Frame not modified (TODO stage 8)")`：

```java
            } else {
                // ===== 未修改：透传原帧 =====
                // 必须 retain：当前函数返回后 WebRTC 会 release 一次
                frame.retain();
                mTargetSink.onFrame(frame);
            }
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `frame.retain()` | 引用计数 +1，让下游用完才归零 |
| `mTargetSink.onFrame(frame)` | 下游收到原帧 |

### 10.5 阶段 8 后能干嘛
**最小可用版本：只读路径打通了！**
- 注册一个永远 `return false` 的 callback
- 下游（RemoteVideoRecorder）能收到原始帧
- 验证：录制仍然能正常工作 → **未修改透传链路正确**

### 10.6 引用计数可视化

```
时刻   WebRTC 内部计数     我们做了什么        下游做了什么
─────────────────────────────────────────────────────────
T0     1                  (WebRTC 刚创建)
T1     1                  进入我们的 onFrame
T2     1                  callback 返回 false
T3     2                  frame.retain()    ← 我们加一
T4     2                  mTargetSink.onFrame()
T5     2                                     下游处理中
T6     2                                     (下游用完 release)
T7     1                                     (假设下游 release 一次)
T8     1                  函数返回
T9     0                  WebRTC 自己的 release（计数归零）✓
```

**关键**：`retain` 让下游使用期间计数 ≥ 1，避免访问已释放内存。

---

## 11. 阶段 9 —— 已修改路径：构造 SimpleI420Buffer + 新 VideoFrame

### 11.1 本阶段目标
完成 modified 分支：用修改后的 byte[] 构造新的 `VideoFrame`，转发给下游。

### 11.2 遇到的新问题

**问题 1：怎么用 byte[] 构造 VideoFrame？**
- VideoFrame 构造函数需要 `VideoFrame.Buffer`
- I420Buffer 是 Buffer 的子接口
- 我们要用 byte[] 实现一个 `I420Buffer`，**包装**我们的数据

**问题 2：为什么不在 SimpleI420Buffer 里直接保存 byte[] 引用？**
- 因为 WebRTC 可能持有这个 buffer 的引用，并在我们不知道的地方读
- 如果它读时我们刚好覆盖 byte[]（下一帧来了），数据会错
- 所以 SimpleI420Buffer 的 `getDataY/U/V` 每次**新建 ByteBuffer 并拷贝**（防御性）

**问题 3：retain/release 在 SimpleI420Buffer 里怎么处理？**
- 我们的 byte[] 走 Java GC
- 所以 `retain/release` 是 **no-op**
- 但 cropAndScale / toI420 必须实现（返回 self 即可）

### 11.3 完整代码（到阶段 9 为止）

新增内部类（放在 YuvFrameProcessor 类内）：

```java
    /**
     * 简单的 I420Buffer 实现：把 byte[] 包装成 WebRTC 期望的 I420Buffer 接口。
     * 每次 getDataY/U/V 都新建一个 ByteBuffer（拷贝数据），
     * 这是为了避免 WebRTC 持有我们的内部 byte[] 引用导致并发问题。
     */
    private static class SimpleI420Buffer implements VideoFrame.I420Buffer {
        private final byte[] yData;
        private final byte[] uData;
        private final byte[] vData;
        private final int width;
        private final int height;
        private final int yStride;
        private final int uvStride;

        SimpleI420Buffer(byte[] y, byte[] u, byte[] v,
                         int width, int height,
                         int yStride, int uvStride) {
            this.yData = y;
            this.uData = u;
            this.vData = v;
            this.width = width;
            this.height = height;
            this.yStride = yStride;
            this.uvStride = uvStride;
        }

        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }

        @Override
        public ByteBuffer getDataY() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(yData.length);
            buffer.put(yData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public ByteBuffer getDataU() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(uData.length);
            buffer.put(uData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public ByteBuffer getDataV() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(vData.length);
            buffer.put(vData);
            buffer.rewind();
            return buffer;
        }

        @Override public int getStrideY() { return yStride; }
        @Override public int getStrideU() { return uvStride; }
        @Override public int getStrideV() { return uvStride; }

        @Override public VideoFrame.I420Buffer toI420() { return this; }
        @Override public void retain() {}      // 引用计数：byte[] 由 GC 管理
        @Override public void release() {}     // 引用计数：同上

        @Override
        public VideoFrame.Buffer cropAndScale(int cropX, int cropY,
                                              int cropWidth, int cropHeight,
                                              int scaleWidth, int scaleHeight) {
            return null;  // 暂不实现
        }
    }
```

替换 `Log.i(TAG, "Frame modified (TODO stage 9)")`：

```java
            if (modified) {
                // ===== 已修改：构造新帧 =====
                SimpleI420Buffer modifiedBuffer = new SimpleI420Buffer(
                        mYData, mUData, mVData,
                        width, height, yStride, uvStride);
                VideoFrame modifiedFrame = new VideoFrame(
                        modifiedBuffer,
                        frame.getRotation(),
                        frame.getTimestampNs());
                mTargetSink.onFrame(modifiedFrame);
                modifiedFrame.release();  // 下游通常会 retain 自己的引用
            }
```

### 11.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 内部类 `SimpleI420Buffer` | 把 byte[] 暴露成 WebRTC 期望的接口 |
| `getDataY/U/V` 每次新建 ByteBuffer + 拷贝 | 防止 WebRTC 持有内部 byte[] 引用导致并发错乱 |
| `toI420() = this` | 已是 I420，避免再次转换 |
| `retain/release` no-op | byte[] 由 GC 管 |
| `cropAndScale = null` | 简化，不实现裁剪缩放 |
| `new VideoFrame(buffer, rot, ts)` | 用新 buffer 构造新帧（保留旋转和时间戳） |
| `modifiedFrame.release()` | 我们创建的帧，引用计数归我们管 |

### 11.5 阶段 9 后能干嘛
**最小可用版本完整！**
- 未修改路径：透传
- 已修改路径：用 byte[] 重建帧
- 业务层可以开始写真实处理逻辑（水印、滤镜、AI 推理输出覆盖）

### 11.6 关键经验
**为什么 modifiedFrame 也要 release？**
- 我们 new 出来的帧计数 = 1
- `mTargetSink.onFrame()` 一般会**同步消费**（直接处理完）
- 所以我们 `onFrame` 返回前 release 是安全的
- 如果下游有异步消费，下游必须自己 retain —— 这是 WebRTC 的契约

**为什么 SimpleI420Buffer 的 getDataY 要拷贝？**
- 因为我们持有了 mYData 的引用，**下一帧**会覆盖它
- 如果 WebRTC 内部把 ByteBuffer 存起来延迟读 → 读到的是下一帧数据
- 每次新建 ByteBuffer + 拷贝 → WebRTC 拿到的是当时点的快照

---

## 12. 阶段 10 —— SimpleI420Buffer 防御性拷贝 + 收尾

### 12.1 本阶段目标
本阶段实际上主要做"打磨"：
- 确保所有路径都正确处理引用计数
- 加上字段注释
- 检查边界情况

### 12.2 本阶段具体做的事

**1. `YuvFrameProcessor` 完整 final 代码已与文件一致**

**2. `SimpleI420Buffer` 已经全部实现**
- 阶段 9 已经完成所有方法
- 阶段 10 主要确认每个方法的语义都正确

**3. 文档化每个字段**

我们在字段上方的注释是当前文件已有的"使用指南"：
- 装饰器模式（mTargetSink）
- 可选回调（mCallback）
- 复用缓冲区（mYData/mUData/mVData）
- 当前分辨率（mCurrentWidth/mCurrentHeight）

这些注释不是写代码时才加的，是**事后回顾**才看出价值 —— 因为读者打开文件第一眼要理解"这 5 个字段分两组，各自干什么"。

### 12.3 阶段 10 后能干嘛
**与最终文件完全一致**：
- 类 JavaDoc 描述工作流程（5 步）
- 字段注释解释每个字段的作用
- 实现完整的装饰器 + 引用计数 + 步长处理

### 12.4 经验教训
**什么时候算"完整"？**
- 不仅是逻辑正确，还要"读得懂"
- 字段 / 方法的注释帮助几个月后的自己快速捡起来
- 当前文件的注释密度大约是 1 行注释 / 3 行代码 —— 偏多，但适合教学

---

## 13. 完整最终代码对照

到阶段 10 为止，代码已经和文件 `app/src/main/java/com/example/chapter07/YuvFrameProcessor.java` 完全一致。下面是按行号 + 阶段映射的关系：

| 阶段 | 文件中对应的内容 | 行号（参考） |
|------|----------------|------------|
| 1 | `package` / `import` / 类声明 + `VideoSink` + `mTargetSink` + 构造函数 + 空 `onFrame`；配套 `YuvFrameCallback.java` 创建 | 1-5, 23-37, 45-49, 63-65 |
| 2 | `onFrame` 改为 `mTargetSink.onFrame(frame)` 直接透传 | 63-65 |
| 3 | `mCallback` 字段 + `setYuvFrameCallback` 方法 | 26, 55-57 |
| 4 | `mCallback == null` 早返回 + `frame.getBuffer().toI420()` + try/finally + 取宽高 | 67-82 |
| 5 | 3 个 byte[] + uvWidth/uvHeight + 读 byte[] 到数组（无 stride） | 90-114 |
| 6 | stride 处理 + 复用 mYData/mUData/mVData + 按需重分配 + remaining 兜底 | 27-32, 91-145 |
| 7 | 在 onFrame 中调 `mCallback.onYuvFrame(...)` + boolean 分流骨架 | 147-156 |
| 8 | unmodified 分支：`frame.retain()` + 透传 | 152-156 |
| 9 | modified 分支 + 内部类 `SimpleI420Buffer` 完整实现 | 158-167, 161-242 |
| 10 | 字段注释 + 类 JavaDoc（最终打磨） | 7-22, 25-37 |

最终代码全文已在阶段 9 的代码块 + 现有文件中给出，结构完全一致。

---

## 14. 演进回顾表

| 阶段 | 加了什么 | 行数变化 | 解决的痛点 | 阶段末能干嘛 |
|------|---------|---------|-----------|------------|
| 1 | 空骨架 + `mTargetSink` + `YuvFrameCallback` 接口 | ~25 | 占位，明确输入 | 编译通过 |
| 2 | `onFrame` 透传 | ~30 | 透明代理 | 链路不破坏 |
| 3 | `mCallback` + `setCallback` | ~40 | 注册/注销回调 | 接口就绪 |
| 4 | `toI420` + try/finally + 取宽高 | ~55 | 验证 toI420 链路 | logcat 能打宽高 |
| 5 | 3 个 byte[] + 读 Y/U/V | ~90 | 拿到数据 | **数据已读出来** |
| 6 | stride + 复用 buffer + 按需分配 | ~140 | 正确性 + 性能 | 同分辨率零 GC |
| 7 | callback 调用 + boolean 分流 | ~155 | 业务介入 | 两条路径骨架 |
| 8 | retain + 透传（未修改） | ~155 | **未修改链路完成** | **未修改可用** |
| 9 | SimpleI420Buffer + 新 VideoFrame | ~245 | **修改链路完成** | **完整可用** |
| 10 | 字段注释 + JavaDoc | ~246 | 可读性 | 与当前文件完全一致 |

**关键里程碑**：
- **阶段 2** = 第一个不破坏管线的透明代理
- **阶段 5** = 数据真的能拿出来看
- **阶段 6** = 性能 + 正确性（stride）
- **阶段 8** = 未修改路径完整（只读场景可用）
- **阶段 9** = 完整可用（含修改场景）

---

## 15. 常见报错对照表

写代码时遇到下面任何一种错误，对照表看哪里出问题：

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `inconvertible types: ... cannot be cast to VideoSink` | 1 | 没 `implements VideoSink` |
| `onFrame` 编译错误 | 1 | 漏实现 `VideoSink.onFrame()` 方法 |
| toI420 抛异常 / 闪退 | 4 | 某些 WebRTC 版本可能不支持某种 buffer 类型；用 `instanceof VideoFrame.I420Buffer` 检查 |
| native 内存泄漏 / OOM | 4 | `try-finally` 没包住 `i420Buffer.release()` |
| 数据看起来"花屏"或偏色 | 5/6 | UV 平面错位 → 用 `width * height` 算大小了；改用 `stride * height` |
| `BufferUnderflowException` / `BufferOverflowException` | 5/6 | `get(byte[], offset, len)` 的 len 超过 buffer 实际剩余；用 `remaining()` 兜底 |
| 录制画面"撕裂"或"错行" | 6 | 没读 stride padding 字节 → UV 平面错位 |
| 每秒卡顿 / 频繁 GC | 5 → 6 | 没复用 byte[]；阶段 6 之后消失 |
| 下游拿到 frame 时崩溃 | 8 | **漏了 `frame.retain()`** —— 这是装饰器模式最常见的 bug |
| 内存持续增长 | 8 | **漏了 `frame.retain()` 但下游也 release 了** → 计数错乱；或 modified 路径漏 release |
| 下游画面颜色错乱 | 9 | SimpleI420Buffer 的 `getDataY/U/V` 顺序写错；或 stride 传错 |
| `IllegalStateException: VideoFrame released` | 9 | 构造新 VideoFrame 后又 release 了 i420Buffer 之前的位置冲突；确认 try-finally 在最外层 |
| `NullPointerException` on downstream sink | 9 | `mTargetSink` 是 null；构造函数必须传非空 sink |
| 同一帧被处理两次 | 8 | `frame.retain()` 后没及时 release（或反之）；检查 `frame.retain` 配对 |
| 异步回调时拿到旧数据 | 6/9 | 回调线程在异步跑时，下一帧已覆盖 mYData；用 `Arrays.copyOf` 或锁 |
| 回调返回 true 但下游看到原帧 | 9 | modified 路径没走；检查 `boolean modified = mCallback.onYuvFrame(...)` |
| 回调返回 false 但下游看到新帧 | 8 | unmodified 路径走错了；检查 retain + 透传 |

---

## 16. 附录：相关文件 + 与姊妹指南的对照

### 16.1 相关文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/example/chapter07/YuvFrameProcessor.java` | 本指南所构建的类，246 行最终形态 |
| `app/src/main/java/com/example/chapter07/YuvFrameCallback.java` | 配套回调接口（1 个方法） |
| `app/src/main/java/com/example/chapter07/RemoteVideoRecorder.java` | 下游 sink 示例（用 processor 包装后录到 MP4） |
| `app/src/main/java/com/example/chapter07/peer.java` | 在 Builder / 按钮回调处实例化 processor 的入口 |
| `readme/record_audio/WebRtcAudioTrackReflector_从零实现指南.md` | 姊妹指南 1（10 阶段，反射获取 PCM） |
| `readme/record_video/RemoteVideoRecorder_从零实现指南.md` | 姊妹指南 2（14 阶段，EGL/OpenGL/MP4） |

### 16.2 与姊妹指南的对照

| 维度 | 音频指南（WebRtcAudioTrackReflector） | 视频录制指南（RemoteVideoRecorder） | 本指南（YuvFrameProcessor） |
|------|------------------------------------|----------------------------------|---------------------------|
| 总阶段数 | 10 | 14 | 10 |
| 核心机制 | Java 反射 | MediaCodec Surface 输入 + OpenGL | 装饰器模式 + 引用计数 |
| 关键 API | `Class.forName` / `getDeclaredField` | `eglMakeCurrent` / `glDrawArrays` / `eglSwapBuffers` | `frame.retain()`/`release()` / `toI420()` |
| 跨线程 | 单线程足够 | 必须独立渲染线程（EGL Context 线程私有） | 同步处理（WebRTC 线程上跑） |
| 数据格式 | PCM byte[] | YUV 三个平面 byte[] | YUV 三个平面 byte[]（带 stride） |
| 主要难点 | 反射字段版本兼容 | EGL/GL 线程模型 | **引用计数 + stride** |
| 资源回收 | try/finally | 严格顺序：线程→EGL→Codec→Muxer | try/finally（i420Buffer 必须 release） |

### 16.3 三个指南的关系

```
peer.java
  │
  ├── 创建 JavaAudioDeviceModule (AudioDeviceModule)
  │
  ├── WebRtcAudioTrackReflector (反射拿到 playout PCM)        ← 音频指南
  │       ↓ 读 PCM
  │   RemoteAudioRecorder (编码 AAC + 写 MP4)
  │
  ├── VideoTrack.addSink(decorator)                            ← 本指南
  │       ↓
  │   YuvFrameProcessor (装饰器, 拦截 YUV)
  │       ↓
  │   RemoteVideoRecorder (EGL/GL 渲染到 MediaCodec 写 MP4)    ← 视频录制指南
  │
  └── LocalAudioRecorder + WebRtcAudioRecordSamplesBridge (本地录音)
```

**`YuvFrameProcessor` 的位置**：处于 `VideoTrack` 和 `RemoteVideoRecorder` 之间，是 **录制链路上的可选拦截点**。它单独存在时（无 callback）就是透明代理；装上 callback 后才"激活"。

### 16.4 何时该读本指南

- 你需要在视频链路上**插入自定义处理**（水印/滤镜/AI 覆盖）→ 必读
- 你在调 WebRTC 视频管线，看到 native 内存泄漏 → 必读引用计数部分
- 你看到画面颜色错位但代码看起来对 → 必读 stride 部分
- 你只关心录制 → 看视频录制指南即可
- 你只关心音频录制 → 看音频指南即可

### 16.5 与两个指南的核心哲学对比

| 指南 | 核心难点 |
|------|---------|
| 音频 | "私有成员的访问" —— 用反射绕过 |
| 视频录制 | "GPU + 编码器的协作" —— 用 EGL 桥接 |
| **本指南** | **"所有权的跨层传递" —— 用引用计数管理 native 内存** |

理解了这个核心，每个阶段的设计选择就有了解释：
- 为什么先做"透传"：因为**所有权链不能断**
- 为什么 byte[] 必须复用：性能优化
- 为什么 SimpleI420Buffer 要拷贝：防止所有权跨越
- 为什么 try/finally：native 内存独立于 GC

---

## 附录：本指南如何被使用

**第一次阅读**：按顺序读一遍，建立"为什么要这么拼"的心智模型。

**实际写代码时**：回到对应阶段复制代码块。每个代码块是**累积**的（包含之前所有阶段），可直接拷贝使用。

**调试时**：对照"常见报错对照表"，定位错误属于哪个阶段。

**回顾时**：看"演进回顾表"，理解每个阶段的目的与里程碑。

**对比时**：看 16.2 节，理解三个指南各自解决不同问题，可以单独读也可以串读。

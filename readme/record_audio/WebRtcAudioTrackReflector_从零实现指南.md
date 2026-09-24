# 从零实现 WebRtcAudioTrackReflector —— 渐进式构建指南

> **目标读者**：已经会用 Java 反射基础语法、但对 WebRTC 内部一无所知的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/WebRtcAudioTrackReflector.java`（165 行）从"空类"逐步演进到当前规模。每一阶段只做一件事，做完之后你能运行、能调试、能验证；再进入下一阶段。
>
> **阅读建议**：先按顺序读一遍，再真正动手写时回到对应阶段复制代码。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 仅占位的空类](#3-阶段-1--仅占位的空类)
4. [阶段 2 —— 反射读 audioOutput](#4-阶段-2--反射读-audiooutput)
5. [阶段 3 —— 用类名字符串拿 WebRtcAudioTrack.class](#5-阶段-3--用类名字符串拿-webrtcaudiotrackclass)
6. [阶段 4 —— 取出 nativeAudioTrack 句柄](#6-阶段-4--取出-nativeaudiotrack-句柄)
7. [阶段 5 —— 拿到 nativeGetPlayoutData 方法引用](#7-阶段-5--拿到-nativegetplayoutdata-方法引用)
8. [阶段 6 —— 拿到 byteBuffer 字段并算帧大小](#8-阶段-6--拿到-bytebuffer-字段并算帧大小)
9. [阶段 7 —— 实现 readPlayoutFrame：第一次"偷"出 PCM](#9-阶段-7--实现-readplayoutframe第一次偷出-pcm)
10. [阶段 8 —— 加入统计字段](#10-阶段-8--加入统计字段)
11. [阶段 9 —— 补全 getter API](#11-阶段-9--补全-getter-api)
12. [阶段 10 —— 加固异常处理](#12-阶段-10--加固异常处理)
13. [完整最终代码对照](#13-完整最终代码对照)
14. [演进回顾表](#14-演进回顾表)
15. [常见报错对照表](#15-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**从 WebRTC 内部的 `WebRtcAudioTrack`（包私有类）中，偷出正在送往 Android `AudioTrack` 播放的 10ms PCM 数据。**

为什么要"偷"？因为 WebRTC 没有公开的 API 能让你拿到这条数据。`WebRtcAudioTrack` 是 `org.webrtc.audio` 包里的包私有类，没法 `import`、没法直接 new、没法直接访问字段。

怎么办？用 Java 反射 —— 通过 `Class.forName` 拿到类、通过 `getDeclaredField` / `getDeclaredMethod` + `setAccessible(true)` 撬开私有成员，然后调用 native 方法 `nativeGetPlayoutData(nativeHandle, size)` 让 native 把最新一段 playout 数据拷贝到内部 `ByteBuffer`，我们再读出来。

这就是整个类的全部职责。后面 10 个阶段都在解决"具体怎么反射、怎么读"这个唯一任务。

---

## 2. 前置知识速查

### 2.1 WebRTC 内部音频走向（远端 playout）

```
网络接收 → WebRTC audio engine → playout ring buffer
                                       │
                                       ▼
                          WebRtcAudioTrack.byteBuffer (10ms PCM)
                                       │
                                       ▼
                           AudioTrack.write() → 扬声器
```

我们就在 `byteBuffer` 这一站"蹲守"，读取一份副本，不影响原 buffer 的播放。

### 2.2 反射 API 速记

| 反射操作 | 方法 |
|---------|------|
| 拿 private 字段 | `clazz.getDeclaredField("name")` |
| 拿 private 方法 | `clazz.getDeclaredMethod("name", paramTypes...)` |
| 关访问检查 | `member.setAccessible(true)` |
| 读字段值 | `field.get(obj)` / `field.getLong(obj)` |
| 调用方法 | `method.invoke(objOrNull, args...)` |

### 2.3 ByteBuffer.duplicate 语义

- 与原 buffer **共享底层数据**（同一块 native 内存）
- 但有**独立的 position / limit / mark**
- 修改视图的 position **完全不影响原 buffer**

这是后面阶段 7 解决并发 bug 的关键。

---

## 3. 阶段 1 —— 仅占位的空类

### 3.1 本阶段目标
能编译、能 `new WebRtcAudioTrackReflector(adm)` 调用，仅此而已。

### 3.2 完整代码

```java
package com.example.chapter07;

import org.webrtc.audio.JavaAudioDeviceModule;

public class WebRtcAudioTrackReflector {

    private final JavaAudioDeviceModule mAdm;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) {
        this.mAdm = adm;
    }
}
```

### 3.3 加了什么 + 为什么

| 字段 / 方法 | 作用 |
|------------|------|
| `mAdm` | 外部传入的 `JavaAudioDeviceModule`。WebRTC 所有的播放、采集对象都挂在它身上，我们必须从它身上"摸东西"。 |

### 3.4 阶段 1 后能干嘛
- 编译通过
- 没有 `@Test` 也能 new 一个实例
- `mAdm` 已经留住引用，但还没用上

### 3.5 一切为啥要从这里开始
- 写代码前先想清楚"我的输入是什么"。这里输入是 `JavaAudioDeviceModule`，因为它身上挂着 WebRTC 所有内部音频对象。
- 不放 `mAdm`，后面所有反射代码都没地方拿起点。

---

## 4. 阶段 2 —— 反射读 audioOutput

### 4.1 本阶段目标
弄清楚"播放对象到底藏在哪"。

### 4.2 遇到的新问题
**问题 1：`adm` 是个什么用？**
- `JavaAudioDeviceModule` 是个"调度外壳"，它身上有个 `private final` 字段叫 `audioOutput`，才是真正干播放活的 `WebRtcAudioTrack` 实例。

**问题 2：怎么读 private 字段？**
- 用 `getDeclaredField` 而不是 `getField`，因为 `getField` 只看 public 继承字段。
- 拿到 Field 后必须 `setAccessible(true)`，否则 `field.get(obj)` 会抛 `IllegalAccessException`。

### 4.3 完整代码（阶段 1 + 阶段 2 增量）

```java
package com.example.chapter07;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        // ===== 反射读 JavaAudioDeviceModule.audioOutput =====
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);

        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        android.util.Log.i(TAG, "audioOutput: " + audioOutput.getClass().getName());
    }
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 静态 `TAG` | 后面 log 用 |
| `mAudioOutput` 字段 | 持有真正播放对象的引用 |
| `throws Exception` | 反射调用可能抛一堆受检异常 |
| `audioOutputField.setAccessible(true)` | 关 Java 访问检查，否则读不到 private |
| `field.get(adm)` | 读出实例值 |

### 4.5 阶段 2 后能干嘛
- 拿到一个 `Object mAudioOutput`，运行时打印它的真实类名
- **这是验证反射通路是否正确的关键一步** —— 在 `Log.i` 里看到类似 `org.webrtc.audio.WebRtcAudioTrack` 就对了
- 如果报 `NoSuchFieldException`，说明 WebRTC 版本里字段改名了（这就是为什么反射写法很脆）

### 4.6 调试技巧
写完阶段 2 立刻跑一次，看 logcat 能不能打印出真实的类名。打印不出来就先不要往下写，**先解决"我能不能反射到任何东西"**，再谈细节。

---

## 5. 阶段 3 —— 用类名字符串拿 WebRtcAudioTrack.class

### 5.1 本阶段目标
拿到包私有类 `WebRtcAudioTrack` 的 `Class<?>` 对象，并校验 `mAudioOutput` 确实是它。

### 5.2 遇到的新问题
**问题 1：`WebRtcAudioTrack` 是包私有的，连 `import` 都编译不过，怎么办？**
- 答：用 `Class.forName("org.webrtc.audio.WebRtcAudioTrack")`。它走的不是编译期类型系统，而是运行期 ClassLoader，和 import 无关。

**问题 2：怎么知道 `audioOutput` 就是这个类型？**
- 答：用 `webRtcAudioTrackClass.isInstance(audioOutput)`。它是 `instanceof` 的反射版，不需要 import 也能判断。

### 5.3 完整代码（到阶段 3 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;
    private final Class<?> mWebRtcAudioTrackClass;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        // ===== 1) 反射读 audioOutput =====
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        // ===== 2) 通过字符串拿到 WebRtcAudioTrack 的 Class =====
        Class<?> cls = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        if (!cls.isInstance(audioOutput)) {
            throw new IllegalStateException(
                "audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }
        this.mWebRtcAudioTrackClass = cls;

        Log.i(TAG, "Got WebRtcAudioTrack class, instance OK");
    }
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mWebRtcAudioTrackClass` 字段 | 后面对 WebRtcAudioTrack 的所有反射操作（拿字段、拿方法）都靠它 |
| `Class.forName(...)` | 绕过 `import org.webrtc.audio.WebRtcAudioTrack` 编译失败的限制 |
| `cls.isInstance(audioOutput)` | 防御性检查：类型不匹配立刻挂 |
| `mWebRtcAudioTrackClass = cls` | 缓存避免后面每次都重新 forName |

### 5.5 阶段 3 后能干嘛
- 已经持有 `Class<?>` 对象，下一步用它取字段
- 已经做了一次类型检查 —— 如果 WebRTC 版本内部实现换了类，这里会立刻报错而不是后面崩到不知哪里

### 5.6 经验教训
**不在第 2 阶段就 `forName`**：因为如果 forName 失败但 audioOutput 没问题，你就分不清是"类路径找不到"还是"audioOutput 反射失败"。分阶段让 bug 更容易定位。

---

## 6. 阶段 4 —— 取出 nativeAudioTrack 句柄

### 6.1 本阶段目标
拿到 `WebRtcAudioTrack` 里那个 `private long nativeAudioTrack` 字段的值 —— 它是 C++ 层 `AudioTrack*` 指针的 Java 句柄。

### 6.2 遇到的新问题
**问题 1：为什么要拿这个 long？**
- 因为 WebRTC 的 native 方法 `nativeGetPlayoutData(nativeAudioTrack, sizeInBytes)` 的第一个参数就是这个句柄。
- 没这个句柄根本调不了 native。

**问题 2：用什么方法读 long？**
- 用 `getLong(obj)`，**不要**用 `get(obj)` 再强转 —— 后者会先装箱成 `Long` 再拆箱，无谓开销。
- 还少踩坑：如果字段类型不匹配，`get` 抛 `IllegalArgumentException`，但 `getLong` 不会。

### 6.3 完整代码（到阶段 4 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;
    private final long mNativeAudioTrackHandle;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        // ===== 1) 反射读 audioOutput =====
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        // ===== 2) 通过字符串拿到 WebRtcAudioTrack 的 Class =====
        Class<?> cls = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        if (!cls.isInstance(audioOutput)) {
            throw new IllegalStateException(
                "audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }

        // ===== 3) 取出 nativeAudioTrack 字段 =====
        Field nativeHandleField = cls.getDeclaredField("nativeAudioTrack");
        nativeHandleField.setAccessible(true);
        long handle = nativeHandleField.getLong(audioOutput);
        if (handle == 0L) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.nativeAudioTrack is 0 (audio track not yet created)");
        }
        this.mNativeAudioTrackHandle = handle;

        Log.i(TAG, "nativeAudioTrack handle = " + handle);
    }
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mNativeAudioTrackHandle` 字段 | 保存 native 句柄，后面 invoke 时用 |
| `cls.getDeclaredField("nativeAudioTrack")` | 拿对应的 Field 对象 |
| `field.getLong(audioOutput)` | 高效读 long（不装箱） |
| `handle == 0` 检查 | 句柄为 0 表示 C++ 还没建好 AudioTrack，常见于 WebRTC 还没开始播放 |

### 6.5 阶段 4 后能干嘛
- 已经有 native 调用所需的两个参数中的第一个（handle）
- 还需要第二步：`nativeGetPlayoutData` 方法本身的 Method 对象

### 6.6 调试技巧
拿到 handle 后打印出来。正常情况你会看到一个类似 `180535698614784` 的长整数。如果一直是 0，多半是 WebRTC 还没建好 AudioTrack，此时通常是因为对端还没发流，需要等连接建立后再 `new` 这个 Reflector。

---

## 7. 阶段 5 —— 拿到 nativeGetPlayoutData 方法引用

### 7.1 本阶段目标
缓存 `nativeGetPlayoutData(long, int)` 这个 private native 方法的 `Method` 对象。

### 7.2 遇到的新问题
**问题 1：为什么不每次 `getDeclaredMethod`？**
- 答：`getDeclaredMethod` 内部会做方法签名搜索，**很慢**。我们每 10ms 调用一次，每秒 100 次，绝对要缓存。

**问题 2：long 参数怎么传？**
- 答：用 `long.class`，不是 `Long.class`。前者是基本类型标记，后者是包装类。native 方法签名是基本类型，传包装类会在调用时报 `IllegalArgumentException: argument type mismatch`。

### 7.3 完整代码（到阶段 5 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;
    private final long mNativeAudioTrackHandle;
    private final Method mNativeGetPlayoutData;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        // ===== 1) 反射读 audioOutput =====
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        // ===== 2) 通过字符串拿到 WebRtcAudioTrack 的 Class =====
        Class<?> cls = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        if (!cls.isInstance(audioOutput)) {
            throw new IllegalStateException(
                "audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }

        // ===== 3) 取出 nativeAudioTrack 字段 =====
        Field nativeHandleField = cls.getDeclaredField("nativeAudioTrack");
        nativeHandleField.setAccessible(true);
        long handle = nativeHandleField.getLong(audioOutput);
        if (handle == 0L) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.nativeAudioTrack is 0 (audio track not yet created)");
        }
        this.mNativeAudioTrackHandle = handle;

        // ===== 4) 缓存 native 方法的 Method 对象 =====
        mNativeGetPlayoutData = cls.getDeclaredMethod(
                "nativeGetPlayoutData", long.class, int.class);
        mNativeGetPlayoutData.setAccessible(true);

        Log.i(TAG, "nativeGetPlayoutData Method cached, handle=" + handle);
    }
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mNativeGetPlayoutData` 字段 | 缓存 Method 对象，避免每帧重新查 |
| `getDeclaredMethod("nativeGetPlayoutData", long.class, int.class)` | 精确指定方法签名 |
| `setAccessible(true)` | 关访问检查 |

### 7.5 阶段 5 后能干嘛
- 构造完成后持有 4 件套：`mAudioOutput`、`mNativeAudioTrackHandle`、`mNativeGetPlayoutData`、加上第 2 步的 `cls`
- 但还没有"目标 buffer" —— 还需要知道 native 把数据写到哪个 `ByteBuffer`

---

## 8. 阶段 6 —— 拿到 byteBuffer 字段并算帧大小

### 8.1 本阶段目标
拿到 `WebRtcAudioTrack` 内的 `ByteBuffer byteBuffer` 字段引用，并探测它的大小（即一帧 10ms PCM 的字节数）。

### 8.2 遇到的新问题
**问题 1：native 方法把数据写到哪里？**
- 写到一个叫 `byteBuffer` 的私有 `ByteBuffer` 字段。这个 BB 是 WebRTC 在 `initPlayout` 时分配好的，**容量固定 = `bytesPerFrame * (sampleRate / 100)`**，也就是恰好 10ms 的 PCM 字节数。
- 例如 48kHz、16bit、mono：`48000 * 0.01 * 2 = 960` 字节。

**问题 2：怎么知道一帧多大？**
- BB 的 `capacity()` 返回的就是这个固定值。

**问题 3：为什么 BB 也是 private？**
- 答：WebRTC 内部用它做"中转"—— native 写入 → Java 读出 → 送进 AudioTrack。整个过程对外部不可见，所以我们也得反射。

### 8.3 完整代码（到阶段 6 为止）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;
    private final long mNativeAudioTrackHandle;
    private final Method mNativeGetPlayoutData;
    private final Field mByteBufferField;

    private final int mFrameSizeInBytes;
    private final int mSamplesPerFrame;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        // ===== 1) 反射读 audioOutput =====
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        // ===== 2) 通过字符串拿到 WebRtcAudioTrack 的 Class =====
        Class<?> cls = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        if (!cls.isInstance(audioOutput)) {
            throw new IllegalStateException(
                "audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }

        // ===== 3) 取出 nativeAudioTrack 字段 =====
        Field nativeHandleField = cls.getDeclaredField("nativeAudioTrack");
        nativeHandleField.setAccessible(true);
        long handle = nativeHandleField.getLong(audioOutput);
        if (handle == 0L) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.nativeAudioTrack is 0 (audio track not yet created)");
        }
        this.mNativeAudioTrackHandle = handle;

        // ===== 4) 缓存 native 方法的 Method 对象 =====
        mNativeGetPlayoutData = cls.getDeclaredMethod(
                "nativeGetPlayoutData", long.class, int.class);
        mNativeGetPlayoutData.setAccessible(true);

        // ===== 5) 拿到 byteBuffer 字段 =====
        mByteBufferField = cls.getDeclaredField("byteBuffer");
        mByteBufferField.setAccessible(true);
        ByteBuffer bb = (ByteBuffer) mByteBufferField.get(audioOutput);
        if (bb == null) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.byteBuffer is null (initPlayout not yet called)");
        }

        // ===== 6) 计算一帧大小 =====
        mFrameSizeInBytes = bb.capacity();
        // 假设 mono 16bit：1 sample = 2 bytes
        mSamplesPerFrame = mFrameSizeInBytes / 2;

        Log.i(TAG, "Ready. handle=" + handle
                + ", frameSizeBytes=" + mFrameSizeInBytes
                + ", samplesPerFrame=" + mSamplesPerFrame);
    }
}
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mByteBufferField` 字段 | 缓存 byteBuffer 字段的引用，**但不缓存 BB 实例**——WebRTC 可能重新分配 |
| `mFrameSizeInBytes` 字段 | 10ms 帧的字节数（外部用来分配 buffer） |
| `mSamplesPerFrame` 字段 | 10ms 帧的采样数（写 wav header 用） |
| `bb == null` 检查 | initPlayout 未调用时 BB 还是 null |

### 8.5 阶段 6 后能干嘛
- 构造时打印出"一帧 = X 字节 / Y 采样"
- 验证：48kHz/16bit/mono 时，打印应该看到 `frameSizeBytes=960, samplesPerFrame=480`
- 不一致的：16k 应该是 320 字节 / 160 采样；8k 应该是 160/80

### 8.6 关键设计：缓存 Field，不缓存 BB 实例
注意代码里 `mByteBufferField` 是 `Field`，但 `mFrameSizeInBytes` 是 `int` —— BB 容量已缓存，BB 实例本身不缓存。
**为什么？**因为 WebRTC 在某些情况下（比如重新协商、采样率切换）会重新分配 BB。每次调用 `mByteBufferField.get(audioOutput)` 重新拿，才不会用到旧的 BB 实例。

---

## 9. 阶段 7 —— 实现 readPlayoutFrame：第一次"偷"出 PCM

### 9.1 本阶段目标
写一个 `readPlayoutFrame(byte[] buffer)` 方法，每次调用把"最新一帧 10ms PCM"写入外部传入的 buffer，返回实际写入字节数。

### 9.2 遇到的新问题
**问题 1：怎么把上面所有零件串起来？**
- 步骤：每次调用时
  1. 重新 `field.get()` 拿到最新的 BB
  2. 调 `nativeGetPlayoutData(null, handle, size)` 让 native 写入 BB
  3. 从 BB 里把数据拷到 byte[]
  4. 返回实际拷了多少

**问题 2：native 方法 invoke 的第一个参数为什么是 null？**
- 因为 `nativeGetPlayoutData` 虽然定义在实例方法位置上，但实际是 `static native`（C++ 实现里就是静态函数，不依赖 `this`）。
- 传 `mAudioOutput` 反而会错（C++ 层通过参数 `long nativeAudioTrack` 取对象）。

**问题 3：怎么读 BB 而不破坏 WebRTC 自己的播放？**
- 这是**最关键的坑**。绝对不要 `bb.position(0); bb.get(...)` 这样写 —— WebRTC 自己的 `AudioTrackJavaThread` 也用这个 BB，它期待 position 永远保持 0（或者某个特定状态）。
- 必须用 `bb.duplicate()` 创建共享底层内存但独立 position 的视图。

**问题 4：duplicate view 读完后要不要复位？**
- 不要复位。每帧开始时 view 自己 `position(0)` 即可。重要的是**永远不动原 bb 的 position**。

### 9.3 完整代码（到阶段 7 为止 —— 最小可用版本）

```java
package com.example.chapter07;

import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class WebRtcAudioTrackReflector {

    private static final String TAG = "WebRtcAudioTrackReflector";

    private final JavaAudioDeviceModule mAdm;
    private final Object mAudioOutput;
    private final long mNativeAudioTrackHandle;
    private final Method mNativeGetPlayoutData;
    private final Field mByteBufferField;

    private final int mFrameSizeInBytes;
    private final int mSamplesPerFrame;

    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;

        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        audioOutputField.setAccessible(true);
        Object audioOutput = audioOutputField.get(adm);
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        this.mAudioOutput = audioOutput;

        Class<?> cls = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        if (!cls.isInstance(audioOutput)) {
            throw new IllegalStateException(
                "audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }

        Field nativeHandleField = cls.getDeclaredField("nativeAudioTrack");
        nativeHandleField.setAccessible(true);
        long handle = nativeHandleField.getLong(audioOutput);
        if (handle == 0L) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.nativeAudioTrack is 0 (audio track not yet created)");
        }
        this.mNativeAudioTrackHandle = handle;

        mNativeGetPlayoutData = cls.getDeclaredMethod(
                "nativeGetPlayoutData", long.class, int.class);
        mNativeGetPlayoutData.setAccessible(true);

        mByteBufferField = cls.getDeclaredField("byteBuffer");
        mByteBufferField.setAccessible(true);
        ByteBuffer bb = (ByteBuffer) mByteBufferField.get(audioOutput);
        if (bb == null) {
            throw new IllegalStateException(
                "WebRtcAudioTrack.byteBuffer is null (initPlayout not yet called)");
        }
        mFrameSizeInBytes = bb.capacity();
        mSamplesPerFrame = mFrameSizeInBytes / 2;

        Log.i(TAG, "Ready. handle=" + handle
                + ", frameSizeBytes=" + mFrameSizeInBytes
                + ", samplesPerFrame=" + mSamplesPerFrame);
    }

    /**
     * 抓取最新一帧（10ms）的 PCM 数据。
     *
     * @param buffer 输出缓冲区，长度必须 >= getFrameSizeInBytes()
     * @return 实际写入的字节数；返回 0 表示 native 侧暂时没数据
     */
    public int readPlayoutFrame(byte[] buffer) {
        if (mAudioOutput == null || mNativeAudioTrackHandle == 0L) {
            return 0;
        }

        try {
            ByteBuffer bb = (ByteBuffer) mByteBufferField.get(mAudioOutput);
            if (bb == null) return 0;

            int sizeToRead = mFrameSizeInBytes;
            if (sizeToRead > buffer.length) {
                return 0;
            }

            // 让 native 把最新 playout PCM 写入 bb
            mNativeGetPlayoutData.invoke(null, mNativeAudioTrackHandle, sizeToRead);

            // 关键：用 duplicate() 创建视图，绝不修改原 bb 的 position
            ByteBuffer view = bb.duplicate();
            view.position(0);
            int got = Math.min(sizeToRead, view.remaining());
            view.get(buffer, 0, got);

            return got;
        } catch (Exception e) {
            Log.e(TAG, "readPlayoutFrame failed", e);
            return 0;
        }
    }

    public int getFrameSizeInBytes() { return mFrameSizeInBytes; }
}
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `readPlayoutFrame(byte[])` | **第一次真正"偷"到数据** |
| `bb = (ByteBuffer) field.get(mAudioOutput)` | 每次都重读 BB（防 WebRTC 重新分配） |
| `method.invoke(null, ...)` | native 方法是静态的，第一个参数传 null |
| `bb.duplicate()` | 关键！共享底层数据但有独立 position |
| `Math.min(...)` | 防御性编程：绝不超过 buffer 实际可读字节 |
| try/catch + return 0 | 任何异常都不能崩录音循环 |

### 9.5 阶段 7 后能干嘛
**这是最小可用版本。** 调用方只要：
```java
byte[] frame = new byte[reflector.getFrameSizeInBytes()];
while (recording) {
    int n = reflector.readPlayoutFrame(frame);
    if (n > 0) {
        // 写 wav / 编码 / 入队
    }
}
```
就能持续拿到 10ms PCM。

### 9.6 必须理解的 BB 并发模型

```
| 时刻 | 线程 | 操作 | 原 bb.position | view.position |
|------|------|------|---------------|---------------|
| T1 | AudioTrackThread | AudioTrack.write(bb, 960) | 0 → 960 | — |
| T2 | AudioTrackThread | bb.rewind() | 960 → 0 | — |
| T3 | 我的线程 | view = bb.duplicate() | 0 | 0 |
| T4 | 我的线程 | view.position(0); view.get(buf) | 0 | 0 → 960 |
| T5 | 我的线程 | view 返回，不再使用 | 0 | （释放） |
| T6 | AudioTrackThread | AudioTrack.write(bb, 960) | 0 → 960 | — |
```
**原 bb.position 永远被 WebRTC 自己控制。我用 view，不动原 bb。**

如果哪天你"优化"成 `bb.position(0); bb.get(...)`，会得到崩溃 `Bad position 1920/960` —— T6 时原 bb.position=960，再 write 会推到 1920，超过 limit=960，崩。

---

## 10. 阶段 8 —— 加入统计字段

### 10.1 本阶段目标
让外部能监控"这次读了多少 / 累计读了多少 / 是否丢帧"。

### 10.2 遇到的新问题
**问题：调用方怎么知道录音是否健康？**
- 健康指标：上一帧实际读了多少字节（理论上固定 = 960）、累计多少字节（用于估算时长 / 文件大小）。
- 没有统计字段就只能"盲录"，出了问题也不知道。

### 10.3 需要新增的代码
（只在已有代码里加 3 行 + 改 3 行）

在字段区：
```java
private int mLastReadSize = 0;
private long mTotalBytesRead = 0;
```

在 `readPlayoutFrame` 里返回前：
```java
mLastReadSize = got;
mTotalBytesRead += got;
return got;
```

### 10.4 阶段 8 后能干嘛
- 配合阶段 9 的 getter，外部可以做实时监控面板、估算录音时长、做丢帧检测。

---

## 11. 阶段 9 —— 补全 getter API

### 11.1 本阶段目标
把内部元数据开放给外部，让调用方不用反射也能问到一帧的字节数 / 采样数 / 时长 / 累计字节数。

### 11.2 遇到的新问题
**问题：为什么需要这么多 getter？**
- `RemoteAudioRecorder` 需要 `getFrameSizeInBytes()` 来分配 buffer；写 wav header 需要 `getSamplesPerFrame()`；估算时长需要 `getFrameDurationUs()`；调试面板需要 `getLastReadSize()` / `getTotalBytesRead()`。
- 不暴露这些，外部只能用反射回到我们这里 —— 没意义。

### 11.3 需要新增的代码

```java
public int getFrameSizeInBytes() { return mFrameSizeInBytes; }
public int getSamplesPerFrame() { return mSamplesPerFrame; }

private static final long FRAME_DURATION_US = 10000L;  // 10ms

public long getFrameDurationUs() { return FRAME_DURATION_US; }
public int getLastReadSize()    { return mLastReadSize; }
public long getTotalBytesRead() { return mTotalBytesRead; }
```

### 11.4 阶段 9 后能干嘛
- 这个类变成一个"自描述"的组件：拿到实例不需要再反射任何东西。
- 调用方可以靠这些 getter 完成自身的设计（如 `new byte[reflector.getFrameSizeInBytes()]`）。

---

## 12. 阶段 10 —— 加固异常处理

### 12.1 本阶段目标
让 `readPlayoutFrame` 在任何反射异常下都能安全返回 0，绝不向调用方抛异常。

### 12.2 遇到的新问题
**问题：为什么必须吞掉异常？**
- 因为录音循环是每 10ms 调一次的高频调用；
- 一旦这里抛异常，录音循环就退出了，整个录音任务终止；
- 而且大概率会让 activity / UI 跟着崩。

**问题：什么样的异常可能发生？**
- `IllegalAccessException`：理论上不会（已在构造时 setAccessible），但 JVM 安全管理器仍可能拦；
- `IllegalArgumentException`：参数不对（理论上不会，因为我们控制了所有参数）；
- `InvocationTargetException`：native 方法内部抛了什么；
- `NullPointerException`：BB 在我们 `field.get` 之后被 WebRTC 置 null（极端情况）。

### 12.3 已经写好的部分
阶段 7 已经写了 try/catch + return 0 的兜底逻辑。这里只要确保**任何 catch 都只 log 不抛**，并且 `readPlayoutFrame` 没有声明 `throws` 即可。

### 12.4 阶段 10 后能干嘛
代码进入"工程化"状态，可以放心地高频调用。

---

## 13. 完整最终代码对照

到阶段 10 为止，代码已经和文件 `app/src/main/java/com/example/chapter07/WebRtcAudioTrackReflector.java` 完全一致。下面是按行号 + 阶段映射的关系：

| 阶段 | 文件中对应的内容 | 行号（参考） |
|------|----------------|------------|
| 1 | `package` / `import` / 类声明 / `mAdm` 字段 / 构造函数占位 | 1-22 |
| 2 | 反射读 `audioOutput` + TAG + mAudioOutput | 19, 24, 47-58 |
| 3 | `Class.forName("...WebRtcAudioTrack")` + isInstance | 61-67 |
| 4 | `nativeAudioTrack` 反射 + mNativeAudioTrackHandle | 26, 70-80 |
| 5 | `nativeGetPlayoutData` 缓存 + mNativeGetPlayoutData | 27, 83-87 |
| 6 | `byteBuffer` 缓存 + mByteBufferField + mFrameSizeInBytes + mSamplesPerFrame | 28, 30-32, 89-99 |
| 7 | `readPlayoutFrame` 完整实现 | 104-143 |
| 8 | `mLastReadSize` + `mTotalBytesRead` 累加 | 33-34, 138-140 |
| 9 | 4 个 getter + FRAME_DURATION_US 常量 | 35, 144-165 |
| 10 | try/catch 已在阶段 7 写入，此阶段主要是确认 | 130, 142-143 |

最终代码全文已在阶段 9 的代码块里给出，结构和当前文件完全一致。

---

## 14. 演进回顾表

| 阶段 | 加了什么 | 行数变化 | 解决的痛点 | 阶段末能干嘛 |
|------|---------|---------|-----------|------------|
| 1 | 空骨架 + `mAdm` | ~10 | 占位，明确输入 | 编译通过 |
| 2 | 反射 `audioOutput` | ~30 | 拿到真播放对象 | 拿到 `Object mAudioOutput` |
| 3 | `Class.forName` + `isInstance` | ~40 | 绕过包私有 import 限制 | 持有 `Class<?>` 对象 |
| 4 | `nativeAudioTrack` + 句柄 | ~55 | 拿到 native 调用参数 | 持有 native 句柄 |
| 5 | `nativeGetPlayoutData` Method | ~70 | 缓存方法引用 | 持有 native 方法对象 |
| 6 | `byteBuffer` Field + 帧大小 | ~90 | 知道数据去哪、一帧多大 | 算出 960 字节/帧 |
| 7 | `readPlayoutFrame` 真正实现 | ~135 | **第一次偷到 PCM** | **最小可用版本** |
| 8 | `mLastReadSize` / `mTotalBytesRead` | ~140 | 能监控 | 加入统计 |
| 9 | 4 个 getter + FRAME_DURATION_US | ~165 | 外部能问元数据 | 类变自描述 |
| 10 | 异常兜底（阶段 7 已写好） | ~165 | 高频调用安全 | 进入工程化 |

**关键里程碑**：
- 阶段 7 = 第一个能跑通录音的最小版本
- 阶段 9 = 与当前文件结构完全一致

---

## 15. 常见报错对照表

写代码时遇到下面任何一种错误，对照表看哪里出问题：

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `NoSuchFieldException: audioOutput` | 2 | WebRTC 改字段名了。检查 `JavaAudioDeviceModule.class.getDeclaredFields()` 实际有什么 |
| `IllegalAccessException: ... module ... not open` | 任意 | JDK 9+ 模块系统。需要加 VM 参数 `--add-opens java.base/java.lang=ALL-UNNAMED` |
| `ClassNotFoundException: org.webrtc.audio.WebRtcAudioTrack` | 3 | WebRTC 版本里类路径变了。新版本可能叫 `WebRtcAudioTrackOutput` |
| `NoSuchFieldException: nativeAudioTrack` | 4 | WebRTC 改字段名。新版本可能是 `nativeAudioTrackPointer` 之类 |
| `NoSuchMethodException: nativeGetPlayoutData` | 5 | 签名变了。新版本可能是 `nativeGetPlayoutData(long)`（没有 size 参数） |
| `IllegalArgumentException: argument type mismatch` | 5 | `long.class` 写成了 `Long.class`。基本类型和包装类不能混 |
| `nativeAudioTrack is 0` | 4 | C++ 还没建 AudioTrack。等到连接建立后再 `new` |
| `byteBuffer is null (initPlayout not yet called)` | 6 | initPlayout 未调用。同样等连接建立 |
| `Bad position N/M` （应用启动后崩溃） | 7 | **用了 `bb.position(0)` 直接操作原 BB**。改回 `bb.duplicate()` |
| `readPlayoutFrame failed` 总是返回 0 | 7 | invoke 抛异常。看 logcat 具体异常类型再对照本表 |

---

## 附录：相关文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/example/chapter07/WebRtcAudioTrackReflector.java` | 本指南所构建的类，165 行最终形态 |
| `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` | 用 `readPlayoutFrame` 攒包 + AAC 编码 + MP4 封装的消费者 |
| `app/src/main/java/com/example/chapter07/peer.java` | 在 Builder / 按钮回调处实例化 Reflector 的入口 |
| `readme/record_audio/README.md` | 远端 + 本端录制的整体实现总览（含 BB 并发问题详解） |


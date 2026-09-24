# 从零实现 YuvFrameCallback —— 渐进式构建指南

> **目标读者**：写过 Java 接口回调、对 WebRTC 完全陌生的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/YuvFrameCallback.java`（12 行）从"空文件"演进到当前规模。一步一步加，每步只加一个概念。
>
> **注意**：本文件最终只是一个 **接口**，不带任何实现逻辑。理解"为什么要定义它"比代码本身更重要。

---

## 目录

1. [这个接口到底要做什么](#1-这个接口到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 一个空接口](#3-阶段-1--一个空接口)
4. [阶段 2 —— 加上一个回调方法（不传任何参数）](#4-阶段-2--加上一个回调方法不传任何参数)
5. [阶段 3 —— 把回调参数加齐](#5-阶段-3--把回调参数加齐)
6. [阶段 4 —— 加返回值（让回调能告诉处理器"我改过数据"）](#6-阶段-4--加返回值让回调能告诉处理器我改过数据)
7. [阶段 5 —— 补全方法注释](#7-阶段-5--补全方法注释)
8. [完整最终代码对照](#8-完整最终代码对照)
9. [演进回顾表](#9-演进回顾表)
10. [常见问答](#10-常见问答)

---

## 1. 这个接口到底要做什么

一句话：**定义一个"处理 YUV 视频帧"的回调协议，让业务层能在 WebRTC 视频帧里"插一脚"**（拦截 → 修改 → 还回去）。

为什么需要这个？因为 `YuvFrameProcessor`（同目录下的另一个类）实现了 WebRTC 的 `VideoSink` 接口，会在每帧视频数据到达时被调用。但它本身不应该包含业务逻辑（比如"加滤镜"、"加水印"、"做人脸识别"）。

正确的做法是：
- `YuvFrameProcessor` 只负责"拿到帧 → 调用回调 → 把回调结果传到下游"
- `YuvFrameCallback` 接口定义"回调长什么样"
- 真正的业务（滤镜/水印/人脸识别）由调用方实现 `YuvFrameCallback` 注入进去

这就是经典的 **"装饰器模式 + 回调注入"**：`YuvFrameProcessor` 是装饰器，`YuvFrameCallback` 是注入到装饰器里的回调钩子。

---

## 2. 前置知识速查

### 2.1 Java 接口基础

```java
public interface MyInterface {
    void doSomething(int x);
}
```

调用方实现接口：
```java
public class MyHandler implements MyInterface {
    @Override
    public void doSomething(int x) {
        System.out.println("Got: " + x);
    }
}
```

### 2.2 YUV 数据布局

| 平面 | 含义 | 大小 | 内容 |
|------|------|------|------|
| Y | 亮度（Luminance） | width × height | 每个像素的明暗 |
| U | 蓝色色度（Chrominance Blue） | (width/2) × (height/2) | 颜色信息（采样少） |
| V | 红色色度（Chrominance Red） | (width/2) × (height/2) | 颜色信息（采样少） |

YUV420 的特点：**U/V 平面的宽高都是 Y 平面的一半**（4:2:0 采样的含义）。

### 2.3 Stride（步长）是什么？

- 一行像素在内存里占的字节数
- 通常等于 width，但有时会**多出几个 padding 字节**对齐
- 解码 / 处理时要看 stride 而不是只看 width

例：width=1920、stride=1920 → 每行 1920 字节（无 padding）
例：width=1921、stride=1924 → 每行 1924 字节（前 3 个字节是 padding）

---

## 3. 阶段 1 —— 一个空接口

### 3.1 本阶段目标
先有一个能 `implements` 的空接口，让上下游类能先做骨架。

### 3.2 遇到的新问题
**问题 1：为什么先写接口不写实现？**
- 答：因为 `YuvFrameProcessor` 接下来就要 `setYuvFrameCallback(someCallback)`。
- 如果 `YuvFrameCallback` 不存在，处理器里的代码写不下去。
- 接口先行是"自上而下"的写法 —— 先把契约定好。

**问题 2：为什么放在同包里？**
- 答：接口和它的实现类（同目录的 `YuvFrameProcessor`）天然耦合。
- 同包让它们不必 `import`，调用时少写 `chapter07.YuvFrameCallback`。

### 3.3 完整代码

```java
package com.example.chapter07;

public interface YuvFrameCallback {
}
```

### 3.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `package com.example.chapter07;` | 与同目录其他类同包，免去 import |
| `public interface YuvFrameCallback` | 留个"槽位"给回调方法 |

### 3.5 阶段 1 后能干嘛
- 可以写 `class MyHandler implements YuvFrameCallback { }` 但什么都不用实现
- `YuvFrameProcessor.setYuvFrameCallback(...)` 可以传 `null` 占位

---

## 4. 阶段 2 —— 加上一个回调方法（不传任何参数）

### 4.1 本阶段目标
定义一个最简单的回调方法。

### 4.2 遇到的新问题
**问题 1：方法签名先定最简版还是最复杂版？**
- 答：**先定最简版**。
- 写一个无参方法 `void onFrame()`，确认接口能编译、能被调用。
- 等真正需要传参数时再加。

**问题 2：方法名怎么起？**
- 常见命名：`onFrame`、`onCallback`、`handle`、`process`
- 这里我们做的是"处理一帧 YUV"，所以 `onFrame` 或 `onYuvFrame` 都行
- 选用 `onYuvFrame`（更精确，避免和 WebRTC 的 `onFrame` 混淆）

### 4.3 完整代码

```java
package com.example.chapter07;

public interface YuvFrameCallback {
    void onYuvFrame();
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `void onYuvFrame();` | 接口的契约：每帧都会调用一次 |

### 4.5 阶段 2 后能干嘛
- 实现类可以写 `public void onYuvFrame() { /* 业务逻辑 */ }`
- 但业务里拿不到帧数据，没法处理任何东西

### 4.6 经验教训
**写接口时一定要想清楚"未来会传什么"**。
如果只写 `onYuvFrame()`，后续每加一个参数都要改接口、改所有实现类。
**所以下一步就把所有可能的参数一次性列出来**。

---

## 5. 阶段 3 —— 把回调参数加齐

### 5.1 本阶段目标
让回调能拿到所有必要的数据：Y/U/V 三个平面 + 分辨率 + 步长。

### 5.2 遇到的新问题
**问题 1：YUV 三个平面怎么传？**
- 答：三个独立的 `byte[]`，而不是合并成一个 `byte[]`。
- 因为业务层经常只需要改某一个平面（如只改 UV 实现灰度），分开传更方便。

**问题 2：为什么传 `byte[]` 而不是 `ByteBuffer`？**
- `byte[]` 对 Java 开发者更友好（直接用下标访问）
- `ByteBuffer` 是 native 接口，可能有 position 状态干扰
- 业务层改完数据后，处理器要重新读 `byte[]` 包装成 `ByteBuffer` 给 WebRTC

**问题 3：步长要不要传？**
- 答：要。
- 业务层可能做"按 stride 的内存拷贝"、"做水平方向的滤镜"等操作。
- 不传步长，业务层只能按 width 处理，遇到 stride != width 的情况就错位了。

### 5.3 完整代码

```java
package com.example.chapter07;

public interface YuvFrameCallback {
    void onYuvFrame(byte[] y, byte[] u, byte[] v,
                    int width, int height,
                    int yStride, int uvStride);
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `byte[] y` | Y 平面数据（亮度） |
| `byte[] u` | U 平面数据（蓝色色度） |
| `byte[] v` | V 平面数据（红色色度） |
| `int width` | 实际宽度（像素） |
| `int height` | 实际高度（像素） |
| `int yStride` | Y 行步长（>= width） |
| `int uvStride` | U/V 行步长（>= width/2） |

### 5.5 阶段 3 后能干嘛
- 业务层可以真正处理 YUV 数据了
- 例：实现一个灰度滤镜（把 U/V 全置 128）

---

## 6. 阶段 4 —— 加返回值（让回调能告诉处理器"我改过数据"）

### 6.1 本阶段目标
让回调返回值告诉处理器"我是否修改过数据"。

### 6.2 遇到的新问题
**问题 1：为什么需要返回值？**
- 答：性能优化。
- 如果业务层没改数据，处理器可以直接透传原帧（retain() 一下），不用构造新帧。
- 如果改了数据，处理器要构造新帧 + 重新上传纹理。
- **没改就跳过构造**，省 CPU、省内存。

**问题 2：用 `boolean` 还是 `int` 状态码？**
- `boolean`：简单，只区分"改了/没改"
- `int`：能表达更多状态（"改了"、"没改"、"出错"）
- 当前阶段选 `boolean`，因为只需要二元判断

**问题 3：如果忘了 return true 会发生什么？**
- 答：处理器以为你没改，直接透传原帧。
- 你的修改就 **不会生效**，但不会崩。
- 这是个静默 bug —— 调试时要警惕。

### 6.3 完整代码

```java
package com.example.chapter07;

public interface YuvFrameCallback {
    boolean onYuvFrame(byte[] y, byte[] u, byte[] v,
                       int width, int height,
                       int yStride, int uvStride);
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `boolean` 返回值 | 让处理器知道"是否需要构造新帧" |

### 6.5 阶段 4 后能干嘛
- 实现类可以返回 `true`（我改了）/`false`（我没改）
- 处理器根据返回值决定要不要重新构造 `VideoFrame`

### 6.6 设计取舍
**为什么不传一个 `modified` 参数（用 AtomicBoolean）？**
- 答：返回值更简洁，函数式风格。
- 但如果未来要在回调里做"异步处理 → 之后设置标志"，就得改成参数。
- 当前业务都是同步处理，返回值够用。

---

## 7. 阶段 5 —— 补全方法注释

### 7.1 本阶段目标
给方法加完整的 Javadoc 注释。

### 7.2 遇到的新问题
**问题 1：注释要写多详细？**
- 答：写"使用约束"和"返回值语义"。
- 每个参数的**含义**要写明（Y/U/V 各是什么）。
- 每个参数的**使用限制**要写明（如"u/v 数组长度是 (width/2)*(height/2)"）。
- 返回值的**契约**要写明（"true 表示已修改，需要重新渲染"）。

### 7.3 完整代码

```java
package com.example.chapter07;

public interface YuvFrameCallback {
    /**
     * 每帧YUV数据回调，支持修改
     * @param y Y平面数据（可修改）
     * @param u U平面数据（可修改）
     * @param v V平面数据（可修改）
     * @param width 宽度
     * @param height 高度
     * @param yStride Y平面步长
     * @param uvStride UV平面步长
     * @return true表示数据已修改，需要重新渲染；false表示未修改
     */
    boolean onYuvFrame(byte[] y, byte[] u, byte[] v, int width, int height, int yStride, int uvStride);
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `@param` 7 个 | 文档化每个参数的含义和约束 |
| `@return` | 文档化返回值语义 |

### 7.5 阶段 5 后能干嘛
- 写实现类时编辑器能自动补全参数名
- 其他开发者一眼看懂接口契约

---

## 8. 完整最终代码对照

到阶段 5 为止，与 `app/src/main/java/com/example/chapter07/YuvFrameCallback.java` 完全一致：

```java
package com.example.chapter07;

public interface YuvFrameCallback {
    /**
     * 每帧YUV数据回调，支持修改
     * @param y Y平面数据（可修改）
     * @param u U平面数据（可修改）
     * @param v V平面数据（可修改）
     * @param width 宽度
     * @param height 高度
     * @param yStride Y平面步长
     * @param uvStride UV平面步长
     * @return true表示数据已修改，需要重新渲染；false表示未修改
     */
    boolean onYuvFrame(byte[] y, byte[] u, byte[] v, int width, int height, int yStride, int uvStride);
}
```

---

## 9. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空接口 | 3 | 给处理器一个回调"槽位" |
| 2 | 无参方法 | 4 | 锁定方法名 `onYuvFrame` |
| 3 | 7 个参数 | 5 | 业务层能拿到所有 YUV 信息 |
| 4 | boolean 返回值 | 5 | 性能优化（未改就透传） |
| 5 | Javadoc | 12 | 文档化契约 |

**关键里程碑**：
- 阶段 3 = 业务层能真正处理数据
- 阶段 4 = 性能优化的开关
- 阶段 5 = 完整可交付的接口

---

## 10. 常见问答

### Q1：为什么 YUV 用 byte[] 而不是 int[]？
A：YUV 数据是 **8 bit 每像素**，用 byte 正好。如果用 int 会浪费 3 倍内存。

### Q2：为什么 width 单独传，yStride 也单独传？
A：width 是"有效宽度"，yStride 是"内存宽度"。两者可能不同（padding）。业务层有时按 width 处理（滤镜），有时按 yStride 处理（拷贝）。两个都给，灵活。

### Q3：UV 平面只有 width/2，为什么不传 uvWidth？
A：因为能从 width 算出来 `(width+1)/2`。少传一个参数，接口更简洁。YUV420 的格式约定让上层自己会算。

### Q4：能否传 YUV 总数据 + offsets 让调用方自己切分？
A：技术上可以。但会增加调用方的复杂度（每次都要切分）。当前方案是"已经切好直接用"，业务层写起来更轻松。

### Q5：这个接口能用 lambda 实现吗？
A：可以。`processor.setYuvFrameCallback((y,u,v,w,h,ys,uvs) -> { /* 处理 */ return true; });`
注意返回 `boolean`。

### Q6：谁会调用这个接口？
A：`YuvFrameProcessor`（同目录）。它在 `onFrame(VideoFrame)` 里把 WebRTC 帧拆成 Y/U/V 三个 byte[]，调用本接口的 `onYuvFrame`，根据返回值决定要不要构造新帧。

### Q7：业务层改完数据后，还需要做什么？
A：什么都不用做。处理器会自己根据你返回的 `true` 重新构造 `VideoFrame` 并向下游 sink 传递。

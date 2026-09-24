# 从零实现 OpenCvDemoActivity —— 渐进式构建指南

> **目标读者**：Android 入门，会写简单的 Activity，但对 OpenCV 完全陌生。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/OpenCvDemoActivity.java`（60 行）从"空 Activity"演进到能加载 OpenCV native 库、创建 Mat、并把结果显示在屏幕上的完整示例。
>
> **副产物**：同目录下还有一个 `OpenCvDemoActivity_with_comments.java`，是给每一行加了一行注释的版本。**两个文件功能完全一样**。

---

## 目录

1. [这个 Activity 到底要做什么](#1-这个-activity-到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 最简的空 Activity](#3-阶段-1--最简的空-activity)
4. [阶段 2 —— 创建一个 TextView 显示在屏幕上](#4-阶段-2--创建一个-textview-显示在屏幕上)
5. [阶段 3 —— 给 TextView 加 padding 和字号](#5-阶段-3--给-textview-加-padding-和字号)
6. [阶段 4 —— 加载 OpenCV 库](#6-阶段-4--加载-opencv-库)
7. [阶段 5 —— 加载失败的兜底](#7-阶段-5--加载失败的兜底)
8. [阶段 6 —— 创建一个 Mat 对象](#8-阶段-6--创建一个-mat-对象)
9. [阶段 7 —— 拼接显示信息（多行 StringBuilder）](#9-阶段-7--拼接显示信息多行-stringbuilder)
10. [阶段 8 —— 显示到 TextView + 释放 Mat](#10-阶段-8--显示到-textview--释放-mat)
11. [完整最终代码对照](#11-完整最终代码对照)
12. [_with_comments 版的由来](#12-_with_comments-版的由来)
13. [演进回顾表](#13-演进回顾表)
14. [常见报错对照表](#14-常见报错对照表)

---

## 1. 这个 Activity 到底要做什么

一句话：**演示 OpenCV 在 Android 上能用 —— 加载 native 库、创建一个 2×3 矩阵、把矩阵信息显示在 TextView 上**。

这是一个**最小可验证示例**：只要这个 Activity 能跑通，就证明 OpenCV native 库装好了、Java API 能调用、native 内存能管理。

---

## 2. 前置知识速查

### 2.1 OpenCV 是什么

- 开源的计算机视觉库（C/C++ 写的）
- Android 上通过 Java Native Interface (JNI) 调用
- 核心数据结构：`Mat`（矩阵）

### 2.2 Mat 是什么

- `Mat` = Matrix（矩阵）
- 可以存图像、灰度图、任意数值矩阵
- 创建时是 native 内存（堆外），**必须手动 `release()` 否则泄漏**

### 2.3 CvType 是什么

- OpenCV 的"数据类型"枚举
- 命名规则：`CV_<位数><类型><通道数>`
- 常见：
  - `CV_8UC1` = 8 位无符号（unsigned）单通道（channel = 1）= 灰度图
  - `CV_8UC3` = 8 位无符号 3 通道 = RGB/BGR 图
  - `CV_32FC1` = 32 位浮点单通道 = 灰度浮点

### 2.4 Scalar 是什么

- 一个"标量"（1~4 个数值的容器）
- 创建 Mat 时用作"初始值"
- `new Scalar(7)` 表示把矩阵里所有元素初始化为 7

### 2.5 dp / sp / px 区别

| 单位 | 含义 | 用途 |
|------|------|------|
| px | 像素 | 原始单位 |
| dp | density-independent pixels | 尺寸（不受屏幕密度影响） |
| sp | scale-independent pixels | 字体（受系统字体缩放影响） |

**24dp 转 px**：`px = dp * density`（density 是屏幕 dpi / 160）

---

## 3. 阶段 1 —— 最简的空 Activity

### 3.1 本阶段目标
让 Android Studio 能识别这是个 Activity，能编译，能运行（白屏）。

### 3.2 遇到的新问题
**问题 1：为什么继承 `AppCompatActivity` 而不是 `Activity`？**
- 答：`AppCompatActivity` 是 AndroidX 提供的基类，兼容老版本 Android + 提供 AppCompat 主题。
- 直接继承 `Activity` 在新项目里几乎不用了。

**问题 2：什么是 `onCreate`？**
- Activity 的生命周期方法之一
- "创建"时由系统回调
- 必须先调 `super.onCreate(savedInstanceState)` 让父类做基础初始化

### 3.3 完整代码

```java
package com.example.chapter07;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class OpenCvDemoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
```

### 3.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `extends AppCompatActivity` | Activity 基类 |
| `onCreate` 重写 | Activity 生命周期入口 |
| `super.onCreate(...)` | 让父类先初始化 |

### 3.5 阶段 1 后能干嘛
- 能编译、能运行
- 屏幕显示一片白（因为没 `setContentView`）

---

## 4. 阶段 2 —— 创建一个 TextView 显示在屏幕上

### 4.1 本阶段目标
不写 XML 布局，直接在 Java 代码里创建一个 TextView 当作 Activity 的内容。

### 4.2 遇到的新问题
**问题 1：为什么不用 XML？**
- 答：这个 Activity 只是用来"显示 OpenCV 状态"，没有复杂布局。
- 用代码直接创建更简洁，不必维护 XML 文件。
- 注意：`new TextView(this)` 必须传 Context（Activity 本身就是 Context）。

**问题 2：`setContentView` 的参数是 View 不是 layout id？**
- 答：`setContentView` 有两个重载：
  - `setContentView(int layoutResID)` —— 用 XML 布局
  - `setContentView(View view)` —— 直接传 View 对象
- 这里我们直接传 TextView 对象。

### 4.3 完整代码

```java
TextView statusView = new TextView(this);
setContentView(statusView);
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `new TextView(this)` | 创建文本视图 |
| `setContentView(statusView)` | 把 TextView 设为页面内容 |

### 4.5 阶段 2 后能干嘛
- 屏幕显示一个 TextView（空白的）
- 默认文字太小、贴边很难看

---

## 5. 阶段 3 —— 给 TextView 加 padding 和字号

### 5.1 本阶段目标
让 TextView 看起来不那么丑：加内边距、加大字号。

### 5.2 遇到的新问题
**问题 1：padding 用 px 还是 dp？**
- 答：**用 dp 转 px**。
- 如果直接写 `setPadding(24, 24, 24, 24)`，在不同 dpi 屏幕上看起来不一样。
- 转成 px 后视觉效果统一。

**问题 2：`getDisplayMetrics().density` 是什么？**
- Android 系统为每个屏幕计算一个"密度因子"
- 例如：dpi=320 → density=2.0（mdpi 是基准 = 1.0）
- `px = dp * density`

### 5.3 完整代码

```java
TextView statusView = new TextView(this);
int padding = (int) (24 * getResources().getDisplayMetrics().density);
statusView.setPadding(padding, padding, padding, padding);
statusView.setTextSize(16f);  // 16sp
setContentView(statusView);
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `padding = 24 * density` | dp 转 px |
| `setPadding(...)` | 文字不贴边 |
| `setTextSize(16f)` | 字号 16sp |

### 5.5 阶段 3 后能干嘛
- TextView 看起来像个能用的样子
- 仍然没有文字内容

---

## 6. 阶段 4 —— 加载 OpenCV 库

### 6.1 本阶段目标
用 OpenCVLoader 加载 native 库。

### 6.2 遇到的新问题
**问题 1：`OpenCVLoader.initLocal()` 是啥？**
- `OpenCVLoader` 是 OpenCV 提供的 Java 工具类
- `initLocal()` = "本地初始化"，加载 OpenCVManager.apk 或项目自带的 .so 库
- 返回 boolean：成功 / 失败

**问题 2：为什么叫 "initLocal" 不叫 "init"？**
- OpenCV 提供了多种初始化方式：
  - `initLocal()`：本地模式（项目自带库）
  - `initDebug()`：从 OpenCV Manager apk 加载（旧方式，已废弃）
  - `initAsync()`：异步初始化
- 当前用 `initLocal()` 是因为库已经包含在项目里

**问题 3：什么时候必须调？**
- 在调用任何 `Mat` / `Imgproc` 等 OpenCV Java API **之前**。
- 否则会抛 `UnsatisfiedLinkError`。

### 6.3 完整代码

```java
import org.opencv.android.OpenCVLoader;

boolean initSuccess = OpenCVLoader.initLocal();
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `OpenCVLoader.initLocal()` | 加载 native 库 |
| 返回值存到 `initSuccess` | 后续判断是否成功 |

### 6.5 阶段 4 后能干嘛
- 如果 native 库装好了，`initSuccess = true`
- 如果没装好，`initSuccess = false`（比如忘了装 OpenCV SDK）

---

## 7. 阶段 5 —— 加载失败的兜底

### 7.1 本阶段目标
加载失败时显示错误信息，并提前 return。

### 7.2 遇到的新问题
**问题 1：为什么 return 而不是继续往下走？**
- 答：OpenCV 没初始化成功，下面的 `new Mat(...)` 会崩。
- 提前 return 防止 NPE / UnsatisfiedLinkError。

**问题 2：用什么文字提示？**
- 当前用中文 `"OpenCV 初始化失败，无法调用 Java API。"`
- 也可以写得更友好，比如引导用户去看文档。

### 7.3 完整代码

```java
if (!initSuccess) {
    statusView.setText("OpenCV 初始化失败，无法调用 Java API。");
    return;
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `if (!initSuccess)` | 失败判断 |
| `setText(...)` | 显示错误信息 |
| `return;` | 阻止后续崩溃 |

### 7.5 阶段 5 后能干嘛
- 即使 OpenCV 没装好，App 也不会崩
- 用户看到清晰的错误信息

---

## 8. 阶段 6 —— 创建一个 Mat 对象

### 8.1 本阶段目标
用 OpenCV Java API 创建一个 2×3 的 8 位单通道矩阵，所有元素初始化为 7。

### 8.2 遇到的新问题
**问题 1：`Mat(rows, cols, type, scalar)` 参数含义？**
- `rows`：行数（高）
- `cols`：列数（宽）
- `type`：数据类型，用 `CvType.CV_8UC1`
- `scalar`：初始值（所有元素都填这个）

**问题 2：为什么要 `Scalar(7)`？**
- 答：选 7 是因为容易识别（不是 0、不是 255 这种特殊值）。
- 调试时可以一眼看出"是不是初始化对了"。

**问题 3：Mat 是 native 内存，什么时候释放？**
- 在 Activity 里用完就 release。
- 不 release 会泄漏 native 堆内存，Android Profiler 里能看到 RSS 增长。

### 8.3 完整代码

```java
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

Mat mat = new Mat(2, 3, CvType.CV_8UC1, new Scalar(7));
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `new Mat(2, 3, CvType.CV_8UC1, ...)` | 创建 2×3 矩阵 |
| `new Scalar(7)` | 初始化为 7 |

### 8.5 阶段 6 后能干嘛
- native 层已经分配了一块内存
- Java 侧通过 `mat` 引用访问
- 接下来读取矩阵信息显示出来

---

## 9. 阶段 7 —— 拼接显示信息（多行 StringBuilder）

### 9.1 本阶段目标
把矩阵的行列数、类型、首个像素值拼成多行字符串。

### 9.2 遇到的新问题
**问题 1：为什么用 StringBuilder？**
- 答：要在末尾追加多行内容，用 `+` 号会创建很多中间 String 对象。
- StringBuilder 只在最后 `toString()` 时创建一个 String，效率高。

**问题 2：怎么换行？**
- `"\n"` 表示换行（在 TextView 里能正确显示）。

**问题 3：`mat.get(0, 0)[0]` 是什么？**
- `mat.get(row, col)` 返回 `double[]`（数组长度 = 通道数）
- 单通道矩阵返回 `[value]`
- 取 `[0]` 拿到实际像素值

**问题 4：为什么要 `(int)` 强转？**
- `mat.get()` 返回 double（即使是 8 位整数）
- 强转 int 让显示更干净（不会显示 `7.0`）

### 9.3 完整代码

```java
StringBuilder builder = new StringBuilder();
builder.append("OpenCV 初始化成功\n")
        .append("rows=").append(mat.rows()).append('\n')
        .append("cols=").append(mat.cols()).append('\n')
        .append("type=").append(mat.type()).append('\n')
        .append("samplePixel=").append((int) mat.get(0, 0)[0]);
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `new StringBuilder()` | 高效字符串拼接 |
| 5 行 append | 显示矩阵信息 |
| `mat.rows()/cols()/type()` | 矩阵属性 |
| `(int) mat.get(0, 0)[0]` | 第一个像素值 |

### 9.5 阶段 7 后能干嘛
- 拿到一个完整的展示字符串
- 接下来显示到 TextView

---

## 10. 阶段 8 —— 显示到 TextView + 释放 Mat

### 10.1 本阶段目标
把字符串显示到 TextView，释放 Mat 占用的 native 内存。

### 10.2 遇到的新问题
**问题 1：`mat.release()` 在哪儿调？**
- 答：用完就 release。
- 当前实现：`builder.toString()` 用完了 `mat`，然后立刻 `release()`。

**问题 2：忘了 release 会怎么样？**
- native 内存泄漏
- Android Profiler 看到 Native Heap 增长
- 多次打开关闭这个 Activity 后可能 OOM

### 10.3 完整代码

```java
statusView.setText(builder.toString());
mat.release();
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `setText(builder.toString())` | 显示信息 |
| `mat.release()` | 释放 native 内存 |

### 10.5 阶段 8 后能干嘛
- Activity 显示 5 行信息（OpenCV 初始化成功 + 矩阵信息）
- native 内存已释放，无泄漏

---

## 11. 完整最终代码对照

到阶段 8 为止，与 `app/src/main/java/com/example/chapter07/OpenCvDemoActivity.java` 完全一致：

```java
package com.example.chapter07;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

public class OpenCvDemoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView statusView = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        statusView.setPadding(padding, padding, padding, padding);
        statusView.setTextSize(16f);
        setContentView(statusView);

        boolean initSuccess = OpenCVLoader.initLocal();
        if (!initSuccess) {
            statusView.setText("OpenCV 初始化失败，无法调用 Java API。");
            return;
        }

        Mat mat = new Mat(2, 3, CvType.CV_8UC1, new Scalar(7));
        StringBuilder builder = new StringBuilder();
        builder.append("OpenCV 初始化成功\n")
                .append("rows=").append(mat.rows()).append('\n')
                .append("cols=").append(mat.cols()).append('\n')
                .append("type=").append(mat.type()).append('\n')
                .append("samplePixel=").append((int) mat.get(0, 0)[0]);
        statusView.setText(builder.toString());
        mat.release();
    }
}
```

---

## 12. _with_comments 版的由来

同目录下还有一个 `OpenCvDemoActivity_with_comments.java`，**代码逻辑完全一致**，只是每一行代码前面都用 `//` 加了一行中文注释。

### 12.1 用途

| 文件 | 何时用 |
|------|--------|
| `OpenCvDemoActivity.java` | 实际运行（AndroidManifest.xml 引用） |
| `OpenCvDemoActivity_with_comments.java` | 学习（给初学者逐行讲解） |

### 12.2 为什么不直接合并？

- 注释会让生产代码变臃肿
- 给两种受众分别准备：开发者 vs 学习者
- `_with_comments` 后缀表明"带注释版"

### 12.3 实现方法

```java
// 第N行：这一行代码的作用是什么
actualCode();  // 实际代码
```

就是手动逐行加注释，不涉及任何自动化工具。

---

## 13. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空 Activity | 10 | 基础骨架 |
| 2 | TextView | 14 | 显示在屏幕上 |
| 3 | padding + 字号 | 18 | 美观 |
| 4 | 加载 OpenCV | 22 | native 库就位 |
| 5 | 失败兜底 | 26 | 防崩 |
| 6 | 创建 Mat | 30 | 验证 native 可用 |
| 7 | StringBuilder 拼接 | 36 | 显示信息 |
| 8 | 显示 + release | 40 | 完整流程 |

**关键里程碑**：
- 阶段 4 = OpenCV 可用性验证
- 阶段 6 = native 内存正确分配
- 阶段 8 = native 内存正确释放

---

## 14. 常见报错对照表

| 错误 | 原因 | 修复 |
|------|------|------|
| `UnsatisfiedLinkError: opencv_java` | 没装 OpenCV SDK 或 .so 库没打进 APK | 在 build.gradle 加 `implementation 'org.opencv:opencv:4.x.x'` 或手动集成 .so |
| `initSuccess = false` 但应用不崩 | OpenCV 库加载失败，但失败兜底到位 | 检查 `OpenCVLoader.initLocal()` 调用日志 |
| `mat.release()` 抛异常 | 重复 release 或没初始化 | 用 `if (mat != null) mat.release();` |
| `mat.get(0, 0)` 返回 null | Mat 没正确创建 | 检查 `new Mat(...)` 参数 |
| 屏幕一片白 | 忘了 `setContentView(...)` | 在 onCreate 里加 |
| TextView 文字紧贴边缘 | 没设 padding | 加 setPadding |
| `ClassCastException` | 把 `Mat` 当 `Bitmap` 用 | OpenCV 是矩阵，Bitmap 是位图，不要混用 |

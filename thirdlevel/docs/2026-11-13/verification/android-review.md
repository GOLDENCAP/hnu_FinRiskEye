# 第三级 Android：A 侧验收缺口审查、黑屏/采集受限修复与验证记录

- 任务：`task-3`（第三级 Android：补黑屏/受保护画面检测与采集受限状态，完善 A 侧验收缺口）
- 负责人：teammate `android-integrator`（同学 A 侧采集与悬浮窗）
- 日期：2026-11-13
- 代码基线：`finriskeye/hnu_FinRiskEye` 提交 `31c2153`（chore: import B third-level handover baseline (2026-11-08)），工作区初始为干净状态
- 验证环境：Windows PowerShell 5.1 + JDK 17.0.12；**本机没有 Android SDK/真机/模拟器，未执行 Gradle 构建与真机验证**

本文档是 A 侧（屏幕采集、画面质量、悬浮窗状态机）的验收缺口清单、修复说明、关键 diff、纯 JVM 测试证据和遗留项记录。不涉及服务端、前两级 WebRTC、`app/build.gradle.kts`、`finriskeye/docs`、`finriskeye/dev-env`、`finriskeye/build-baseline`。

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| B 交付代码 A 侧缺口 | 共梳理 10 条（P0×3、P1×3、P2×3、P3×1），详见第 2 节 |
| 本次修复 | 新增 `FrameQualityDetector`（纯 Java，黑屏/空白/纯色判定）；`ScreenRiskService` 集成“采集受限”状态机；顺带修复 OCR 同步异常位图泄漏等 2 个低风险真实 bug；权限撤销文案明确化 |
| 黑屏误报“无风险” | **已消除**：黑屏/受保护画面不调用 `/api/v1/risk/screen`，悬浮窗橙色标题“检测受限”+ 明确说明，不清除已有风险，画面恢复后自动清除并强制重新分析 |
| 单测 | 新增 JUnit4 测试 11 个用例；本机用 JDK17 纯 JVM 编译并实跑：`Tests run: 11, Passed: 11, Failed: 0`（见第 5 节实际输出） |
| Android 编译/真机 | **未在本机验证**（约束：不跑 Gradle，避免与 sdk-builder 冲突）；Android 侧改动需由 Lead 在 sdk-builder 环境执行 `assembleDebug` 统一验证，黑屏/权限撤销等场景需真机验证 |

---

## 1. 验收口径（对照用）

本次修复对照的 12 月 8 日验收要求：

> 空内容、黑屏、权限撤销、网络或分析失败有明确状态，不误报为无风险。

拆解到 A 侧（采集与悬浮窗）：

| 验收场景 | A 侧要求 | B 交付现状 | 本次结论 |
| --- | --- | --- | --- |
| 黑屏/受保护画面 | 有明确“检测受限”状态，不得显示“无风险” | 无检测，黑屏当普通画面送视觉模型，通常返回 `none` → 绿色“无风险” | **缺口，已修复** |
| 空内容 | 有明确状态，不误报无风险 | OCR 为空仍送图片接口分析；纯空白画面无状态提示 | **部分缺口，已补空白/纯色画面判定** |
| 权限撤销 | 有明确状态 | MediaProjection `onStop` → `shutdown`，文案“屏幕共享授权已结束” | **基本覆盖，本次文案明确化** |
| 网络/分析失败 | 有明确状态 | 失败重试 1 次后进入“检测受限”并显示失败原因 | **已覆盖（代码复核通过）** |
| 不误报无风险 | 风险未清除时刻不显示“无风险” | 风险锁存 + 连续 2 次无风险清除；黑屏会绕过该机制 | **随黑屏修复一并加固** |

11 月 18 日目标（悬浮窗、MediaProjection 授权、基础画面采集）在 B 的交付中已实现：右上角可滚动悬浮窗（`FLAG_SECURE`）、悬浮窗/通知权限引导、MediaProjection 授权 → ImageReader 抓帧 → OCR → 视觉接口 → 悬浮窗/通知。本次未改动这些主链路，只增加画面质量前置判断。

---

## 2. A 侧验收缺口清单（按优先级，10 条）

| # | 优先级 | 缺口/问题 | 现象与影响 | 本次处置 |
| --- | --- | --- | --- | --- |
| 1 | P0 | 黑屏/受保护画面无显式检测 | `FLAG_SECURE`/DRM/厂商录屏限制时 MediaProjection 只拿到黑屏；黑屏作为普通画面送视觉模型，模型返回 `none` → 悬浮窗绿色“无风险”，**直接违反 12/8 验收要求** | 新增 `FrameQualityDetector` + `ScreenRiskService` 前置检测；黑屏判 `BLACK` |
| 2 | P0 | 黑屏帧仍上传视觉接口 | 每次采样都把纯黑 JPEG 发给 `/api/v1/risk/screen`，浪费流量和模型 token，且黑屏统计结果会参与“连续 2 次无风险清风险”逻辑，干扰风险锁存 | 黑屏分支提前返回：不 OCR、不生成 JPEG、不发请求 |
| 3 | P0 | 黑屏状态与既有风险/在途结果一致性 | 若黑屏期间旧请求返回 `none`，会清掉已有风险提示；黑屏与恢复切换时可能显示旧 summary | 新增 `screenFrameLimited` 状态；受限期间忽略画面 `none` 结果、保留风险；恢复时强制重新分析；分析失败/处理失败不覆盖受限说明 |
| 4 | P1 | 空白/纯色画面无状态（“空内容”的画面侧） | 纯白加载页、纯色占位页没有内容可分析，但仍会得到 `none` → “无风险”；与“空内容要有明确状态”不符 | `FrameQualityDetector` 增加 `BLANK`：近白空白、亮色纯色 → 同样走“检测受限”，提示“空白或纯色画面，未采集到可分析内容” |
| 5 | P1 | 权限撤销文案不够明确 | 撤销后仅显示“屏幕共享授权已结束”，用户/验收无法区分“主动停止”和“系统撤销” | `MediaProjection.Callback.onStop` 文案改为“屏幕共享授权已撤销，请重新授权后开启检测”（真机验证项） |
| 6 | P1 | 网络/分析失败状态（复核项） | B 已实现：失败重试 1 次 → `NETWORK_ERROR`/`HTTP_*` → `screenError` → 橙色“检测受限”；已有风险时保留风险并附加失败原因 | 代码复核通过，不修改；真实 E2E 证据由 `server-verifier` 提供 |
| 7 | P2 | OCR 同步异常时位图泄漏、采集链路卡死 | `textRecognizer.recognize` 若同步抛异常（如服务停止时识别器已 `close()` 的竞态），`bitmap` 不会回收、`processing` 保持 true，后续帧不再分析 | `try/catch` 包裹：回收位图 + 降级为仅图片分析；异常路径不再泄漏 |
| 8 | P2 | 受限状态可能重复刷屏 | 黑屏持续时若每帧都 `renderCurrentState`，会每 2 秒重发通知/刷新悬浮窗 | 同类型受限帧只在“首次进入/类型变化”时渲染；持续黑屏不重复刷新 |
| 9 | P2 | 恢复瞬间可能误显示“无风险” | 黑屏恢复时若直接 `renderCurrentState`，在重新分析完成前可能短暂显示绿色“无风险” | 恢复时无既有风险则先显示“检测中”，恢复后的第一帧强制重新分析；有风险则保留红色风险标题 |
| 10 | P3 | 真机/环境相关项（不修） | 横竖屏切换后虚拟显示尺寸固定；ML Kit 回调不返回时无超时看门狗；部分厂商的受保护画面可能不是纯黑（灰/模糊/最后一帧）；视觉模型本身存在波动 | 见第 6 节“未修复项及原因”，转真机验收/后续版本 |

---

## 3. 修复实现

### 3.1 新增纯 Java 画面质量检测：`thirdlevel/android/java/.../FrameQualityDetector.java`

与 Android `Bitmap` 解耦：Android 侧负责把帧降采样到 64×64 并 `getPixels(int[])`，检测器只接收 `int[] pixels + width + height`，因此可在无真机环境用 JVM 单测验证。

判定规则（阈值集中为 `public static final` 常量）：

| 判定 | 条件 | 说明 |
| --- | --- | --- |
| `BLACK` | 平均亮度 `< BLACK_MAX_AVERAGE_LUMA(12.0)` 且 亮度标准差 `<= BLACK_MAX_STDDEV(3.0)` | 近乎全黑且几乎无波动，典型 `FLAG_SECURE`/DRM 黑屏 |
| `BLANK` | 近白空白：平均亮度 `>= BLANK_MIN_AVERAGE_LUMA(230.0)` 且 标准差 `<= BLANK_MAX_STDDEV(2.0)`；或亮色纯色：平均亮度 `>= SOLID_COLOR_MIN_AVERAGE_LUMA(180.0)` 且 标准差 `<= SOLID_COLOR_MAX_STDDEV(1.0)` | 空白页/纯色占位页，没有可分析内容 |
| `OK` | 其余情况；参数非法（null、宽高非法、数组长度不足）也返回 `OK` | 检测器自身异常时按正常画面继续，避免误停风险分析 |

**为什么阈值保守（关键设计取舍）：** 深色模式背景（如 `#121212`，亮度约 18）高于黑屏平均亮度阈值 12；只要画面里还有文字/控件，标准差就会超过 3，因此正常深色界面不会被判黑屏、不会停止风险分析。代价是“暗灰色但非纯黑”的受限画面可能检测不到——这种画面在真机上通常是受保护内容的灰屏占位，需要真机补充样本后再调整阈值（见第 6 节）。

`ScreenRiskService` 侧调用链（Android 侧采样，保持与 `ImageDeduplicator` 相同的 `createScaledBitmap` 风格）：

```java
Bitmap sample = Bitmap.createScaledBitmap(bitmap, 64, 64, true);
int[] pixels = new int[64 * 64];
sample.getPixels(pixels, 0, 64, 0, 0, 64, 64);
FrameQualityDetector.Quality quality = frameQualityDetector.detect(pixels, 64, 64);
// sample != bitmap 时 recycle，避免位图泄漏
```

### 3.2 集成进 `ScreenRiskService`（采集与状态机）

新的单帧处理顺序（在 `captureTick` 内，每 2 秒采样一次）：

```text
acquireLatestImage → imageToBitmap（含 OCR 最大边 2600 缩放）
→ FrameQualityDetector 检测（64×64 抽样，本地、不发网络）
   ├─ OK    → 若刚从受限恢复：清除受限状态并强制重新分析
   │        → ImageDeduplicator → 生成视觉 JPEG → ML Kit OCR → /api/v1/risk/screen
   └─ BLACK/BLANK → recycle 位图、processing=false、置“检测受限”状态
                    （不 OCR、不生成 JPEG、不发风险请求、不清除已有风险）
```

新增/复用的状态字段：

- `screenFrameLimited`：当前是否处于“画面质量导致的采集受限”；
- `screenFrameLimitedHint`：当前受限说明（黑屏/空白两种）；
- `screenError`：复用既有错误字段承载受限说明（`renderCurrentState` 的 `limited` 分支据此显示橙色“检测受限”）；
- `renderCurrentState` 的 `limited` 条件增加 `screenFrameLimited`，保证即使错误字段被并发路径短暂改写，也不会掉回“无风险”。

行为矩阵（集成后）：

| 采集画面 | 风险接口 | 悬浮窗标题 | 说明区 | 已有风险 |
| --- | --- | --- | --- | --- |
| 正常画面（无风险） | 调用 | 检测中 → 无风险（绿色） | 正常 summary | 连续 2 次无风险后清除 |
| 正常画面（有风险） | 调用 | 存在风险（红色） | 风险等级 + 证据/原因 | 锁存 |
| 全黑/受保护画面 | **不调用** | **检测受限（橙色）** | 画面：检测受限<br>画面：黑屏或受保护画面（可能被目标应用限制） | **不清除**；若有风险则显示红色“存在风险”并在详情附加受限说明 |
| 空白/纯色画面 | **不调用** | **检测受限（橙色）** | 画面：检测受限<br>画面：空白或纯色画面，未采集到可分析内容 | 同上 |
| 画面恢复正常 | **强制重新分析一次** | 先“检测中”，随后由结果决定 | 受限说明清除 | 有风险保持红色；无风险按连续 2 次规则 |
| 检测器自身异常 | 按正常画面继续 | 不变 | 不显示受限 | 不误报受限 |

补充约束（对应 12/8 验收）：

- 黑屏期间**不发送** `/api/v1/risk/screen`，因此不可能出现服务端 `none` → “无风险”的误报；
- 黑屏期间**不执行 OCR**，降低 CPU 占用；
- 黑屏期间不修改 `screenRiskActive/screenClearResults`，已有风险提示不会被清除；
- 受限说明用橙色标题表达“检测受限”，不会出现在绿色“无风险”分支下；
- 持续黑屏只在进入受限时刷新一次悬浮窗/通知，不刷屏。

### 3.3 顺带修复的低风险真实 bug

1. **OCR 同步异常导致位图泄漏与采集卡死**（`handleCapturedBitmap`）
   - 原实现：`textRecognizer.recognize(...)` 未被 `try/catch` 包裹。服务停止时 `shutdown()` 会 `close()` 识别器，capture 线程与主线程存在竞态，`recognize` 可能同步抛异常；此时 `bitmap` 未 `recycle()`，`processing` 保持 `true`，后续帧永远不再处理。
   - 修复：异常时 `bitmap.recycle()`，并降级为“无 OCR 文本、只用图片分析”，保证链路继续。
2. **`handleAnalysisError` 冗余分支**：`if (hasActiveRisk()) renderCurrentState(); else renderCurrentState();` 两个分支相同，已简化为一次 `renderCurrentState()`；同时增加受限状态保护，分析失败信息不会覆盖黑屏说明。

### 3.4 权限撤销（12/8 验收项）

`MediaProjection.Callback.onStop` 的停止文案由“屏幕共享授权已结束”改为：

```text
屏幕共享授权已撤销，请重新授权后开启检测
```

用户/验收方在悬浮窗消失、`ThirdLevelActivity` 状态区可以看到明确的撤销原因。权限撤销后的清理链路（取消请求、关闭 ImageReader/VirtualDisplay、停前台服务、隐藏悬浮窗）沿用 B 的实现，未改动。

---

## 4. 关键 diff 摘要

改动只在允许路径内，未修改 `app/build.gradle.kts`（测试 sourceSet 与 JUnit4 依赖已存在，无需改构建脚本）、前两级 WebRTC 代码、server、docs、dev-env、build-baseline；未执行任何 git 提交。

```text
$ git -C finriskeye\hnu_FinRiskEye status --short
 M thirdlevel/android/java/com/example/chapter07/thirdlevel/ScreenRiskService.java
?? thirdlevel/android/java/com/example/chapter07/thirdlevel/FrameQualityDetector.java
?? thirdlevel/android/test/java/com/example/chapter07/thirdlevel/FrameQualityDetectorTest.java

$ git -C finriskeye\hnu_FinRiskEye diff --stat
 .../chapter07/thirdlevel/ScreenRiskService.java    | 170 +++++++++++++++++----
 1 file changed, 143 insertions(+), 27 deletions(-)
```

`ScreenRiskService.java` 的 5 个关键 diff 片段：

1. 新增受限说明常量与状态字段：

```diff
+    private static final String FRAME_LIMITED_BLACK_HINT =
+            "画面：黑屏或受保护画面（可能被目标应用限制）";
+    private static final String FRAME_LIMITED_BLANK_HINT =
+            "画面：空白或纯色画面，未采集到可分析内容";
+    private static final String FRAME_LIMITED_SUMMARY = "画面：检测受限";
...
+    private final FrameQualityDetector frameQualityDetector = new FrameQualityDetector();
...
+    private boolean screenFrameLimited;
+    private String screenFrameLimitedHint = "";
```

2. 采样循环接入画面质量前置判断（黑屏不 OCR、不发请求）：

```diff
-                        boolean imageChanged = imageDeduplicator.shouldAnalyze(bitmap);
-                        handleCapturedBitmap(bitmap, imageChanged);
+                        FrameQualityDetector.Quality quality = evaluateFrameQuality(bitmap);
+                        if (quality == FrameQualityDetector.Quality.OK) {
+                            // 从黑屏/受保护画面恢复时强制重新分析，避免沿用受限前的旧状态。
+                            boolean recovered = releaseFrameLimitedState();
+                            boolean imageChanged = imageDeduplicator.shouldAnalyze(bitmap);
+                            handleCapturedBitmap(bitmap, imageChanged || recovered);
+                        } else {
+                            // 黑屏/受保护画面不发送风险请求，也不清除已有风险提示。
+                            bitmap.recycle();
+                            processing.set(false);
+                            markFrameLimited(quality);
+                        }
```

3. 新增 `evaluateFrameQuality` / `markFrameLimited` / `releaseFrameLimitedState` 三个方法（完整实现见源码；`evaluateFrameQuality` 的 Android 采样在 `finally` 中回收采样位图，检测异常按 `OK` 继续）。

4. 受限期间保护既有风险，不让在途 `none` 结果清风险：

```diff
+        if (!fromAudio && screenFrameLimited) {
+            // 黑屏/受保护画面期间，旧画面在途的结果可能返回 none；
+            // 此时不能清除已有风险，保持受限状态等待画面恢复后重新分析。
+            renderCurrentState();
+            return;
+        }
```

5. `renderCurrentState` 的受限分支纳入新状态：

```diff
-        boolean limited = !screenError.isEmpty()
+        boolean limited = screenFrameLimited
+                || !screenError.isEmpty()
                 || !audioError.isEmpty()
                 || (audioEnabled && audioSummary.contains("不可用"));
```

新增文件：

- `thirdlevel/android/java/com/example/chapter07/thirdlevel/FrameQualityDetector.java`（96 行）
- `thirdlevel/android/test/java/com/example/chapter07/thirdlevel/FrameQualityDetectorTest.java`（11 个测试用例）

SHA-256（便于 Lead 核对）：

```text
451efb4bcf965d81b89ec0ff9a9b1b9cd22ed01fa50b1a87e650ac4060ac48ba  FrameQualityDetector.java
48ed5cc758a6e77c935fd822cdbadd0a70c796efce6a4ff43638aa6034bf9827  FrameQualityDetectorTest.java
018aba102d0010ad5151e2e6f5e85872d3a218a70d258576f363e47c050cc285  ScreenRiskService.java
```

---

## 5. 验证方式与结果

### 5.1 纯 JVM 单元测试（实际执行）

本机没有 JUnit4 依赖（`~/.gradle`/`~/.m2` 均无 junit 4.x，只有 JUnit 3.8.1），因此用 JDK17 直接编译：

- 被验证的正式测试源码：`thirdlevel/android/test/java/.../FrameQualityDetectorTest.java`（JUnit4 风格，与现有 `TextDeduplicatorTest` 一致，Gradle 构建时会走 `junit:junit:4.13.2` 正式运行）；
- 测试源码 + 被测类 `FrameQualityDetector.java`；
- `finriskeye/evidence/android/jvm-test/src/` 下的最小 `org.junit` 兼容桩（`@Test`、`Assert.assertEquals`）和反射运行器 `JunitLiteRunner`，**仅用于本机验证，不是应用依赖、不参与 Gradle 构建**。

复现命令（任意 PowerShell 窗口）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File finriskeye\evidence\android\run-jvm-tests.ps1
```

脚本会输出到 `finriskeye/evidence/android/jvm-test-output.txt`。2026-11-13 20:36 实际运行输出（原样摘录该文件）：

```text
==== Pure-JVM test run: third-level FrameQualityDetector ====
Date: 2026-11-13 20:36:35 +08:00
javac: javac 17.0.12
Compile sources:
  C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye\thirdlevel\android\java\com\example\chapter07\thirdlevel\FrameQualityDetector.java
  C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye\thirdlevel\android\test\java\com\example\chapter07\thirdlevel\FrameQualityDetectorTest.java
  C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\evidence\android\jvm-test\src\org\junit\Test.java
  C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\evidence\android\jvm-test\src\org\junit\Assert.java
  C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\evidence\android\jvm-test\src\JunitLiteRunner.java
Compile output dir: C:\Users\79047\AppData\Local\Temp\finriskeye-frame-quality-tests
javac exit code: 0

Run: java -cp <temp classes dir> JunitLiteRunner
java exit code: 0
[PASS] brightSolidColorFrameIsBlank
[PASS] classificationBoundariesAreConservative
[PASS] darkModeScreenWithTextIsNotLimited
[PASS] invalidPixelsAreTreatedAsOk
[PASS] lightGrayBlankFrameIsBlank
[PASS] nearBlackUniformFrameIsBlack
[PASS] noisyDarkVideoFrameIsNotFlaggedAsBlack
[PASS] normalContentFrameIsOk
[PASS] pureBlackFrameIsBlack
[PASS] uniformDarkBackgroundIsNotFlaggedAsBlack
[PASS] whiteBlankFrameIsBlank

Tests run: 11, Passed: 11, Failed: 0

RESULT: PASS
```

测试覆盖矩阵：

| 用例 | 验证点 |
| --- | --- |
| `pureBlackFrameIsBlack` / `nearBlackUniformFrameIsBlack` | 全黑、近黑（`#050505`）→ `BLACK` |
| `whiteBlankFrameIsBlank` / `lightGrayBlankFrameIsBlank` / `brightSolidColorFrameIsBlank` | 纯白、浅灰、亮色纯色 → `BLANK` |
| `normalContentFrameIsOk` | 黑白棋盘格正常内容 → `OK` |
| `darkModeScreenWithTextIsNotLimited` | 深色模式背景 + 散布浅色文字像素 → `OK`（不误停风险分析） |
| `uniformDarkBackgroundIsNotFlaggedAsBlack` | 深色模式纯背景（亮度约 18）→ `OK`（保守阈值，显式锁定该设计取舍） |
| `noisyDarkVideoFrameIsNotFlaggedAsBlack` | 暗但有波动的视频画面 → `OK` |
| `invalidPixelsAreTreatedAsOk` | null/空数组/零宽/数组不足 → `OK`（检测失败不误报受限） |
| `classificationBoundariesAreConservative` | 阈值边界：黑屏边界、空白边界、亮色纯色边界 |

### 5.2 Android 侧语法层检查（无 Android SDK 的替代手段）

约束要求不跑 Gradle；本机也没有 Android SDK/android.jar。为降低手误风险，做了如下补充检查（**不等于完整编译**）：

```powershell
# 1) 新增纯 Java 类 + 正式测试源码用 JDK17 编译并运行（见 5.1，javac/java 均 exit 0）
# 2) ScreenRiskService.java 用 -sourcepath 指向 thirdlevel/android/java 做 javac 语法/语义检查
javac -encoding UTF-8 -Xmaxerrs 3000 -sourcepath finriskeye\hnu_FinRiskEye\thirdlevel\android\java `
      -d %TEMP%\check finriskeye\hnu_FinRiskEye\thirdlevel\android\java\com\example\chapter07\thirdlevel\ScreenRiskService.java
```

结果：报错全部是“缺少外部依赖符号”（`android.*`、`androidx.*`、`okhttp3`、`org.json`、ML Kit、`R` 等）以及缺少 Android 基类导致的“方法不会覆盖或实现超类型的方法”；**没有任何 `需要';'`/`非法的...`/`不兼容的类型`/`未结束` 等语法类错误**；项目内被引用类（`FrameQualityDetector`、`RiskModels`、`ScreenTextRecognizer`、`ImageDeduplicator` 等）均能解析（`程序包...不存在` 计数为 0）。`ScreenRiskService` 的完整编译仍以 Lead 在 sdk-builder 环境执行 `assembleDebug` 为准。

### 5.3 未执行的验证

- 未执行 `gradlew assembleDebug` / `testDebugUnitTest`（约束：构建由 Lead 用 sdk-builder 环境统一做，避免冲突；B 已知 Gradle 9.3.1 单测执行器在本机有 `GradleWorkerMain ClassNotFoundException` 环境问题）；
- 未在 Android 真机/模拟器验证黑屏、受保护画面、权限撤销、悬浮窗实际显示（本机无设备）；
- 未做 Node 风险服务联调（由 `server-verifier` 负责）。

---

## 6. 未修复项及原因

| 项 | 原因/影响 | 建议 |
| --- | --- | --- |
| 真机黑屏/受保护画面验证 | 本机无 Android 设备，无法构造 `FLAG_SECURE`/DRM/腾讯会议白板等受保护画面；检测器阈值是按“保守优先”设计的统计值，需真机取真实黑屏/深色界面样本校准 | 真机验收时先测：深色模式正常界面（应继续分析、不显示受限）→ 打开 `FLAG_SECURE` 测试页或受保护视频（应显示橙色“检测受限”+黑屏说明）→ 恢复正常画面（应自动清除并继续分析） |
| 受保护画面非纯黑的情况 | 部分厂商/DRM 可能渲染为灰色占位、模糊或最后一帧而非纯黑（0 亮度）；统计检测只能覆盖“近黑/近空白”，无法保证 100% 识别所有受保护内容 | 真机采集样本后补充阈值或增加“画面长时间完全不变 + 无 OCR 文本”的辅助判定（需避免正常静态页面误判，建议下一版本评估） |
| 横竖屏切换 | 虚拟显示尺寸在会话开始时确定，旋转后画面可能被裁剪/拉伸（B 已知问题） | 当前建议停止后重新开启检测；如需自动适配，需要监听配置变化并重建 VirtualDisplay，改动较大，留待后续 |
| ML Kit OCR 回调不返回 | 若 ML Kit `process()` 的回调既不成功也不失败，`processing` 会保持 true，后续帧不再分析（无超时看门狗）；极端情况才出现，且加入看门狗会与网络请求 15s+重试时间窗冲突，需要更细致的分段超时设计 | 下一版本将 `processing` 拆成 OCR 超时与网络超时两段，或用 `Task` 超时包装；当前作为已知风险记录 |
| 视觉模型结果波动 | B 已知问题：同一白板在模型只看到工具栏时可能返回 `none`；属于服务端模型能力/波动，A 侧已用“连续 2 次无风险才清除”降低误清概率 | 用完整演示样例测试；必要时服务端对低信息画面返回“无法判断”而非 `none`（需服务端配合） |
| 腾讯会议应用音频是否可采集 | `AudioPlaybackCapture` 只接受目标应用允许的 MEDIA/GAME/UNKNOWN 类型，腾讯会议可能禁止采集；B 已实测系统音乐播放器可用，真实会议语音待实测 | 真机按 B 文档第 10 节流程验证；不可采集时应显示“语音采集不可用”（B 已实现） |
| 悬浮窗权限被系统中途撤销 | 代码未监听 `SYSTEM_ALERT_WINDOW` 撤销事件；撤销后悬浮窗通常被系统移除，更新调用不崩溃但用户看不到提示 | 真机验证；如需要可在 `shutdown` 前检查 `Settings.canDrawOverlays` 并在状态区提示重新授权 |
| 服务端幂等缓存/HTTPS/令牌轮换 | 属于服务端与部署范围，不在 A 侧修复范围 | 由服务端负责人/部署负责人处理 |

另外记录一项**行为边界**（非缺陷）：单帧短暂全黑（例如应用切换/旋转的瞬态黑帧）会立即显示一次“检测受限”，下一帧正常画面（≤2 秒内）会自动清除。这是“宁可明确受限，不误报无风险”的取舍；如果真机演示中该瞬态影响体验，可后续加“连续 2 帧黑屏才判受限”的迟滞，但会降低检测灵敏度。

---

## 7. 下一步建议

1. **构建验证（Lead/sdk-builder）**：在 Android SDK 就绪后执行 `.\gradlew.bat assembleDebug -PAI_API_KEY=__THIRDLEVEL_DISABLED__ -POPENAI_API_KEY=__THIRDLEVEL_DISABLED__`，确认新增类与 `ScreenRiskService` 集成可编译；APK 产出后记录大小与 SHA-256。若 Gradle 单测执行器问题修复，再执行 `testDebugUnitTest` 跑正式 JUnit4 版本的 `FrameQualityDetectorTest`。
2. **真机 A 侧验收**（建议按顺序，每步截图/录屏留证）：
   - 启动检测，正常聊天/会议画面 → 标题“检测中/无风险”，OCR 与视觉分析正常；
   - 深色模式应用正常界面 → 不得出现“检测受限”，分析继续；
   - 打开受保护画面（`FLAG_SECURE` 测试页/DRM 视频/腾讯会议白板）→ 标题橙色“检测受限”+“画面：黑屏或受保护画面（可能被目标应用限制）”，**不得显示“无风险”**，服务端无新请求日志、客户端无新 token 消耗；
   - 先制造高风险画面（红色“存在风险”）→ 再切受保护画面 → 风险提示仍在，详情附加受限说明；
   - 恢复普通画面 → 受限说明消失，先显示“检测中”，随后按分析结果更新；
   - 在系统设置中撤销屏幕共享/把应用切到后台被回收 → 状态区显示“屏幕共享授权已撤销，请重新授权后开启检测”；
   - 停止 Node 服务后继续检测 → 显示“分析失败/无法连接风险分析服务”，不得误报“无风险”。
3. **证据汇总**：把真机截图/日志放到 `finriskeye/evidence/android/`（本目录），与本文档、`jvm-test-output.txt` 一起作为第三级 A 侧验收证据。
4. **交付前检查**：确认 `git status` 仅包含本文档列出的 3 个源码文件变更；不要提交构建产物/临时文件。

---

## 8. 本次产物清单

| 路径 | 类型 | 说明 |
| --- | --- | --- |
| `finriskeye/hnu_FinRiskEye/thirdlevel/android/java/com/example/chapter07/thirdlevel/FrameQualityDetector.java` | 新增源码 | 纯 Java 黑屏/空白检测器（阈值常量集中定义） |
| `finriskeye/hnu_FinRiskEye/thirdlevel/android/java/com/example/chapter07/thirdlevel/ScreenRiskService.java` | 修改源码 | 采集受限状态机集成、OCR 异常位图回收、权限撤销文案 |
| `finriskeye/hnu_FinRiskEye/thirdlevel/android/test/java/com/example/chapter07/thirdlevel/FrameQualityDetectorTest.java` | 新增测试 | JUnit4 风格，11 个用例，接入现有 Gradle test sourceSet |
| `finriskeye/evidence/android/review.md` | 证据文档 | 本文档 |
| `finriskeye/evidence/android/jvm-test-output.txt` | 证据 | 纯 JVM 测试实际输出（2026-11-13） |
| `finriskeye/evidence/android/run-jvm-tests.ps1` | 证据/脚本 | 一键复现纯 JVM 测试 |
| `finriskeye/evidence/android/jvm-test/src/` | 证据/脚本 | 最小 `org.junit` 兼容桩与反射运行器（仅本机验证用） |

所有结论均基于当前工作区与上述本机验证；涉及真机行为的部分标注为“待真机验证”，未夸大验证范围。

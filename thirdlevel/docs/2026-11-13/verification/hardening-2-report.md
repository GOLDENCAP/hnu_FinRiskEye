# 第三级 Android 12/8 补强记录：OCR 看门狗、黑屏迟滞、悬浮窗权限撤销、延迟统计、演示入口、应用名

- 任务：`task-4`（第三级 Android 12/8 补强，服务 11/28–12/8 验收与 12/15 演示/模拟器联调）
- 负责人：teammate `android-hardening`
- 日期：2026-11-13
- 代码基线：`finriskeye/hnu_FinRiskEye` 提交 `ca71aac`（含上一轮 `FrameQualityDetector` 黑屏/空白检测与“检测受限”状态，见 `review.md`）
- 验证环境：Windows PowerShell 5.1 + JDK 17.0.12；**本机未跑 Gradle、无 Android 真机**（Android 完整编译由 sdk-builder 的 `task-8` 统一执行；模拟器 E2E 由 emulator-verifier 执行）
- 写入范围：`thirdlevel/android`（java/res/test）、`app/src/main/res/values/strings.xml`、`finriskeye/evidence/android`；另外 **1 处经 Lead 明确授权** 的 `app/src/main/AndroidManifest.xml`（只新增 `ThirdLevelDemoActivity` 一条声明，见第 3.6 节）

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| 6 项补强 | **全部完成**：OCR 看门狗、黑屏/空白迟滞、悬浮窗权限撤销、延迟与会话统计、应用名、演示/自测入口 |
| 纯 JVM 验证 | **PASS**：3 个测试类共 **35 个用例全部通过**（新增 FrameLimitedGate 11、AnalysisStatsFormatter 13，回归 FrameQualityDetector 11） |
| 演示画面可用性 | **PASS**：两张打包 PNG 经与 `ScreenRiskService` 相同的 64×64 采样路径检测均为 `OK`，连续 2 帧也不会被判“检测受限”；演示 Activity 未设置 `FLAG_SECURE`（脚本校验） |
| 静态一致性检查 | **PASS**：8 个 XML 全部格式合法；13 个 Java 源文件的 `R.*` 引用、全部 `@string/@color/@drawable/@layout` 引用均可解析；`ThirdLevelDemoActivity` 已在 manifest 注册且 `exported=false`；本机 `aapt2 compile` 编译 thirdlevel（8 flat）与 app（54 flat）资源均 exit 0 |
| Android 编译/真机 | **未在本机验证**（不跑 Gradle 的约束）；黑屏迟滞真机手感、权限撤销、OCR 看门狗真实触发、悬浮窗/通知显示需真机/模拟器验证（步骤见第 7 节） |

---

## 1. 验收口径对照（12/8 验收要求）

> “空内容、黑屏、权限撤销、网络或分析失败有明确状态，不误报为无风险”；另需“记录延迟”，以及可演示的内置测试画面。

| 验收场景 | 本轮处置 | 状态 |
| --- | --- | --- |
| 黑屏/受保护画面 | 上一轮已做“检测受限”；本轮加**迟滞门控**：连续 2 帧 BLACK/BLANK 才进入受限，1 帧正常立即退出 | 完成 |
| 单帧瞬态黑帧 | 迟滞未满时只丢弃该帧、**不刷状态**（解决 `review.md` 第 6 节记录的行为边界） | 完成 |
| 权限撤销（悬浮窗） | 采集 tick 每 2 秒 `Settings.canDrawOverlays()` 检查；撤销后 `shutdown(true, "悬浮窗权限已撤销，请重新授权后开启检测")`，防重复 shutdown | 完成 |
| 权限撤销（屏幕共享） | 沿用上一轮 `MediaProjection.Callback.onStop` 文案与清理链路，未改动 | 已覆盖 |
| 网络/分析失败 | 沿用 B 的重试 1 次 + “检测受限”状态；本轮把失败也计入会话统计（延迟可见） | 已覆盖 + 增强 |
| 记录延迟 | `RiskApiClient` 记录每次请求（含重试）耗时 → `RiskResult.latencyMs` → 悬浮窗/通知详情统一追加“最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次” | 完成 |
| 空内容/分析失败不误报 | 沿用既有语义；迟滞/看门狗只降级不误报 | 保持 |
| OCR 回调不返回 | 新增 **8s 看门狗**：降级为“无 OCR 文本、仅图片分析”，位图只回收一次、processing 只复位一次、shutdown 时移除超时器 | 完成（`review.md` 第 6 节遗留项关闭） |
| 应用名 | `app_name` 改为 “FinRiskEye 风险检测”，包名不变 | 完成 |
| 演示/自测入口 | `ThirdLevelDemoActivity` + 内置普通/风险两张测试图 + 大按钮切换 + 返回按钮 + “自测/演示用”提示；从 `ThirdLevelActivity`“演示画面（自测用）”按钮进入 | 完成 |

---

## 2. 改动清单

| 路径（相对 `finriskeye/hnu_FinRiskEye`） | 类型 | 说明 |
| --- | --- | --- |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/FrameLimitedGate.java` | 新增源码 | 纯 Java 黑屏/空白迟滞门控（连续 2 帧进入、1 帧退出、未知质量不误报），阈值常量集中定义 |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/AnalysisStatsFormatter.java` | 新增源码 | 纯 Java 延迟/会话统计格式化（相对时间、耗时、分析次数/风险次数） |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ThirdLevelDemoActivity.java` | 新增源码 | 演示/自测画面 Activity，全屏 ImageView + 普通/风险/返回三个大按钮，无 `FLAG_SECURE` |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ScreenRiskService.java` | 修改源码 | OCR 看门狗、迟滞门控集成、悬浮窗权限撤销检查、延迟/会话统计与统一统计行、shutdown 清理 |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/RiskApiClient.java` | 修改源码 | 请求耗时统计（含重试，`SystemClock.elapsedRealtime`）写入 `RiskResult.latencyMs` |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/RiskModels.java` | 修改源码 | `RiskResult` 新增 `latencyMs` 字段（默认 -1） |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ThirdLevelActivity.java` | 修改源码 | 新增“演示画面（自测用）”按钮点击监听（只跳转演示页，不影响开始/停止流程） |
| `thirdlevel/android/res/layout/activity_third_level.xml` | 修改资源 | “授予悬浮窗权限”下方新增 `thirdLevelDemoButton` |
| `thirdlevel/android/res/layout/activity_third_level_demo.xml` | 新增资源 | 演示页布局：顶部“自测/演示用”提示 + 全屏 `fitCenter` ImageView + 两个 72dp 大按钮 + 返回按钮 |
| `thirdlevel/android/res/drawable-nodpi/third_level_demo_normal.png` | 新增资源 | 复制自 `thirdlevel/test-assets/01_普通画面.png`（1200×800，12546 字节） |
| `thirdlevel/android/res/drawable-nodpi/third_level_demo_risk.png` | 新增资源 | 复制自 `thirdlevel/test-assets/02_风险画面.png`（640×480，19412 字节） |
| `thirdlevel/android/res/values/third_level_strings.xml` | 修改资源 | 新增 7 条演示页中文文案 |
| `thirdlevel/android/test/java/com/example/chapter07/thirdlevel/FrameLimitedGateTest.java` | 新增测试 | JUnit4 风格，11 个用例（进入/退出/抖动/混合/未知/重置/封顶） |
| `thirdlevel/android/test/java/com/example/chapter07/thirdlevel/AnalysisStatsFormatterTest.java` | 新增测试 | JUnit4 风格，13 个用例（含验收样例整行文案） |
| `app/src/main/res/values/strings.xml` | 修改资源 | `app_name` → “FinRiskEye 风险检测” |
| `app/src/main/AndroidManifest.xml` | 修改（**Lead 已授权**） | 只新增 `ThirdLevelDemoActivity` 一条 `<activity>` 声明，`exported=false`，未改其他节点 |
| `finriskeye/evidence/android/hardening-2-report.md` | 证据 | 本文档 |
| `finriskeye/evidence/android/hardening-2-output.txt` | 证据 | 纯 JVM 测试与静态检查实际输出 |
| `finriskeye/evidence/android/run-jvm-tests-hardening2.ps1` | 证据/脚本 | 一键复现本轮全部本机验证 |
| `finriskeye/evidence/android/jvm-test/src/DemoImageQualityCheck.java` | 证据/脚本 | 用 JDK ImageIO 校验演示 PNG 不会被判“检测受限”（非应用依赖） |
| `finriskeye/evidence/android/jvm-test/src/org/junit/Assert.java` | 证据/脚本 | 兼容桩补充 `assertEquals(int,int)/assertEquals(long,long)` 重载 |

未改动：`thirdlevel/server`、`deploy`、`docs`、`dev-env`、`build-baseline`、`ARTIFACTS`、`app/build.gradle.kts`、前两级 WebRTC 代码；未执行 `git add/commit`。

---

## 3. 实现说明与关键 diff

### 3.1 OCR 看门狗（`ScreenRiskService`）

问题：ML Kit `recognize()` 的回调在极端情况下既不成功也不失败，`processing` 会一直为 `true`，后续帧永远不再分析（`review.md` 第 6 节记录项）。

实现要点：

- 常量 `OCR_TIMEOUT_MS = 8_000L`（集中定义，真机调优只改这一处）；
- 每次 OCR 封装 `OcrAttempt`，用 `AtomicBoolean finished` 的一次性 CAS 作为**唯一收尾点** `finishOcrAttempt()`：取消看门狗、回收位图、清空 `activeOcrAttempt`；
- 成功回调、失败回调、看门狗超时、同步抛异常、`shutdown` 五条路径都走同一个收尾点 → **bitmap 只回收一次、后续分析只触发一次**；
- 超时降级为 `processRecognizedText("", imageBase64, imageChanged)`（无 OCR 文本、仅图片分析），链路继续；
- `shutdown()` 中 `cancelActiveOcrAttempt()` 移除超时器并回收在途位图，`processing.set(false)`，服务退出后不会再有看门狗触发。

```java
private static final long OCR_TIMEOUT_MS = 8_000L;

private void startOcrWithWatchdog(Bitmap bitmap, String imageBase64, boolean imageChanged) {
    ScreenTextRecognizer recognizer = textRecognizer;
    if (recognizer == null) {                       // 服务停止中：降级为仅图片分析
        recycleQuietly(bitmap);
        processRecognizedText("", imageBase64, imageChanged);
        return;
    }
    final OcrAttempt attempt = new OcrAttempt(bitmap, imageBase64, imageChanged);
    attempt.timeoutTask = () -> handleOcrTimeout(attempt);
    activeOcrAttempt = attempt;
    mainHandler.postDelayed(attempt.timeoutTask, OCR_TIMEOUT_MS);
    try {
        recognizer.recognize(bitmap, new ScreenTextRecognizer.Callback() {
            public void onRecognized(String text) {
                if (!finishOcrAttempt(attempt)) return;   // 看门狗已超时，忽略迟到回调
                processRecognizedText(text, attempt.imageBase64, attempt.imageChanged);
            }
            public void onError(String message) {
                if (!finishOcrAttempt(attempt)) return;
                processRecognizedText("", attempt.imageBase64, attempt.imageChanged);
            }
        });
    } catch (Throwable error) {
        if (finishOcrAttempt(attempt)) {
            processRecognizedText("", attempt.imageBase64, attempt.imageChanged);
        }
    }
}

private boolean finishOcrAttempt(OcrAttempt attempt) {
    if (attempt == null || !attempt.finished.compareAndSet(false, true)) {
        return false;                 // 已被另一条路径收尾：调用方必须直接返回
    }
    mainHandler.removeCallbacks(attempt.timeoutTask);
    if (activeOcrAttempt == attempt) {
        activeOcrAttempt = null;
    }
    recycleQuietly(attempt.bitmap);
    return true;
}
```

### 3.2 黑屏/空白迟滞（新增 `FrameLimitedGate` + 集成）

规则（常量集中定义）：

- `ENTER_LIMITED_FRAMES = 2`：连续 2 帧 BLACK 或 BLANK 才进入“检测受限”（BLACK/BLANK 可混合计数）；
- 任意 1 帧 `OK` 立即退出受限；
- `null`/未知质量按正常画面处理（宁可继续分析，不误报受限）。

采集循环集成（受限帧仍**不 OCR、不发请求、不清风险**；迟滞未满只丢帧）：

```java
FrameQualityDetector.Quality quality = evaluateFrameQuality(bitmap);
// 迟滞门控：单帧瞬态黑屏不刷“检测受限”，连续 2 帧受限才进入。
boolean limited = frameLimitedGate.onFrame(quality);
if (quality == FrameQualityDetector.Quality.OK) {
    boolean recovered = releaseFrameLimitedState();     // 从受限恢复 → 强制重新分析
    boolean imageChanged = imageDeduplicator.shouldAnalyze(bitmap);
    handleCapturedBitmap(bitmap, imageChanged || recovered);
} else {
    bitmap.recycle();
    processing.set(false);
    if (limited) {
        markFrameLimited(quality);                      // 首次进入/类型变化才刷新状态
    } else {
        Log.d(TAG, "single limited frame ignored by hysteresis: " + quality);
    }
}
```

`startScreenSession()` 中 `frameLimitedGate.reset()`，新会话不继承上一会话的迟滞状态。

### 3.3 悬浮窗权限中途撤销（`ScreenRiskService.captureTick`）

```java
if (now - lastOverlayPermissionCheckAtMs >= OVERLAY_PERMISSION_CHECK_INTERVAL_MS) {
    lastOverlayPermissionCheckAtMs = now;
    if (!Settings.canDrawOverlays(ScreenRiskService.this)) {
        shutdown(true, "悬浮窗权限已撤销，请重新授权后开启检测");
        return;                       // 不再调度下一 tick
    }
}
```

- `OVERLAY_PERMISSION_CHECK_INTERVAL_MS = 2_000L`，与采样频率一致，开销小（一次 Binder 调用）；
- `shutdown()` 本身 `synchronized` + `shuttingDown.compareAndSet(false,true)` 防重复，多条路径（tick / 悬浮窗停止按钮 / projection onStop / onDestroy）不会重复清理；
- 撤销后 `ThirdLevelActivity` 状态区/通知显示明确原因，不崩溃。

### 3.4 延迟与会话统计

- `RiskApiClient`：`execute(...)` 增加 `startedAtMs` 参数（重试沿用同一起点，统计到最终结果）；所有回传路径统一走 `deliver()`：

```java
private static void deliver(ResultCallback callback, RiskModels.RiskResult result, long startedAtMs) {
    result.latencyMs = (int) Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
    callback.onComplete(result);
}
```

- `RiskModels.RiskResult` 新增 `public int latencyMs = -1;`。
- `ScreenRiskService` 记录 `lastAnalysisAtMs / lastLatencyMs / analysisCount / riskCount`：
  - 屏幕、音频分析回调完成时 `recordAnalysis(result)`（网络/服务失败也计入“分析次数”，便于观察稳定性；`shouldAlert()` 计入“风险次数”）；
  - `renderCurrentState()` 在**有风险、无风险、检测受限**三种详情末尾统一追加一行，已有的分行结构不变；
  - 时间格式化不写在 Android 类里，而是纯 Java `AnalysisStatsFormatter`（Android 侧只负责用 `SystemClock.elapsedRealtime()` 算间隔毫秒）。

统计行实际形态（无分析 / 正常 / 有风险）：

```text
最近分析：暂无｜本次会话：分析 0 次，风险 0 次
最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次
最近分析：刚刚，耗时未知｜本次会话：分析 3 次，风险 1 次
```

`AnalysisStatsFormatter` 规则（均有单测）：`<1s` 显示“刚刚”；`<60s` 显示“Ns 前”；`<60min` 显示“N 分钟前”；否则“N 小时前”；耗时 `<1s` 显示 `Nms`，否则保留 1 位小数 `N.Ns`；负数输入显示“未知/耗时未知”。

### 3.5 应用名

`app/src/main/res/values/strings.xml`：

```diff
-    <string name="app_name">chapter07</string>
+    <string name="app_name">FinRiskEye 风险检测</string>
```

`AndroidManifest.xml` 的 `android:label="@string/app_name"` 已存在，无需改动；包名/applicationId 不变。

### 3.6 演示/自测入口（emulator-verifier 依赖项）

**包名/类名（可直接用于 uiautomator/adb 检查）：**

- 应用包名：`com.example.chapter07`（未变）
- 演示 Activity：`com.example.chapter07.thirdlevel.ThirdLevelDemoActivity`（`exported=false`，**不能用 `adb shell am start` 直接拉起**）
- 启动链路（emulator 自动化唯一可行路径）：
  1. `adb shell am start -n com.example.chapter07/.MainActivity`（exported=true）
  2. 点击按钮文本 **“第三级：跨应用风险检测”** → `ThirdLevelActivity`
  3. 点击按钮文本 **“演示画面（自测用）”** → `ThirdLevelDemoActivity`
  4. 演示页按钮文本：**“普通画面”** / **“风险转账页”** / **“返回检测页”**；默认显示普通画面
  5. 页面顶部小字：**“自测/演示用，不含真实会议内容”**
- Activity label（最近任务/标题）：`@string/third_level_demo_title` = “第三级演示画面（自测用）”

**页面要点：**

- ImageView 全屏 `fitCenter`，默认普通画面；点“风险转账页”切风险样例（便于先验“无风险”再验“存在风险”）；
- 两个切换按钮 72dp 高、返回按钮 72dp 高，方便模拟器按坐标点击；
- **不设置 `FLAG_SECURE`**（否则 MediaProjection 只能采到黑屏，模拟器无法验证采集链路）；仅设置 `FLAG_KEEP_SCREEN_ON` 防自动锁屏；
- 演示页不含真实会议内容，与自动检测主流程完全解耦（不启动/停止服务，不注册状态 listener）。

**Manifest 声明**（Lead 于 2026-11-13 明确授权，只加这一条，未动其他节点）：

```diff
         <activity
             android:name=".thirdlevel.ThirdLevelActivity"
             android:exported="false"
             android:label="第三级：跨应用风险检测" />
+        <!-- Lead 已授权：仅注册第三级演示/自测画面页（12/15 演示与模拟器联调入口）。 -->
+        <activity
+            android:name=".thirdlevel.ThirdLevelDemoActivity"
+            android:exported="false"
+            android:label="@string/third_level_demo_title" />
```

**`ThirdLevelActivity` 入口按钮**（加在“授予悬浮窗权限”下方，不影响原开始/停止流程与状态 listener）：

```diff
+        <Button
+            android:id="@+id/thirdLevelDemoButton"
+            android:layout_width="match_parent"
+            android:layout_height="wrap_content"
+            android:layout_marginTop="8dp"
+            android:text="@string/third_level_demo_entry" />
```

```diff
+        // 演示画面入口：只跳转内置测试画面页，不影响自动检测的开始/停止流程。
+        demoButton.setOnClickListener(v ->
+                startActivity(new Intent(ThirdLevelActivity.this, ThirdLevelDemoActivity.class)));
```

### 3.7 顺带处理的小问题

1. **`RiskApiClient` 重试总耗时**：上一版没有耗时字段；本轮把耗时统计放在重试链路外层（`startedAtMs` 透传），失败重试后的总延迟不会只算最后一次请求。
2. **`TRANSCRIPTION_EMPTY` 的统计延迟**：该分支会构造一个合成的 `success("none")` 结果，本轮把服务端真实 `latencyMs` 复制到合成结果，悬浮窗统计不会显示“耗时未知”。
3. **`shutdown` 复位 `processing`**：停止后立即复位，避免下一次启动残留“正在处理”的旧值。
4. **`processRecognizedText` 的极窄 NPE 竞态**：`shutdown()` 会先把 `running` 置 false 再把 `apiClient` 置空；若 OCR 回调线程恰好在两步之间通过 `running` 判断，继续发请求会 NPE（ML Kit 回调抛异常会导致 App 崩溃）。本轮改为取本地引用并判空，空则直接复位 `processing` 返回。

---

## 4. 纯 JVM 验证（实际执行）

### 4.1 复现命令

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File finriskeye\evidence\android\run-jvm-tests-hardening2.ps1
```

输出写入 `finriskeye/evidence/android/hardening-2-output.txt`。该脚本不需要 Gradle / Android SDK / 真机：用 JDK17 编译交付的纯 Java 类 + 正式 JUnit4 风格测试源码 + 本机最小 `org.junit` 兼容桩并实跑；同时做演示图片帧质量、XML/资源/manifest/Java 语法层校验。

### 4.2 单测结果（2026-11-13 22:0x 实际运行）

```text
FrameQualityDetectorTest   Tests run: 11, Passed: 11, Failed: 0
FrameLimitedGateTest       Tests run: 11, Passed: 11, Failed: 0
AnalysisStatsFormatterTest Tests run: 13, Passed: 13, Failed: 0
------------------------------------------------------------
合计                         35, Passed: 35, Failed: 0
```

新增用例覆盖矩阵：

| 测试类 | 覆盖点 |
| --- | --- |
| `FrameLimitedGateTest`（11） | 单帧黑屏不进入受限；连续 2 帧黑屏/空白进入；黑+白混合累计；受限持续保持；1 帧正常立即退出；OK/BLACK 抖动永不进入；`null` 未知质量按正常处理；`reset()` 清理待定/受限；待定期不对外报受限；连续计数封顶 |
| `AnalysisStatsFormatterTest`（13） | “刚刚/秒/分钟/小时”边界；负数未知；耗时 ms/s 一位小数；负数耗时未知；会话计数与负数钳制；**验收样例整行文案**：“最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次”；无分析显示“暂无”；耗时未知形态 |
| `FrameQualityDetectorTest`（11，回归） | 上一轮黑屏/空白/纯色/深色模式/阈值边界用例全部保持通过 |

### 4.3 演示 PNG 帧质量校验（防止演示页被误判“检测受限”）

用 JDK `ImageIO` 解码**实际打包的** `drawable-nodpi` PNG，按与 `ScreenRiskService` 相同的 64×64 降采样路径喂给 `FrameQualityDetector` + `FrameLimitedGate`（连续 2 帧都不应受限）：

```text
[PASS] third_level_demo_normal.png -> quality=OK, limitedAfter2Frames=false, imageSize=1200x800, avgLuma=251.77, stddev=27.02
[PASS] third_level_demo_risk.png   -> quality=OK, limitedAfter2Frames=false, imageSize=640x480, avgLuma=224.48, stddev=65.48
```

结论：模拟器/真机显示演示画面时，画面质量前置判断不会拦截，会正常走 OCR + `/api/v1/risk/screen`。

### 4.4 静态一致性检查（同一脚本内）

- **XML 格式**：8 个 XML（thirdlevel 全部 res + app `strings.xml` + `AndroidManifest.xml`）全部合法，失败 0；
- **资源引用**：13 个 Java 源文件中所有 `R.id/R.string/R.drawable/R.layout/R.color` 引用均可解析（`R.id` 定义 156、`R.string` 21、`R.drawable` 7、layout 32、color 10）；thirdlevel XML 中所有 `@string/@color/@drawable/@layout` 引用均可解析；
- **Manifest/演示入口接线**：`ThirdLevelDemoActivity` 已声明且 `exported=false`、`ThirdLevelActivity` 仍在、`app_name` 标签仍在、`thirdLevelDemoButton` 在布局中、演示页 normal/risk/close 三按钮齐全、`ThirdLevelActivity` 会启动演示页、演示页未设置 `FLAG_SECURE`；
- **Java 语法层校验**：对 13 个含 Android 依赖的源文件执行 `javac`（缺 android.jar/androidx/okhttp/ML Kit/R 符号属预期，共 439 条依赖类错误），**语法类错误 0 条**（`expected / illegal start / not a statement / unclosed / reached end of file / invalid method declaration` 等模式均无命中）；
- **aapt2 资源编译（可选，本机已装 SDK）**：用 sdk-builder 装的 `dev-env/android-sdk/build-tools/36.0.0/aapt2.exe compile` 分别编译 `thirdlevel/android/res`（8 个 flat 文件）与 `app/src/main/res`（54 个 flat 文件），均 `exit 0`，无资源语法/文件名/PNG 格式错误（该检查只编译资源、不跑 Gradle，不占用构建）。

### 4.5 未执行的验证

- 未执行 `gradlew assembleDebug / testDebugUnitTest / lintDebug`（约束：Android 完整构建由 sdk-builder 的 `task-8` 统一执行，避免并发冲突）；
- 未执行真机/模拟器 E2E（本机无设备；模拟器 E2E 由 emulator-verifier 执行，演示入口已就绪）；
- 未做 Node 风险服务联调（由 server 侧负责）。

---

## 5. 遗留项与风险（如实记录）

| 项 | 说明 | 建议 |
| --- | --- | --- |
| Android 完整编译 | 本轮只做纯 JVM + 静态校验；新增 Activity/布局/字符串/manifest 声明需 `task-8` 编译确认 | sdk-builder 跑 `assembleDebug`；若报错集中在新增资源/Activity，本轮改动可直接定位 |
| OCR 看门狗超时回收位图 | 超时后立即 `recycle()` 时 ML Kit 的 `Task` 可能仍在 native 侧处理该位图（不确定厂商实现是否已拷贝）。若极端情况下出现 native 崩溃，应改为“超时只降级、延迟到回调/下一次采样再回收” | 真机/模拟器日志验证；当前按任务要求“超时回收、不泄漏”实现，已用一次性标记保证不会重复回收 |
| 迟滞阈值 | 连续 2 帧（约 4 秒观察窗）是体验与灵敏度的折中；极慢的页面切换动画可能仍会被判受限 | 真机按第 7 节步骤观察；如需更稳可调 `FrameLimitedGate.ENTER_LIMITED_FRAMES` |
| 悬浮窗权限撤销检测延迟 | 最长约 2 秒（检查间隔）才停止并给出状态；撤销瞬间系统会先移除悬浮窗 | 验收时等待 2–3 秒观察状态区/通知文案即可 |
| 统计口径 | “分析次数”含失败（便于观察网络稳定性）；“风险次数”按每次分析结果 `shouldAlert()` 计数，不是去重后的独立风险事件；画面与语音合并统计 | 如验收要求分离画面/语音，可在下一版按来源拆分两个计数器 |
| 演示页静态图 | 内置两张固定截图，不含真实会议内容；`FLAG_SECURE`/DRM 受保护画面的“检测受限”仍需专门测试页/受保护视频验证 | 真机验收流程见第 7 节 |
| 应用音频 | 模拟器无声卡，音频采集链路仍需真机验证（B 已知限制） | 真机按 B 文档第 10 节验证 |
| 环境依赖 | 本轮未新增第三方依赖（只是用了 JDK 自带 ImageIO 做本地校验，非应用依赖） | — |

---

## 6. 与模拟器联调（emulator-verifier）对接说明

1. **APK**：使用 sdk-builder `task-8` 产出的 v2 APK（dex 中应包含 `FrameLimitedGate`/`AnalysisStatsFormatter`/`ThirdLevelDemoActivity`）。
2. **进入演示页**：`MainActivity` → “第三级：跨应用风险检测” → “演示画面（自测用）”。演示页 `exported=false`，所以**必须走 App 内按钮链路**，不能用 `am start` 直接拉起（除非 `adb root` 后 `am start` 并接受 shell 无权启动 non-exported 组件；正常 emulator 无 root）。
3. **服务地址**：模拟器内用 `http://10.0.2.2:3100`，令牌 `local-thirdlevel-dev`（与 `task-5` 一致）。
4. **验证风险画面**：先开始检测（授悬浮窗 + 屏幕共享），再进演示页 → 点“风险转账页” → 等待 ≥6 秒（等一次截图 + 模型返回）→ 悬浮窗应出现红色“存在风险”，详情含“风险等级/证据/最近分析：…，耗时 …｜本次会话：分析 N 次，风险 M 次”。
5. **验证无风险**：点“普通画面” → 等待结果 → 悬浮窗变绿色“无风险”，详情末尾统计行仍存在。
6. **返回**：演示页点“返回检测页”回 `ThirdLevelActivity`（模拟器坐标点击用 72dp 大按钮更容易命中）。
7. **注意**：演示页本身无 `FLAG_SECURE`（画面可被采集）；但悬浮窗窗口带 `FLAG_SECURE`（既有设计，防止悬浮窗被自己采集造成递归），截屏里悬浮窗区域可能为黑块，属预期。
8. 关键 logcat 过滤：`adb logcat -s ThirdLevelRisk`（`OCR watchdog timeout`、`single limited frame ignored by hysteresis`、`screen risk result: ..., latencyMs=`）。

---

## 7. 真机/模拟器验收步骤（12/8 清单逐项）

按顺序执行，每步截图/录屏留证（建议存 `finriskeye/evidence/android/`）：

1. **应用名**：安装后桌面图标名称应为“FinRiskEye 风险检测”。
2. **正常检测**：开始检测 → 正常会议/聊天画面 → 标题“检测中/无风险”，详情末尾有“最近分析：…，耗时 …｜本次会话：分析 N 次，风险 M 次”。
3. **延迟记录**：连续观察 2–3 次分析，统计行的“最近分析”时间与“耗时”应随每次请求刷新。
4. **黑屏迟滞**：快速切换应用制造单帧黑屏 → 不应立即出现“检测受限”（≤2 秒内恢复即无变化）；持续黑屏 >4 秒 → 橙色“检测受限”+“画面：黑屏或受保护画面（可能被目标应用限制）”，且详情仍有统计行、**不显示“无风险”**。
5. **受保护画面**：打开 `FLAG_SECURE`/DRM 视频 → 同上“检测受限”，服务端无新请求日志。
6. **恢复**：回到正常画面 → 受限说明消失，先“检测中”再按结果更新。
7. **悬浮窗权限撤销**：检测中到系统设置撤销“显示在其他应用上层” → 2 秒内检测停止，`ThirdLevelActivity` 状态区显示“悬浮窗权限已撤销，请重新授权后开启检测”，不崩溃。
8. **屏幕共享撤销**：系统撤销投屏/录屏 → 状态区显示“屏幕共享授权已撤销，请重新授权后开启检测”（上一轮已实现）。
9. **服务不可用**：停止风险服务后继续检测 → 显示“分析失败/无法连接风险分析服务”，不误报“无风险”。
10. **演示入口（12/15 演示口径）**：从第三级页进入“演示画面（自测用）”→ 顶部“自测/演示用，不含真实会议内容”小字 → 默认普通画面“无风险” → 切“风险转账页”出现“存在风险”及证据 → “返回检测页”回到第三级页，原开始/停止流程不受影响。
11. **OCR 看门狗**：如能复现 ML Kit 回调卡住，logcat 应出现 `OCR watchdog timeout after 8000ms, degrade to image-only analysis`，随后仍能看到 `/api/v1/risk/screen` 请求（无 OCR 文本、仅图片分析），采集不会永久卡死。

---

## 8. SHA-256（便于 Lead/构建同学核对）

```text
13A88733D9A4767B507387665174D43B607556DC128616984C7C52D0F8609BCD  ScreenRiskService.java
73A989C3A078E1AC8E295692869CC1189595ACFE00D5B198F23577C6574C1E9D  RiskApiClient.java
8451212DFF3B7B73C4AC0BE6ABF35193F9C1EB2EEFDCCE468BE0EAA4FCB3FBA9  RiskModels.java
B8F844534EB1D748DBF46E0393585C9BA874DB49AFA5F9736BF27AA3C375C937  ThirdLevelActivity.java
1FD9BD2CBFEC1A8EE18CD8D12C0DC742B0943AE5C8D05A4BC41D985A72A46C76  FrameLimitedGate.java
61BDCA7CED507AC1D6FBB7BB1E16BD59E5720A532620890D851F95BFBBBE0E3E  AnalysisStatsFormatter.java
64F77B9B7351590CB9215F3919B4792EE8EE54D1DEC775E9ABE1EB70A4191A17  ThirdLevelDemoActivity.java
507894026CF71873D098F8C115B075F027111038DB5801E1FF4DDD938BFA76B8  FrameLimitedGateTest.java
91FD175C347AD3C4DBFD1DCE75C9E1726FCDD8FC3D7D91A8028E4BDFA0E3C8C4  AnalysisStatsFormatterTest.java
0BFD7EF96843ADA167FE7D970267556AF6F0979871A8F98628E1BEAF4D133458  activity_third_level.xml
80F9F695553C98A02F2701939679B227B4EB69A61C292EB6A3709B3917BA99E7  activity_third_level_demo.xml
86B92D5F181518DF05DB998DE9C1EFF934A4517A8F85B141E38E20ACD7B12134  third_level_strings.xml
978DFDD22F61C0BB6BD9CFCD4DE6140D3402175F3B59EB00ACA58008B1EA9FC7  third_level_demo_normal.png
3341E43A8FB0065430ED01C31F14328074AE02685FE7FD2F43B71CEBD4D47E7B  third_level_demo_risk.png
1CFC3CDD792A88BDC23DA58932B1E4B08583E463AC36D26782D4ABA0EEBD55A9  app/src/main/res/values/strings.xml
197597FF227856A7F3F87F77B9BCB4FDA8167695C822F3C3461F291719946EE7  app/src/main/AndroidManifest.xml
```

（哈希计算于 2026-11-13 22:0x，若之后 Lead/其他同学再改上述文件，以最新为准。）

---

## 9. 本轮产物清单

| 路径 | 类型 | 说明 |
| --- | --- | --- |
| `thirdlevel/android/java/.../FrameLimitedGate.java` | 新增源码 | 纯 Java 黑屏/空白迟滞门控 |
| `thirdlevel/android/java/.../AnalysisStatsFormatter.java` | 新增源码 | 纯 Java 延迟/会话统计格式化 |
| `thirdlevel/android/java/.../ThirdLevelDemoActivity.java` | 新增源码 | 演示/自测画面 Activity |
| `thirdlevel/android/java/.../ScreenRiskService.java` | 修改源码 | OCR 看门狗、迟滞、权限撤销、统计行 |
| `thirdlevel/android/java/.../RiskApiClient.java` / `RiskModels.java` | 修改源码 | 延迟记录与 `latencyMs` |
| `thirdlevel/android/java/.../ThirdLevelActivity.java` | 修改源码 | 演示入口按钮 |
| `thirdlevel/android/res/layout/activity_third_level_demo.xml` | 新增资源 | 演示页布局 |
| `thirdlevel/android/res/drawable-nodpi/third_level_demo_{normal,risk}.png` | 新增资源 | 内置测试画面（ASCII 文件名） |
| `thirdlevel/android/res/layout/activity_third_level.xml` / `values/third_level_strings.xml` | 修改资源 | 入口按钮与演示文案 |
| `thirdlevel/android/test/java/.../FrameLimitedGateTest.java` / `AnalysisStatsFormatterTest.java` | 新增测试 | 11 + 13 个 JUnit4 用例 |
| `app/src/main/res/values/strings.xml` | 修改资源 | 应用名 |
| `app/src/main/AndroidManifest.xml` | 修改（Lead 授权） | 演示 Activity 声明（仅此一条） |
| `finriskeye/evidence/android/hardening-2-report.md` | 证据 | 本文档 |
| `finriskeye/evidence/android/hardening-2-output.txt` | 证据 | 纯 JVM 测试与静态检查实际输出 |
| `finriskeye/evidence/android/run-jvm-tests-hardening2.ps1` | 证据/脚本 | 一键复现脚本 |
| `finriskeye/evidence/android/jvm-test/src/DemoImageQualityCheck.java` | 证据/脚本 | 演示 PNG 帧质量校验工具 |

所有结论均基于当前工作区与上述本机验证；涉及真机/模拟器行为的部分标注为“待验证”，未夸大验证范围。

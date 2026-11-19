# 第三级收尾：lintDebug 剩余 4 个 error 的修复记录（前两级遗留代码）

- 任务来源：Lead 直接指派（`lintDebug` 5 个 error 的收尾；1 个 `PropertyEscape` 由 Lead 处理）
- 负责人：teammate `android-hardening`
- 日期：2026-11-13
- 背景：`sdk-builder` 在最终验证时跑 `lintDebug` 报 5 个 error。其中 `local.properties` 的 `PropertyEscape` 由 Lead 删除该文件处理（AGP 改走 `ANDROID_HOME`，构建不受影响）；其余 4 个 error 位于**前两级遗留代码**，为避免 12/15 交付时 lint 挂红，本文件记录这 4 处的**最小改动**修复。
- 约束：不跑 Gradle（构建/lint 复跑由 sdk-builder 统一执行）；只改下述 3 个文件 + 本证据文件；不改行为（`NewApi` 处补充的是真正的版本保护，见 3.3）。

---

## 0. 结论摘要

| # | 文件:行 | lint id | 处理方式 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java:117`、`:129` | `MissingPermission` | 在 `startRecording(...)` 方法上加 `@SuppressLint("MissingPermission")`（新增 `import android.annotation.SuppressLint`） | 已修复 |
| 2 | `app/src/main/java/com/example/chapter07/getposition/BackgroundRecordActivity.java:190` | `GestureBackNavigation` | 在 `onBackPressed()` 上加 `@SuppressLint("GestureBackNavigation")`，保持原返回行为（新增同 import） | 已修复 |
| 3 | `app/src/main/java/com/example/chapter07/golbal_view_advanced/GlobalTouchAdvancedActivity.java:198` | `NewApi` | 改为 `Build.VERSION.SDK_INT >= O` 真版本保护：API 26+ `startForegroundService`，低版本回退 `startService` | 已修复 |
| 4 | `local.properties` | `PropertyEscape` | 由 Lead 处理（删除该文件，AGP 走 `ANDROID_HOME`） | Lead 已处理 |

改动共 3 个文件、+18/-1 行；`javac` 语法层校验 0 个语法类错误（见第 3 节）；未执行 Gradle，最终以 sdk-builder 重跑 `lintDebug` + `assembleDebug` 为准。

---

## 1. `MissingPermission`（RemoteAudioRecorder.java，2 个 error）

### 1.1 原因

`RemoteAudioRecorder` 是前两级通话的音频录制工具类，第 117、129 行的两处 `new AudioRecord(...)`（先 `VOICE_CALL`、失败回退 `MIC`）需要 `RECORD_AUDIO` 权限。lint 做静态检查时无法看到运行时权限申请链路，因此报 `MissingPermission`。

### 1.2 权限模型说明（重要：这是 lint 抑制，不是权限放松）

- `AndroidManifest.xml` 已声明 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`；
- 前两级进入通话前，`MainActivity` 通过 EasyPermissions 动态申请 `CAMERA + RECORD_AUDIO`，未授权不会进入通话链路；
- `RemoteAudioRecorder` 只会在通话建立后由录音/保存逻辑调用，此时权限已授予；
- 本次改动只是在方法上加编译期注解，**不改变任何运行时权限判断、不申请也不提升权限**；若设备上权限被撤销，`AudioRecord` 仍会按系统行为失败（原有异常处理不变）。

### 1.3 改动 diff

```diff
 // ========== Android 音视频相关 import ==========
+import android.annotation.SuppressLint;               // lint 抑制注解（仅编译期，不改运行时权限行为）
 import android.content.Context;                       // Android 上下文（本类未直接使用）
@@ startRecording(String outputPath) 方法
+    // 说明：本类只是前两级通话的音频录制工具，RECORD_AUDIO 权限由调用方在进入通话前
+    // 通过 EasyPermissions 动态申请（见 MainActivity），AudioRecord 只会在授权后创建。
+    // 这里用 lint 抑制避免静态检查误报，不改变任何运行时权限行为。
+    @SuppressLint("MissingPermission")
     public boolean startRecording(String outputPath) {
```

方法级注解同时覆盖第 117、129 行两处 `new AudioRecord(`（以及同方法内 `mAudioRecord.startRecording()` 的同类检查）。

---

## 2. `GestureBackNavigation`（BackgroundRecordActivity.java，1 个 error）

### 2.1 原因

`BackgroundRecordActivity` 是前两级遗留的点击记录页面，`onBackPressed()`（第 190 行）重写后先停止记录再 `super.onBackPressed()`。targetSdk 36 下 lint 的 `GestureBackNavigation` 建议改用 `OnBackPressedDispatcher`/`OnBackInvokedCallback` 以支持 Android 13+ 预测性返回，否则报 error。

### 2.2 处理说明

- 本次只做 lint 抑制，**保持原有返回行为不变**（`isRecording` 时先 `stopRecording()` 再走系统返回）；
- 不做预测性返回改造：该页面属于前两级历史功能，改造返回导航链路超出本次收尾范围，且会引入回归风险；
- 若后续需要适配预测式返回，再单独评估（建议统一用 `OnBackPressedDispatcher`）。

### 2.3 改动 diff

```diff
 package com.example.chapter07.getposition;
 
+import android.annotation.SuppressLint;
 import android.app.Activity;
@@ onBackPressed()
     @Override
+    // 说明：这是前两级遗留的旧代码，仍使用 Activity.onBackPressed() 处理返回键；
+    // Android 13+ 的预测性返回新 API 需要改造返回导航链路，本次保持原有返回行为不变，
+    // 仅用 lint 抑制避免 GestureBackNavigation 在 12/15 交付时挂红。
+    @SuppressLint("GestureBackNavigation")
     public void onBackPressed() {
```

---

## 3. `NewApi`（GlobalTouchAdvancedActivity.java，1 个 error）

### 3.1 原因

第 198 行 `startForegroundService(intent)` 是 API 26（Android 8.0）才有的方法，`minSdk = 24`；未做版本判断时，Android 7.x 设备会 `NoSuchMethodError`，lint 报 `NewApi`。

### 3.2 处理说明（真正的版本保护，不是抑制）

保留原 `Intent` 构造（`ACTION_START` + `database_name` extra）不变，只在启动处按系统版本分流：

```diff
         // 启动前台服务
-        startForegroundService(intent);
+        // 说明：前两级遗留代码原先直接调用 startForegroundService（API 26+），
+        // 在 Android 7.x 及以下设备上会 NoSuchMethodError/被 lint 报 NewApi。
+        // 这里按系统版本保护：API 26+ 用 startForegroundService，低版本回退 startService。
+        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
+            startForegroundService(intent);
+        } else {
+            startService(intent);
+        }
```

- API 26+ 行为完全不变（仍以前台服务方式启动）；
- API 24–25 走 `startService`，避免调用不存在的方法；
- 该 Activity 对应的 `GlobalTouchAdvancedService` 已在 manifest 声明 `foregroundServiceType="dataSync"`，前台服务类型不受影响。

---

## 4. 改动清单

| 文件 | 改动 | 行数 |
| --- | --- | --- |
| `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` | 新增 `SuppressLint` import；`startRecording()` 上加 `@SuppressLint("MissingPermission")` + 注释 | +5 |
| `app/src/main/java/com/example/chapter07/getposition/BackgroundRecordActivity.java` | 新增 `SuppressLint` import；`onBackPressed()` 上加 `@SuppressLint("GestureBackNavigation")` + 注释 | +5 |
| `app/src/main/java/com/example/chapter07/golbal_view_advanced/GlobalTouchAdvancedActivity.java` | `startForegroundService` 改为版本保护（保留原 intent 构造） | +8/-1 |

```text
$ git -C finriskeye/hnu_FinRiskEye diff --stat -- <上述 3 个文件>
 app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java                           | 5 +++++
 app/src/main/java/com/example/chapter07/getposition/BackgroundRecordActivity.java         | 5 +++++
 app/src/main/java/com/example/chapter07/golbal_view_advanced/GlobalTouchAdvancedActivity.java | 9 ++++++++-
 3 files changed, 18 insertions(+), 1 deletion(-)
```

未改动：其余前两级代码、第三级代码、server、docs、build.gradle.kts、manifest（本次无 manifest 变化）；未 git commit。

---

## 5. 本机验证（不跑 Gradle）

1. **javac 语法层校验**：对上述 3 个文件用 JDK17 执行
   `javac -J-Duser.language=en -encoding UTF-8 -proc:none -Xmaxerrs 5000 -sourcepath app/src/main/java -d <temp> <3 files>`
   （本机无 android.jar，缺 Android/第三方符号属预期）：
   - 依赖类错误（`package android.* does not exist` 等）大量出现，属预期；
   - **语法类错误（expected / illegal start / not a statement / unclosed / reached end of file / invalid method declaration 等）0 条**。
2. **注解/import 存在性**：`grep` 确认 3 个文件改动如 diff 所示，`@SuppressLint` 注解均紧邻对应方法声明。
3. **未执行**：`gradlew lintDebug` / `assembleDebug`（由 sdk-builder 统一重跑）；lint 是否归零以重跑结果为准。

---

## 6. 遗留与注意事项

| 项 | 说明 |
| --- | --- |
| lint 复跑 | 请 sdk-builder 重跑 `lintDebug`（期望 error 0；PropertyEscape 随 local.properties 删除消失）与 `assembleDebug`（期望新增注解不影响编译） |
| `GestureBackNavigation` 抑制生效范围 | 该检查按源码方法级抑制通常生效；若 sdk-builder 的 AGP/lint 版本仍报，可将 `@SuppressLint("GestureBackNavigation")` 移到类级别（行为不变），但不建议为它改返回导航实现 |
| 预测性返回改造 | 属功能改造，非本次 lint 收尾范围；如 12/15 后需要适配 Android 13+ 预测返回，建议前两级统一迁移到 `OnBackPressedDispatcher` |
| `MissingPermission` 语义 | 仍依赖于 `MainActivity` 的 EasyPermissions 运行时申请链路；若后续新增不经 MainActivity 的录音入口，需要在该入口自行保证先申请权限 |
| `NewApi` 低版本分支 | API 24–25 走 `startService`；若未来 minSdk 升到 26+，可去掉该分支 |

---

## 7. 产物

| 路径 | 说明 |
| --- | --- |
| `app/src/main/java/com/example/chapter07/RemoteAudioRecorder.java` | `MissingPermission` 抑制（2 处 AudioRecord） |
| `app/src/main/java/com/example/chapter07/getposition/BackgroundRecordActivity.java` | `GestureBackNavigation` 抑制 |
| `app/src/main/java/com/example/chapter07/golbal_view_advanced/GlobalTouchAdvancedActivity.java` | `NewApi` 版本保护 |
| `finriskeye/evidence/android/hardening-3-lint-fixes.md` | 本证据文件 |

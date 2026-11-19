# task-9：FLAG_SECURE 自测页 + 遗留页面全局 edge-to-edge 兜底 + 悬浮窗权限误判修复（v1.6）

- 任务：`task-9`（Lead 指派；含 Lead 追加的“遗留页面测试入口”与“MIUI 悬浮窗权限误判”两项）
- 负责人：teammate `android-hardening`
- 日期：2026-11-19
- 代码基线：`finriskeye/hnu_FinRiskEye` HEAD `18c014c`（Lead 已提交，工作区开始为干净状态）
- 构建环境：`finriskeye\tools\build-debug-env.ps1`（JDK 21 + SDK 36，全部可写目录重定向到 `finriskeye\build-home`）
- 约束遵守：**未 adb/install/操作手机**（Lead 正在真机联调音频）、**未 git commit**、未改 `docs/`、`evidence/device/`、`ARTIFACTS`、`dev-env`

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| A. 受保护画面（FLAG_SECURE）自测页 | **完成**：`ThirdLevelProtectedDemoActivity`（FLAG_SECURE + EdgeToEdgeInsets + 返回按钮），演示页新增“受保护画面（自测）”入口，manifest 注册 `exported=false` + `Theme.Chapter07.NoActionBar` |
| A2. 遗留页面测试入口（Lead 追加） | **完成**：演示页新增“打开遗留页面（测试）”按钮，进入 `getposition.get`（非 OpenCV 页，避免 native 初始化风险），用于验证全局 insets 兜底 |
| B. 遗留 Activity 全局 edge-to-edge 兜底 | **完成**：新增 `FinRiskEyeApplication`，API≥35 时给所有未适配 Activity 的 DecorView 追加 systemBars padding（跳过 4 个已适配页面），API<35 完全不动 |
| C. MIUI 悬浮窗权限误判 | **完成**：连续 **3 次**（约 6 秒）检测不到权限才 `shutdown`；只失败 1–2 次时在状态里提示“悬浮窗权限可能已撤销，风险提示可能不可见”并**继续检测**，恢复后自动清除提示 |
| D. 版本与构建 | **完成**：versionCode=7 / versionName=1.6，`assembleDebug` 成功；APK 已复制并核对 size/SHA256/badging/dex |
| E. 证据 | 本文件 + `finriskeye/evidence/build-v3/`（build log、APK、badging、dex/manifest 校验、APK info） |

**APK（供 Lead 真机安装）**

```text
路径：C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\evidence\build-v3\app-debug-v1.6.apk
大小：114,642,254 字节（约 109.33 MB）
SHA256：B81DC9F56A950FC2085EE38E0CDECF37CB421E0D01A45D0E5C5312498EB98E5F
versionCode / versionName：7 / 1.6
包名：com.example.chapter07（未变）
```

> 注：本文件中的 SHA256/大小对应**含 Lead 追加“打开遗留页面（测试）”按钮后的最终一次构建**。构建共跑两次（首次 build 成功后收到 Lead 追加需求，第二次增量重打），最终 APK 以第二次为准。

---

## 1. 改动清单

| 路径（相对 `finriskeye/hnu_FinRiskEye`） | 类型 | 说明 |
| --- | --- | --- |
| `app/src/main/java/com/example/chapter07/FinRiskEyeApplication.java` | 新增源码 | Application + ActivityLifecycleCallbacks；API≥35 时对未适配遗留 Activity 的 DecorView 追加 systemBars insets |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ThirdLevelProtectedDemoActivity.java` | 新增源码 | FLAG_SECURE 受保护画面自测页（全屏说明文字 + 返回按钮 + EdgeToEdgeInsets） |
| `thirdlevel/android/res/layout/activity_third_level_protected_demo.xml` | 新增资源 | 受保护画面页布局（root id=`thirdLevelProtectedDemoRoot`） |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ThirdLevelDemoActivity.java` | 修改源码 | 演示页新增两个入口：受保护画面、遗留页面（`getposition.get`） |
| `thirdlevel/android/res/layout/activity_third_level_demo.xml` | 修改资源 | 新增 `thirdLevelDemoProtectedButton`、`thirdLevelDemoLegacyButton`（各 64dp 高） |
| `thirdlevel/android/res/values/third_level_strings.xml` | 修改资源 | 新增 4 条文案：“受保护画面（自测）”“打开遗留页面（测试）”、受保护页标题/说明/返回 |
| `thirdlevel/android/java/com/example/chapter07/thirdlevel/ScreenRiskService.java` | 修改源码 | 悬浮窗权限检查改为连续 3 次失败才停；1–2 次失败只提示并继续检测（含恢复清除） |
| `app/src/main/AndroidManifest.xml` | 修改 | `<application android:name=".FinRiskEyeApplication">`；注册 `ThirdLevelProtectedDemoActivity`（exported=false、NoActionBar 主题） |
| `app/build.gradle.kts` | 修改 | versionCode 6→7、versionName 1.5→1.6 |
| `finriskeye/evidence/android/device-fixes-2026-11-19.md` | 证据 | 本文件 |
| `finriskeye/evidence/build-v3/` | 证据 | `app-debug-v1.6.apk`、`build-v1.6.log`、`apk-badging-v1.6.txt`、`apk-manifest-check-v1.6.txt`、`dex-class-check-v1.6.txt`、`apk-info-v1.6.txt` |

未改动：前两级业务代码、server、docs、ARTIFACTS、evidence/device、dev-env；未执行 git add/commit。

---

## 2. A：受保护画面（FLAG_SECURE）自测页

### 2.1 目的

真机开始检测后打开该页，MediaProjection 只能采到黑屏，检测端应显示「检测受限 / 黑屏或受保护画面」，而不是误报「无风险」。

### 2.2 实现要点

`ThirdLevelProtectedDemoActivity`：

```java
// FLAG_SECURE 必须在页面显示前设置：让系统窗口标记为受保护内容，
// MediaProjection / 截屏只能得到黑屏，用于验证检测端的黑屏判定。
getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
);
setContentView(R.layout.activity_third_level_protected_demo);
// 与演示页一致：Android 15+ 强制 edge-to-edge，需要给根布局补系统栏 padding。
EdgeToEdgeInsets.apply(this, findViewById(R.id.thirdLevelProtectedDemoRoot));
findViewById(R.id.thirdLevelProtectedDemoBackButton).setOnClickListener(v -> finish());
getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
```

- 页面文案：标题“受保护画面（自测）”，说明“本页已设置 FLAG_SECURE，系统禁止截屏与屏幕采集，MediaProjection 只能得到黑屏。用于验证检测端显示「检测受限 / 黑屏或受保护画面」，而不是误报「无风险」。”，底部 72dp“返回演示页”按钮；
- 不启动/停止检测服务，仅作自测页；
- manifest：

```xml
<activity
    android:name=".thirdlevel.ThirdLevelProtectedDemoActivity"
    android:exported="false"
    android:label="@string/third_level_protected_title"
    android:theme="@style/Theme.Chapter07.NoActionBar" />
```

### 2.3 演示页入口

`activity_third_level_demo.xml` 在图片与底部按钮之间新增两个 64dp 高按钮：

- **“受保护画面（自测）”** → `ThirdLevelProtectedDemoActivity`；
- **“打开遗留页面（测试）”**（Lead 追加）→ `com.example.chapter07.getposition.get`。

选择 `getposition.get` 而不是 `OpenCvDemoActivity` 的原因：`getposition.get` 是普通 `Activity` + 简单数据库页，不依赖 OpenCV native 初始化，真机进入不会因 OpenCV 加载失败/异常而崩溃；同时它是 **DarkActionBar 遗留页**，正好用于观察全局 insets 兜底效果。

`ThirdLevelDemoActivity` 对应代码：

```java
findViewById(R.id.thirdLevelDemoProtectedButton).setOnClickListener(v ->
        startActivity(new Intent(this, ThirdLevelProtectedDemoActivity.class)));
// 遗留 Activity 均 exported=false，adb shell 无法直接启动，因此由 App 内置按钮进入。
findViewById(R.id.thirdLevelDemoLegacyButton).setOnClickListener(v ->
        startActivity(new Intent(this, com.example.chapter07.getposition.get.class)));
```

---

## 3. B：一、二级遗留 Activity 全局 edge-to-edge 兜底

### 3.1 背景

工程 targetSdk=36，Android 15/16 强制 edge-to-edge。上一轮已对 `MainActivity`、`ThirdLevelActivity`、`ThirdLevelDemoActivity` 用 `EdgeToEdgeInsets` + NoActionBar 主题单独适配；但一、二级还有大量遗留 Activity（`peer`、`OpenCvDemoActivity`、`getposition` 各页等）仍用默认 `Theme.Chapter07`（DarkActionBar），在 Android 15+ 上 ActionBar/首行内容会被状态栏遮挡。

### 3.2 实现：`FinRiskEyeApplication`

`app/src/main/java/com/example/chapter07/FinRiskEyeApplication.java`：

```java
public class FinRiskEyeApplication extends Application {
    private static final int EDGE_TO_EDGE_MIN_SDK = 35; // Android 15

    private static final Set<String> ADAPTED_ACTIVITY_NAMES = new HashSet<>(Arrays.asList(
            "com.example.chapter07.MainActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelDemoActivity",
            "com.example.chapter07.thirdlevel.ThirdLevelProtectedDemoActivity"
    ));

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < EDGE_TO_EDGE_MIN_SDK) {
            return; // 旧系统（如平板 Android 12）不强制 edge-to-edge，保持原行为
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                applyLegacyEdgeToEdgeFallback(activity);
            }
            // 其余 6 个生命周期回调空实现（接口无默认实现）
            ...
        });
    }

    private static void applyLegacyEdgeToEdgeFallback(Activity activity) {
        if (activity == null || ADAPTED_ACTIVITY_NAMES.contains(activity.getClass().getName())) {
            return; // 4 个已适配页面跳过，避免重复 padding
        }
        Window window = activity.getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);

        final View decorView = window.getDecorView();
        // 原始 padding 只读取一次；listener 多次触发时按 base 重算，避免重复累加。
        final int baseLeft = decorView.getPaddingLeft();
        final int baseTop = decorView.getPaddingTop();
        final int baseRight = decorView.getPaddingRight();
        final int baseBottom = decorView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(decorView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // 系统栏图标与夜间模式一致
        int nightMode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(window, decorView).setAppearanceLightStatusBars(lightBars);
        WindowCompat.getInsetsController(window, decorView).setAppearanceLightNavigationBars(lightBars);

        decorView.requestApplyInsets();
    }
}
```

manifest 增加：

```xml
<application
    android:name=".FinRiskEyeApplication"
    ... >
```

### 3.3 行为边界（重要）

- **API ≥ 35**：未适配的遗留 Activity 全部追加 systemBars padding（ActionBar 整体下移，不再被状态栏/刘海遮挡）；不删除/不隐藏各页 ActionBar；
- **API < 35**：`onCreate` 直接 return、不注册回调，旧平板（Android 12）行为完全不变；
- 已适配的 4 个页面（MainActivity / ThirdLevelActivity / ThirdLevelDemoActivity / ThirdLevelProtectedDemoActivity）跳过，避免与页面内 `EdgeToEdgeInsets` 叠加造成双重留白；
- padding 基于 base 重算，不会因 insets 多次分发而重复累加。

---

## 4. C：MIUI 悬浮窗权限偶发误判修复

### 4.1 问题

真机上出现过一次 `Settings.canDrawOverlays()` 偶发返回 false，原实现（1 次失败即 shutdown）导致检测被误停。

### 4.2 实现（`ScreenRiskService`）

```java
// 连续 3 次（约 6 秒）都检测不到权限才停止；只失败 1-2 次时仅提示、继续检测。
private static final int OVERLAY_PERMISSION_MISSING_SHUTDOWN_THRESHOLD = 3;
private static final String OVERLAY_PERMISSION_WARNING =
        "悬浮窗权限可能已撤销，风险提示可能不可见";
```

captureTick 中每 2 秒的检查改为：

```java
if (!Settings.canDrawOverlays(ScreenRiskService.this)) {
    consecutiveOverlayPermissionMisses++;
    Log.w(TAG, "overlay permission check failed, consecutive=" + consecutiveOverlayPermissionMisses);
    if (consecutiveOverlayPermissionMisses >= OVERLAY_PERMISSION_MISSING_SHUTDOWN_THRESHOLD) {
        // 连续 3 次（约 6 秒）都拿不到权限才认为确实被撤销，避免偶发误判误停检测。
        shutdown(true, "悬浮窗权限已撤销，请重新授权后开启检测");
        return;
    }
    // 只失败 1-2 次：把可能的风险写进状态，检测继续跑，不停止服务。
    updateOverlayPermissionWarning(true);
} else if (consecutiveOverlayPermissionMisses > 0) {
    consecutiveOverlayPermissionMisses = 0;   // 恢复：清计数与提示，继续正常检测
    updateOverlayPermissionWarning(false);
}
```

- 警告文案追加在悬浮窗/通知详情中（有风险/无风险/受限三种状态都有，位置在错误行之后、统计行之前），**不改变标题与检测流程**；
- 提示只在状态变化时刷新一次（`updateOverlayPermissionWarning` 内部判重），不会每 2 秒刷屏；
- 连续 3 次失败仍会 `shutdown(true, "悬浮窗权限已撤销，请重新授权后开启检测")`，保留“真撤销”的明确状态；
- logcat（tag `ThirdLevelRisk`）关键日志：
  - `overlay permission check failed, consecutive=N`
  - `overlay permission missing once or twice, keep detecting: ...`
  - `overlay permission check recovered, clear warning`
  - 最终停止：`screen risk session stopped: 悬浮窗权限已撤销，请重新授权后开启检测`

---

## 5. D：版本与构建结果

### 5.1 版本

```kotlin
versionCode = 7
versionName = "1.6"
```

### 5.2 构建命令（实际执行）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "finriskeye\tools\build-debug-env.ps1" `
  -ProjectPath "C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye" `
  -Task assembleDebug -GradleArgs "--no-daemon"
```

- 构建日志：`finriskeye/evidence/build-v3/build-v1.6.log`
- 结果：`BUILD SUCCESSFUL`，最终一次 **Gradle 35 秒 / 脚本总耗时 38 秒**（含 Lead 追加按钮的增量重新构建；首次完整构建为 51 秒/54 秒）
- 唯一告警：`C:\Users\79047\.android\analytics.settings 拒绝访问`（沙箱允许范围内的非致命 metrics 警告，与历史构建一致）；另有既有 gradle.properties 里 `android.enableAapt2/enableBuildCache/enableJetifier` 的 deprecated 警告，均不影响产物。

### 5.3 APK 校验（已保存到 `finriskeye/evidence/build-v3/`）

| 项 | 值 |
| --- | --- |
| APK | `finriskeye\evidence\build-v3\app-debug-v1.6.apk`（绝对路径：`C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\evidence\build-v3\app-debug-v1.6.apk`） |
| 大小 | 114,642,254 字节 / 109.33 MB |
| SHA256 | `B81DC9F56A950FC2085EE38E0CDECF37CB421E0D01A45D0E5C5312498EB98E5F` |
| aapt2 badging | `package: name='com.example.chapter07' versionCode='7' versionName='1.6' ...`（见 `apk-badging-v1.6.txt`） |
| 打包后 Manifest | `application android:name="com.example.chapter07.FinRiskEyeApplication"`；包含 `ThirdLevelDemoActivity`、`ThirdLevelProtectedDemoActivity`（见 `apk-manifest-check-v1.6.txt`） |
| dex 类校验 | 13 个 dex、共 12,463 个类；`Lcom/example/chapter07/FinRiskEyeApplication;`、`Lcom/example/chapter07/thirdlevel/ThirdLevelProtectedDemoActivity;`、`ThirdLevelDemoActivity`、`ScreenRiskService`、`getposition.get`、`EdgeToEdgeInsets` 均存在（见 `dex-class-check-v1.6.txt`） |
| 签名 | debug 签名（沿用 `validateSigningDebug`） |

证据文件：

```text
finriskeye/evidence/build-v3/app-debug-v1.6.apk
finriskeye/evidence/build-v3/build-v1.6.log
finriskeye/evidence/build-v3/apk-badging-v1.6.txt
finriskeye/evidence/build-v3/apk-manifest-check-v1.6.txt
finriskeye/evidence/build-v3/dex-class-check-v1.6.txt
finriskeye/evidence/build-v3/apk-info-v1.6.txt
```

---

## 6. 真机验证步骤（供 Lead 安装 v1.6 后执行）

### 场景 1：FLAG_SECURE 受保护画面 → 检测受限

1. 安装 v1.6 APK，打开 MainActivity → “第三级：跨应用风险检测”；
2. 授予悬浮窗权限 → 填服务地址/token → 开始检测 → 系统弹窗允许屏幕共享；
3. 等待首次分析（约 2–4 秒），正常画面应为“检测中/无风险”；
4. 点“演示画面（自测用）”→ 点 **“受保护画面（自测）”**；
5. 等待约 4–6 秒（2 个采样周期），预期：
   - 悬浮窗标题变为橙色 **“检测受限”**，详情出现 **“画面：黑屏或受保护画面（可能被目标应用限制）”**；
   - **不得显示“无风险”**；
   - 详情末尾仍有“最近分析/本次会话”统计行；
6. 点“返回演示页”/返回键 → 回到演示页（可采集）→ 约 2–4 秒后受限自动解除，恢复“检测中/无风险”。

> 注意：受保护页本身 FLAG_SECURE，系统截屏/录屏也看不到其内容，这属于预期；请用另一台设备拍照或直接观察本机屏幕。

### 场景 2：遗留页面全局 edge-to-edge 兜底

1. 打开“演示画面（自测用）” → 点 **“打开遗留页面（测试）”**，进入 `getposition.get`（DarkActionBar 遗留页）：
   - 预期：状态栏下方不再压住 ActionBar/首行内容；标题栏完整可见、内容不被状态栏/刘海遮挡；
2. 返回；如需要可再从 MainActivity 进入其他遗留页（如 `peer`、OpenCvDemoActivity、后台点击记录等）对照检查，均应在状态栏下方完整显示；
3. 旧平板（Android 12）装上同包应保持原布局**不变**（Application 兜底只在 API≥35 生效）。

### 场景 3：MIUI 悬浮窗权限偶发误判

1. 检测运行中，反复进出系统设置/切换前后台，模拟 MIUI 权限检查偶发 false；
2. 预期：1–2 次误判时检测**不停**，悬浮窗/通知详情出现“悬浮窗权限可能已撤销，风险提示可能不可见”，随后若权限检查恢复该行消失；
3. 仅当连续 3 次（约 6 秒）都失败时才停止，并显示“悬浮窗权限已撤销，请重新授权后开启检测”；
4. 可随时 `adb logcat -s ThirdLevelRisk` 观察 `overlay permission check failed, consecutive=` 等日志（本机验证由 Lead 执行 adb）。

---

## 7. 遗留风险与注意

| 项 | 说明 |
| --- | --- |
| Application insets 兜底范围 | 只对 `Build.VERSION.SDK_INT >= 35` 生效；旧系统（平板 Android 12）行为不变。若个别遗留 Activity 自带 WindowInsets 处理（少见），可能与全局 padding 叠加，需要在真机上抽查；如发现具体页面，可在 `ADAPTED_ACTIVITY_NAMES` 中将其加入跳过并单独适配 |
| 极少数自绘 Window/DecorView 的页面 | 全局 padding 加在 DecorView 上，理论上对自定义 Decor 布局可能有额外留白；本次抽查 `getposition.get` 等常见遗留页，其他页面建议真机顺手看一眼 |
| FLAG_SECURE 的 MediaProjection 行为 | 绝大多数设备采到纯黑（可被 `FrameQualityDetector` 判 BLACK）；个别厂商可能输出灰屏/最后一帧，此时可能无法判为“黑屏受限”。本次自测页用于验证典型行为，若真机出现灰屏需单独调阈值 |
| 悬浮窗权限检查仍是启发式 | MIUI 偶发误判用“连续 3 次”折中；若某些 ROM 出现“持续返回 false 但悬浮窗其实可见”，仍可能误停。真机如复现，可再加大阈值或改为结合悬浮窗 View 是否 attached 判断（需真机样本） |
| 真机验证依赖 Lead | 本机未 adb/install；两个场景的最终结论以 Lead 真机验证为准 |

---

## 8. 产物清单

| 路径 | 类型 | 说明 |
| --- | --- | --- |
| `app/src/main/java/com/example/chapter07/FinRiskEyeApplication.java` | 新增源码 | 全局遗留页面 edge-to-edge 兜底 |
| `thirdlevel/android/java/.../ThirdLevelProtectedDemoActivity.java` | 新增源码 | FLAG_SECURE 受保护画面自测页 |
| `thirdlevel/android/res/layout/activity_third_level_protected_demo.xml` | 新增资源 | 受保护画面页布局 |
| `thirdlevel/android/java/.../ThirdLevelDemoActivity.java` | 修改源码 | 两个测试入口（受保护画面 / 遗留页面） |
| `thirdlevel/android/res/layout/activity_third_level_demo.xml` | 修改资源 | 两个新按钮 |
| `thirdlevel/android/res/values/third_level_strings.xml` | 修改资源 | 新增文案 |
| `thirdlevel/android/java/.../ScreenRiskService.java` | 修改源码 | 悬浮窗权限 3 次迟滞 + 提示 |
| `app/src/main/AndroidManifest.xml` | 修改 | Application name + 受保护页注册 |
| `app/build.gradle.kts` | 修改 | versionCode 7 / versionName 1.6 |
| `finriskeye/evidence/android/device-fixes-2026-11-19.md` | 证据 | 本文件 |
| `finriskeye/evidence/build-v3/*` | 证据 | APK + 构建日志 + 校验结果 |

所有结论基于本机构建与静态/产物校验；真机行为以 Lead 实测为准（本文件已给出验证步骤与预期现象）。

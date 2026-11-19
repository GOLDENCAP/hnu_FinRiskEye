# FinRiskEye 第三级 Android 构建环境与构建记录（2026-11-13）

本文件记录：本机 Android 构建环境如何搭好、基线/最终工程如何编译出 debug APK、
遇到的全部环境坑与修复方式，以及在新机器上的一键复现步骤。

工作区：`C:\Users\79047\Documents\deepseek-harness\default-workspace`
项目根：`finriskeye`
所有命令均在 **Windows PowerShell 5.1**（本机没有 PowerShell 7）下执行。

---

## 0. 一键复现（新机器）

```powershell
# 1) 安装 JDK 21 + Android SDK（JDK 走清华镜像；SDK 组件走 dl.google.com）
cd <工作区>\finriskeye
powershell -ExecutionPolicy Bypass -File tools\setup-dev-env.ps1

# 2) 构建基线工程（交接包原始工程的原样副本，只用于证明可编译）
powershell -ExecutionPolicy Bypass -File tools\build-debug-env.ps1

# 3) 构建最终工程（第三级交付工程）
powershell -ExecutionPolicy Bypass -File tools\build-debug-env.ps1 -ProjectPath ..\hnu_FinRiskEye
```

产物：

| 工程 | APK 路径 |
| --- | --- |
| 基线 | `finriskeye\build-baseline\app\build\outputs\apk\debug\app-debug.apk` |
| 最终 | `finriskeye\hnu_FinRiskEye\app\build\outputs\apk\debug\app-debug.apk` |
| 交付副本 | `finriskeye\ARTIFACTS\app-debug-thirdlevel-2026-11-13.apk` |

机器可正常写 `%USERPROFILE%\.gradle`、`%USERPROFILE%\.android`、`%TEMP%` 时，
第 2/3 步也可直接用工程自带的 `build-debug.ps1`（`tools\build-debug-env.ps1` 只是为了
在本机沙箱下把可写目录重定向到 workspace，见第 4 节）。

首次安装约需下载：JDK 196 MB + cmdline-tools 136 MB + SDK 组件约 2.4 GB（NNDK 占大头）。
本机实测：环境安装 6.9 分钟；冷 Gradle 缓存基线构建 3.3 分钟；热缓存最终工程构建 1.1 分钟。

---

## 1. 环境清单（实测版本）

| 组件 | 版本 / 路径 |
| --- | --- |
| OS | Windows 11 家庭版 10.0.26200 x64，16 vCPU，15.2 GB RAM |
| Shell | Windows PowerShell 5.1.26100.7705（Desktop；无 PS7，脚本必须 5.1 兼容且 ASCII-only） |
| JDK | Eclipse Temurin **21.0.13+9 LTS** → `finriskeye\dev-env\jdk\jdk-21` |
| Android SDK | `finriskeye\dev-env\android-sdk` |
| ├ platform-tools | **37.0.1** |
| ├ platforms | **android-36 (rev 2)** |
| ├ build-tools | **36.0.0** |
| ├ cmake | **3.22.1** |
| └ ndk | **28.2.13676358 (r28c)** |
| Gradle | **9.3.1**（wrapper，`gradle-wrapper.properties`；发行包来自 tencent 镜像缓存） |
| AGP | **9.1.0**（`gradle/libs.versions.toml`） |
| 工程参数 | compileSdk=36 / minSdk=24 / targetSdk=36 / Java source-target=11 |
| 长路径 | `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled=1` |

完整记录见同目录 `env-versions.txt`、`java-version.txt`、`sdkmanager-list-installed.txt`。

---

## 2. 交付构建结果

### 2.1 最终工程（第三级交付 APK）

- 工程：`finriskeye\hnu_FinRiskEye`（含 android-integrator 当日的黑屏检测/采集受限改动）
- 构建命令：`powershell -ExecutionPolicy Bypass -File tools\build-debug-env.ps1 -ProjectPath ..\hnu_FinRiskEye`
- Gradle 结果：`BUILD SUCCESSFUL in 1m 6s`，41 tasks executed
- 墙钟耗时：**1.1 分钟**（热 Gradle 缓存；构建前已有基线构建缓存的依赖）
- APK 交付副本：`finriskeye\ARTIFACTS\app-debug-thirdlevel-2026-11-13.apk`
- 大小：**113,458,173 字节（108.20 MB）**
- SHA-256：`7E97049236800BCEE95F7677E163C54E0961FB6C9CE56FE89652829C59085DC2`
- 完整日志：`build-final.log`
- 可解析性：`aapt2 dump badging` 正常 → package `com.example.chapter07` v1.0，
  minSdk 24 / targetSdk 36 / compileSdk 36，native-code `arm64-v8a` `armeabi-v7a`；
  `apksigner verify` → APK Signature Scheme v2 通过（Android debug 证书）。
  证据：`apk-badging-deliver.txt`、`apk-signature.txt`。
- 内容校验：APK 内 `classes6.dex` 含 `FrameQualityDetector`、`ScreenRiskService`，
  与基线 APK 的 `classes6.dex` 相比从 94,032 字节变为 98,528 字节，
  说明该 APK 确实由当前（含 integrator 改动）源码编译，而不是复用旧产物。

### 2.2 基线工程（交接包原始工程原样副本）

- 工程：`finriskeye\build-baseline`（由交接包 `handover\...\hnu_FinRiskEye` 原样复制，
  未改任何源码；仅新增 `local.properties` 指向本机 SDK）
- 构建命令：`powershell -ExecutionPolicy Bypass -File tools\build-debug-env.ps1`
- Gradle 结果：`BUILD SUCCESSFUL in 3m 16s`，41 tasks executed
- 墙钟耗时：**3.3 分钟**（冷 Gradle 缓存，含依赖解析与 NDK/CMake 原生编译）
- APK：`finriskeye\build-baseline\app\build\outputs\apk\debug\app-debug.apk`
- 大小：113,458,173 字节（108.20 MB）
- SHA-256：`1D9E4B45F26828B3FE7582222AB8DBADF1E3FECDE8BD7013C37D6509A4CC8A12`
- 完整日志：`build.log`（末尾摘要在 `build-tail.log`）
- 对照：交接包自带 APK（同学 B 的机器构建）114,575,684 字节，
  SHA-256 `E5BCFAC65A2CCAD4ECB064E39DC96F853C5D70948F82255644472410BA8C53BC`。
  大小/SHA 不同属正常：debug APK 含构建时间戳与不同 JDK/AGP 产出的字节差异，
  本机重编成功即证明工程可编译。

---

## 3. 遇到的坑与修复（按发生顺序）

### 坑 1：`api.adoptium.net` 下载 JDK 极慢（195 MB 约 1.5 小时）
- 现象：`curl` 实测 ~68 KB/s，脚本会卡在 JDK 步骤。
- 修复：改用清华 TUNA Adoptium 镜像（实测 ~44 MB/s），保留官方 API 作 fallback。
  见 `setup-dev-env.ps1` 中 `$jdkUrls` 数组：
  `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip`
- 日志证据：`setup-dev-env.log.attempt1-adoptium-slow.log`。

### 坑 2：PowerShell 5.1 + `$ErrorActionPreference="Stop"` 下捕获 native stderr 会抛终止错误
- 现象：JDK 装好后脚本死于 `NativeCommandError`（`java -version` 写 stderr + `2>&1`）。
- 修复：新增 `Get-NativeOutput`，捕获前临时把 `$ErrorActionPreference` 降为 `Continue`。
- 日志证据：`setup-dev-env.log.attempt2-javaversion-crash.log`。

### 坑 3：PowerShell 5.1 在无交互控制台时把 stdin 管道给 `.bat` 报 codepage 错
- 现象：`$yes | & sdkmanager.bat --licenses` →
  `Program 'sdkmanager.bat' failed to run: Valid values are between 0 and 65535, inclusive. Parameter name: codepage`
- 修复：改用 `cmd.exe /c ""sdkmanager.bat" --licenses < yes.txt"` 由 cmd 做 stdin 重定向；
  另外保留兜底：若 `--licenses` 失败，直接写入标准 license 哈希文件。
  （用 PowerShell 7 或交互式控制台时一般不会遇到这个问题。）

### 坑 4（最关键）：沙箱限制子进程只能写 workspace，导致 sdkmanager/Gradle/JVM 四处 AccessDenied
- 现象：`sdkmanager --list` 报 `Warning: Failed to download any source lists!` /
  `Warning: IO exception while downloading manifest`，看起来像网络问题，实际是
  `java.nio.file.AccessDeniedException: C:\Users\79047\.android\cache\sdkbin-1_...`
  （`--verbose` 日志见 `diag\sdkmanager-verbose.log`）。
- 结论：PowerShell 宿主进程可以随意读写，但 **child process（java.exe/cmake/ninja...）
  只能写 workspace 内路径**；写 `%USERPROFILE%\.android`、`%USERPROFILE%\.gradle`、
  `%TEMP%` 都会被拒绝。网络本身是通的（Java 直连 HTTPS 与 curl 均 200）。
- 修复：把所有工具的可写目录重定向到 workspace 内：
  1) `ANDROID_USER_HOME` → `finriskeye\build-home\android-user-home`
     （sdkmanager 下载缓存、debug keystore 都落在这里）
  2) `GRADLE_USER_HOME` → `finriskeye\build-home\gradle-home`
     （wrapper dists、依赖 modules-2、daemon 日志、构建缓存）
  3) `TEMP` / `TMP` → `finriskeye\build-home\tmp`，并设
     `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<tmp> -XX:-UsePerfData`
  4) `ANDROID_HOME` / `ANDROID_SDK_ROOT` → `finriskeye\dev-env\android-sdk`
  以上由 `tools\build-debug-env.ps1` 统一设置；`tools\setup-dev-env.ps1` 对
  sdkmanager 也做同样设置。
- 提速：把已有 `C:\Users\79047\.gradle\wrapper\dists`（145 MB）和
  `C:\Users\79047\.gradle\caches\modules-2`（275 MB）用 PowerShell 的
  `Copy-Item` 拷进 `build-home\gradle-home`（host 进程可读它们），
  避免 wrapper 重新下载发行包、减少依赖下载。不做这步也能构建，只是首构建更慢。

### 坑 5：AGP 分析数据仍尝试写 `%USERPROFILE%\.android`（仅警告）
- 构建日志中 `Unable to initialize metrics, ensure C:\Users\79047\.android is writable ... (拒绝访问)`。
- 影响：仅 analytics 上报失败，纯 warning，不影响编译/打包/签名。
  （AGP 的 analytics 路径不跟随 `ANDROID_USER_HOME`。）

### 坑 6：Gradle/AGP 弃用告警（无需处理）
- `android.enableJetifier=true`、`android.enableAapt2=true`、`android.enableBuildCache=false`
  为工程 `gradle.properties` 的历史配置，AGP 9.1 下仅告警，构建正常。
- `mergeDebugNativeLibs` 报 `libopencv_java4.so` 在 jniLibs 与 maven 依赖中重复，
  AGP 9.1 只告警并选择其中一份，构建/打包正常（与交接文档一致）。

### 坑 7：`hnu_FinRiskEye\local.properties` 未由本 agent 写入
- 沙箱的自动审查阻止了向 `hnu_FinRiskEye` 写文件（该目录不在本 agent 原始写范围）。
- 影响：无。AGP 通过 `ANDROID_HOME` 环境变量同样能定位 SDK，两次构建均成功。
- 如需要该文件，由 Lead/android-integrator 在该工程下写入一行即可：
  `sdk.dir=C:/Users/79047/Documents/deepseek-harness/default-workspace/finriskeye/dev-env/android-sdk`
  （`build-baseline\local.properties` 已有可直接参考的样例。）

---

## 4. `tools\` 下脚本说明

| 脚本 | 作用 |
| --- | --- |
| `setup-dev-env.ps1` | 安装 Temurin JDK 21 + Android SDK（platform-tools、platform 36、build-tools 36.0.0、cmake 3.22.1、ndk 28.2.13676358）。可重复执行（幂等）：已装的部分自动跳过。镜像/许可/沙箱重定向均已处理。 |
| `build-debug-env.ps1` | 通用构建包装：设置第 3 节坑 4 所述全部环境变量，再调用工程自带的 `build-debug.ps1`（工程脚本本身不改）。`-ProjectPath` 指定工程目录，默认 `build-baseline`。 |
| `test-risk-api.ps1` | （其它队友的可选 Node 服务联调脚本，与本构建无关。） |

**为什么工程自带的 `build-debug.ps1` 不直接在本机用**：它只设置
`JAVA_HOME/ANDROID_HOME/PATH`，没有重定向 Gradle home、TEMP 等；在本机沙箱下
Gradle 会立刻因 `%USERPROFILE%\.gradle` 不可写而失败。在可正常写用户目录的机器上，
直接跑 `build-debug.ps1` 即可，`build-debug-env.ps1` 也无副作用。

**幂等性验证**：环境装好后再次执行 `setup-dev-env.ps1`（已装组件自动跳过、重新做
license 检查与安装校验）→ exit code 0，耗时仅 0.09 分钟，输出
`Build environment ready.`；日志见 `setup-dev-env-rerun.log`。

---

## 5. 复现注意事项

1. 必须用 **Windows PowerShell 5.1 或 PowerShell 7** 均可（脚本保持 ASCII-only 且 5.1 兼容；
   包含中文的 `.md` 用 UTF-8 保存）。
2. 首次安装需要网络访问：`mirrors.tuna.tsinghua.edu.cn`、`dl.google.com`；
   依赖解析走 `maven.aliyun.com`（工程 `settings.gradle.kts` 已配好），
   Gradle 发行包走 tencent 镜像（`gradle-wrapper.properties`）。
3. 若换机器后沙箱策略不同（可写用户目录），可以先尝试不套 wrapper 直接
   `.\build-debug.ps1`；失败时的报错特征就是 `AccessDeniedException` + 上述路径。
4. `finriskeye\build-home\` 是纯缓存（Gradle home / tmp / android user home），
   可随时删除重建，不影响交付物；删除后首次构建会重新下载依赖（约 5-10 分钟）。
5. `finriskeye\dev-env\` 是安装目录（JDK/SDK，约 3 GB），不要提交/打包进交付物；
   `dev-env\downloads` 里的安装包 zip 可删。
6. `local.properties` 属本机配置（含绝对路径），交付包里由使用者按自己路径生成；
   `build-baseline` 里保留了一份本机样例。

---

## 6. 证据文件清单（同目录）

| 文件 | 内容 |
| --- | --- |
| `env-versions.txt` | 主机/JDK/SDK/Gradle/AGP/环境变量/磁盘 汇总 |
| `java-version.txt` | `java -version` 原始输出 |
| `sdkmanager-list-installed.txt` | `sdkmanager --list_installed` 原始输出 |
| `setup-dev-env.log` | 最终成功一轮环境安装完整日志（含 license 文本） |
| `setup-dev-env.log.attempt1/2/3-*.log` | 三次失败尝试日志（对应坑 1/2/3/4） |
| `build.log` | 基线构建完整日志 |
| `build-tail.log` | 基线构建日志末尾 |
| `build-final.log` | 最终工程构建完整日志 |
| `build-tail-final.log` | 最终工程构建日志末尾 |
| `build-summary.txt` | 两个 APK 的路径/大小/SHA256/耗时/环境 摘要 |
| `setup-dev-env-rerun.log` | 环境脚本幂等性复跑日志（exit 0，5 秒） |
| `apk-badging-deliver.txt` | `aapt2 dump badging`（交付/基线 APK） |
| `apk-signature.txt` | `apksigner verify --print-certs`（交付 APK） |
| `baseline-app-debug.apk` | 基线 APK 副本（对照证据，交付用 APK 在 `ARTIFACTS\`） |
| `diag\` | 诊断过程日志（sdkmanager 失败/代理/verbose 等） |

交付目录 `finriskeye\ARTIFACTS\`：
- `app-debug-thirdlevel-2026-11-13.apk`（最终交付 APK）
- `SHA256SUMS-2026-11-13.txt`（交付 APK 与基线 APK 的校验值）

# HNU FinRiskEye 第三级：Android 模拟器端到端联调报告（阶段一/二/三）

- 任务：task-5；执行 teammate：`emulator-verifier`
- 时间：2026-11-13 21:54 ~ 22:20（Asia/Shanghai）
- 目标：无真机条件下用 Android 模拟器完成"画面采集 → OCR/画面质量 → 风险服务 → 悬浮窗"的 E2E 证据
- **总判定：模拟器环境已搭好，但本机模拟器无法 boot（qemu 进程被安全软件/外部挂起），阶段二/三的运行时 E2E 未能执行。本报告如实记录完成项、阻塞点、已收集证据与替代验证。**

## 1. 结论摘要

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 阶段一 | 装 emulator + API36 系统镜像、建 AVD、boot 模拟器、boot 截图 | **部分完成 / 阻塞于 boot**：SDK、系统镜像、AVD、WHPX 全部就绪；模拟器 4 次启动均因 qemu 线程被挂起而无法 boot，无 boot 截图 |
| 阶段二 | 现有 APK（v1）安装 → 第三级配置 → MediaProjection → 采集/OCR/请求风险服务 → 截屏+logcat+服务端日志 | **未执行**（无可用设备）；改为宿主机 APK 静态校验 + 服务端健康检查 |
| 阶段三 | v2 APK 演示画面入口：风险转账页→红色"存在风险"+证据；普通画面→"无风险"；停止按钮/通知 | **未执行**（无可用设备）；v2 最终 APK 已静态校验（含 ThirdLevelDemoActivity） |

阶段一阻塞的完整诊断见 `emulator-setup.md` 与 `diagnostics/emulator-process-thread-states.txt`。

## 2. 阶段一：环境搭建（已完成部分）

- ✅ `emulator` 37.2.13 已在 SDK 中；`emulator-check accel` → `WHPX(10.0.26200) is installed and usable`。
- ✅ `system-images;android-36;google_apis;x86_64` rev 7 安装成功（第 1 次因残留 `.installer` 状态 vendor.img 解压失败，清理后第 2 次成功）。
- ✅ AVD `finriskeye_api36` 创建成功（pixel_6、API36 Google APIs x86_64、4C/2GB RAM/4GB data、1080x2400@420dpi、AVD 目录在 `finriskeye\emulator-home\avd`）。
- ❌ **模拟器 boot 失败（阻塞点）**：4 次启动尝试（WHPX 后台 job / WMI 脱管 / `-accel off` 纯 TCG / 窗口模式）均在启动 10~20 秒后出现
  `qemu-system-x86_64-headless.exe` 全部线程 `Wait, Suspended`、CPU 累计时间冻结、`adb devices` 恒为 `emulator-5554 offline`；
  console 日志停在 QEMU "Starting QEMU main loop"/netsim 初始化之后。
- 主因假设：火绒 HIPS（HipsDaemon Running）+ McAfee/Defender 等安全软件拦截并挂起虚拟化进程（详见 `diagnostics/security-software.txt`）；已排除 WHPX/GPU/TCG/后台 job 归属等因素。
- 未产出 boot 截图（`adb shell screencap` 无设备可执行），`screenshots/` 仅含说明 README。

## 3. 阶段二：现有 APK（v1）运行时联调 —— 未执行

计划路径（未能执行）：`adb install app-debug-thirdlevel-2026-11-13.apk` → `appops set com.example.chapter07 SYSTEM_ALERT_WINDOW allow` → `am start -n com.example.chapter07/.thirdlevel.ThirdLevelActivity` → uiautomator dump + input 填 `http://10.0.2.2:3100` / `local-thirdlevel-dev` → 开始检测 → MediaProjection 系统弹窗允许 → 等 10-20s → 截图/logcat `ThirdLevelRisk`/服务端请求比对。

原因：无 boot 设备，adb 无法 install/start/screencap/logcat。

已完成替代验证（宿主机，`diagnostics/v2-apk-static-verification.txt`）：
- v1 APK：113,458,173 B，SHA256 `7E97049236800BCEE95F7677E163C54E0961FB6C9CE56FE89652829C59085DC2`，包名 `com.example.chapter07`，minSdk 24 / targetSdk 36，含 `ThirdLevelActivity`。
- 服务端在宿主机可达：`GET 127.0.0.1:3100/health` → `{"ok":true,"service":"thirdlevel-risk-service","version":"1.1.0","model_configured":true,...}`（v1.1.0 加固版，token 与 URL 已从 .env 确认：`local-thirdlevel-dev`，模拟器内地址应为 `http://10.0.2.2:3100`）。

## 4. 阶段三：v2 APK 演示画面 E2E —— 未执行

计划路径（未能执行）：安装最终 v2 → 开始检测 → 进入"演示画面（自测用）" → 切"风险转账页" → 等 ≥8s → 截图确认悬浮窗红色"存在风险"+证据 → 切"普通画面"验证"无风险" → 点停止 → 保存截图/logcat/服务端请求记录。

原因同上：无 boot 设备。

已完成静态校验（`diagnostics/v2-apk-static-verification.txt`）：
- 最终 v2 APK：`finriskeye\ARTIFACTS\app-debug-thirdlevel-v2-2026-11-13.apk`，114,597,846 B，
  SHA256 `6C13AB43750215D7016082008A79F0B770BDFDB88857F3797DE231657FF6D186`（与 Lead 定稿一致），应用名 `FinRiskEye 风险检测`；
- `aapt2 dump xmltree` 确认 manifest 含 `com.example.chapter07.thirdlevel.ThirdLevelActivity` 与 **`com.example.chapter07.thirdlevel.ThirdLevelDemoActivity`**（演示画面入口已编入最终制品，exported=false，需 App 内进入）。
- 未验证：入口点击链路、风险转账页渲染、OCR/视觉分析、`/api/v1/risk/screen` 实际请求与响应、悬浮窗红色"存在风险"、普通画面"无风险"、停止按钮/通知、黑屏迟滞 2 帧逻辑、延迟统计行。

## 5. 未验证项清单（相对 task-5 验收目标）

1. 模拟器内 MediaProjection 授权弹窗与屏幕采集会话启动；
2. ML Kit 中文 OCR 在模拟器上的识别效果（APK 含 `text-recognition-chinese` 打包模型，但未运行）；
3. 模拟器内 `10.0.2.2:3100` 网络连通与 Bearer token 鉴权；
4. 采集→OCR/画面质量→`/api/v1/risk/screen`→悬浮窗 全链路；
5. 悬浮窗 SYSTEM_ALERT_WINDOW 覆盖显示（appops 无法设置，因无设备）；
6. 风险/无风险画面切换与 2 帧清除迟滞、`ThirdLevelRisk` logcat；
7. 停止按钮/前台通知；音频采集（本就需真机，模拟器 `-no-audio`，不在目标内）；
8. `screencap` 截图与真机 UI 坐标点击验证。

## 6. 证据与限制说明

- **限制**：本机安全软件（火绒 HIPS 等）在无交互/无管理员会话中挂起 qemu，导致模拟器无法 boot；这属于**环境/主机策略限制**，不是项目代码或构建产物问题。解除需管理员在桌面会话加白名单（步骤见 `emulator-setup.md` 第 7 节）。
- **不修改**：本次未修改项目源码/docs/ARTIFACTS/server，未改安全软件配置，未 git commit。
- **替代证据充分性**：第三级功能交付证据仍应以"构建 + 40 单测 + 服务端 42 单测/真实 E2E + 用户真机验收"为主（Lead 已确认其他证据足够）；本报告作为"模拟器路线已尝试并给出精确 blocker"的补充材料，供 11/28、12/8 检查说明。

## 7. 证据索引（`finriskeye\evidence\emulator\`）

| 证据 | 路径 |
| --- | --- |
| 环境搭建与启动全记录（含命令/参数/4 次尝试/复测步骤） | `emulator-setup.md` |
| 系统镜像安装失败/成功日志 | `sdkmanager-install.log`、`sdkmanager-install2.log` |
| 4 次启动 console 日志 | `emulator-console.log`、`emulator-console-detached.log`、`emulator-console-tcg.log`、`emulator-console-windowed.log` |
| qemu 线程挂起证据与根因分析 | `diagnostics/emulator-process-thread-states.txt` |
| Windows crashpad APPCRASH 事件 | `diagnostics/windows-app-crash-events.txt` |
| 主机安全软件清单（主因假设） | `diagnostics/security-software.txt` |
| 主机/加速/AVD/adb/服务端健康 | `diagnostics/system-info.txt`、`accel-check-and-version.txt`、`avd-config.*`、`hardware-qemu.ini`、`adb-devices.txt`、`server-health.txt` |
| v1/v2 APK 静态校验 | `diagnostics/v2-apk-static-verification.txt` |
| 截图目录（无截图，仅说明） | `screenshots/README.md` |

## 8. 时间线

| 时间 | 事件 |
| --- | --- |
| 21:54:40 | 认领 task-5，检查 workspace/SDK/服务端（3100 已运行） |
| 21:55:31 | 第 1 次安装系统镜像 → 21:57:24 失败（stale `.installer`，vendor.img 解压错误） |
| 21:58:34 | 清理残留后第 2 次安装 → 成功（API36 google_apis x86_64 rev7） |
| 22:00:02 | 创建 AVD `finriskeye_api36`（pixel_6）并调优 config |
| 22:00:22 | 启动尝试 #1（WHPX，后台 job）→ 4s 后 AVD 停写、线程挂起 |
| 22:10:5x | 手动 NtResumeProcess 验证"确为外部 Suspend"→ 22:11:02 退出 code=-36863 |
| 22:11:41 | 启动尝试 #2（WMI 脱管）→ t+20s 51 线程 Suspended |
| 22:13:48 | 启动尝试 #3（-accel off TCG）→ t+10s 68/68 Suspended |
| 22:16:05 | 启动尝试 #4（窗口模式）→ 无法建窗 0xC0000005 |
| 22:16~22:20 | 收集诊断证据（线程状态/事件日志/安全软件/accel/APK 静态校验/服务端健康），撰写本报告与 emulator-setup.md |

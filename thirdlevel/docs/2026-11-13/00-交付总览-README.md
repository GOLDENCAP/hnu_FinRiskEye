# HNU FinRiskEye · 第三级「跨应用金融风险检测」交付包

> 更新：2026-11-13（第二轮，推进 11/28–12/8 目标） ｜ 计划交付：2026-12-15 ｜ 当前检查点：11 月 18 日已完成，正在准备 11 月 28 日 / 12 月 8 日验收

本项目是一个 Android 金融反诈风险识别应用，按四级能力逐步交付：

| 级别 | 能力 | 状态 |
| --- | --- | --- |
| 第一级 | 基于本地视频的金融风险词识别 | 原有工程能力，保留 |
| 第二级 | 基于视频会议（WebRTC / 自建 Go 信令服务）的风险识别 | 已有实现（线上服务当前不可达，见 `docs/待确认事项与下一步.md`） |
| **第三级** | **用户授权后跨应用采集屏幕/可采集的播放音频 → OCR/视觉分析 → 风险服务 → 悬浮窗与通知提示** | **本交付包的主体；已完成两轮开发与验证** |
| 第四级 | 全屏持续风险识别 | 已出预研方案，见 `docs/第四级-全屏持续检测方案.md`（不列入本阶段保底） |

## 1. 当前状态（2026-11-13）

### 第一轮（已交付）

- 同学 B 的第三级实现完整导入工程（交接包见 `handover/交接给搭档-第三级-2026-11-08/`）；
- 本机搭好便携 Android 构建环境（JDK 21 + SDK 36 + NDK 28.2 + CMake 3.22.1，`tools/setup-dev-env.ps1` 一键重建）；
- 重新构建 APK 成功；用真实硅基流动模型跑通服务端文本/画面/语音 E2E（13/13）；
- 补上「黑屏/受保护画面不得误报为无风险」的采集受限状态（`FrameQualityDetector`）。

### 第二轮（本轮新增，面向 11/28–12/8）

- **Android 12/8 验收补强**：
  - OCR 看门狗（8s 超时降级为仅图片分析，位图只回收一次）；
  - 黑屏/空白**迟滞**（连续 2 帧才进入「检测受限」，避免瞬态黑帧闪烁）；
  - 悬浮窗权限中途撤销检测（明确提示并安全停止）；
  - 悬浮窗/通知新增**延迟与会话统计**（“最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次”）；
  - 应用名改为「FinRiskEye 风险检测」；
  - 新增**演示/自测画面页**（内置普通画面/风险转账页，供演示与模拟器联调）。
- **服务端加固与部署化（v1.1.0）**：按令牌+IP 限流（默认 60/分钟，429 `RATE_LIMITED`）、`/health` 增加 version/model_configured/uptime、结构化请求日志、优雅关闭、幂等缓存自动清理；新增 `deploy/`（Dockerfile、docker-compose、nginx HTTPS 示例、Windows 启动脚本、部署说明）。
- **验证结果**：
  - Gradle 单元测试 **40/40 通过**；Android Lint **0 error / 415 warning**；`assembleDebug` 成功；
  - 服务端 `npm test` **42/42 通过**；真实硅基流动文本模型 E2E（含限流/幂等/401 行为）7/7 通过；
  - 模拟器端到端尝试：本机安全软件（火绒 HIPS/McAfee/Defender）会挂起 qemu，模拟器无法 boot，已记录诊断与白名单复测步骤（`evidence/emulator/emulator-setup.md`）；真机验证仍不可省略。
- **最终交付 APK（v2，含本轮全部 Android 改动）**：
  - 路径：`ARTIFACTS/app-debug-thirdlevel-v2-2026-11-13.apk`
  - 大小：114,597,846 B（109.29 MB）
  - SHA-256：`6C13AB43750215D7016082008A79F0B770BDFDB88857F3797DE231657FF6D186`
  - 校验：`aapt2` badging 正常、`apksigner` v2 通过、dex 含 FrameQualityDetector/FrameLimitedGate/AnalysisStatsFormatter/ThirdLevelDemoActivity
  - （第一轮 v1 APK 保留在 `ARTIFACTS/app-debug-thirdlevel-2026-11-13.apk` 作对照）
- **待真机验证**：在平板上安装 v2 APK，用腾讯会议实测画面/白板/音频，记录设备与软件版本、端到端延迟（清单见 `docs/12-8测试计划与记录表.md`）。

## 2. 目录结构

```text
finriskeye/
├─ README.md                     # 本文件：交付说明与快速开始
├─ hnu_FinRiskEye/               # 主工程（Android + 第三级 Node 服务 + 部署 + 接口文档）
│  ├─ app/                       # Android 主模块（一、二级功能 + 第三级入口）
│  ├─ thirdlevel/
│  │  ├─ android/                # 第三级 Android 源码/资源（由 app/build.gradle.kts sourceSets 接入）
│  │  ├─ server/                 # 第三级 Node 风险服务（.env 不入库；含 42 项测试）
│  │  ├─ test-assets/            # 测试图片与语音素材
│  │  ├─ API.md                  # 第三级接口文档（含 429 限流与 /health 字段）
│  │  ├─ docs/2026-11-13/        # 随仓库保存的交付/验证文档
│  │  └─ 第三级测试与交付说明-2026-11-08.md
│  ├─ deploy/                    # Dockerfile / docker-compose / nginx / Windows 启动脚本 / 部署说明
│  ├─ gradle/                    # Gradle 9.3.1 wrapper 与版本清单
│  ├─ build-debug.ps1            # 一键构建（使用 ../dev-env）
│  └─ logcat.ps1                 # 查看真机日志（使用 ../dev-env 里的 adb）
├─ dev-env/                      # 便携构建环境（不入库，可脚本重建）
├─ build-home/                   # 构建隔离缓存（Gradle home/temp/android-user-home，不入库）
├─ tools/
│  ├─ setup-dev-env.ps1          # 一键安装构建环境（JDK21 + Android SDK）
│  ├─ build-debug-env.ps1        # 沙箱安全的构建/测试包装脚本（-Task testDebugUnitTest/lintDebug/assembleDebug）
│  └─ test-risk-api.ps1          # 服务端 E2E 测试脚本（真实模型/报告生成）
├─ evidence/
│  ├─ build/                     # 第一轮构建证据
│  ├─ build-v2/                  # 第二轮构建、单测、Lint、APK 校验证据
│  ├─ server/                    # 服务端 E2E 证据 + hardening/ 加固证据
│  ├─ android/                   # Android 审查、JVM 测试、两轮补强证据
│  └─ emulator/                  # 模拟器端到端尝试证据
├─ docs/                         # 交付文档（总览、检查点、测试计划、部署、四级方案、演示脚本）
├─ build-baseline/               # 原始交接工程基线构建副本（可删除/重建）
├─ handover/                     # 同学 B 的原始交接包（只读参考）
└─ ARTIFACTS/                    # 最终交付物（APK v1/v2、源码包、git bundle、文档包、校验和）
```

## 3. 快速开始

### 3.1 构建 Android APK

**方式 A：使用本包自带的自动环境（推荐，Windows）**

```powershell
pwsh -File tools\setup-dev-env.ps1        # 首次：安装便携 JDK21 + Android SDK
cd hnu_FinRiskEye
.\build-debug.ps1                          # 产物：app\build\outputs\apk\debug\app-debug.apk
```

> 本机沙箱下需用 `tools\build-debug-env.ps1`（把 ANDROID_USER_HOME/GRADLE_USER_HOME/TEMP 重定向到工作区），详见 `evidence/build/build-env-notes.md`。

**方式 B：使用 Android Studio**

用 Android Studio 打开 `hnu_FinRiskEye/`（Gradle 9.3.1、AGP 9.1.0、compileSdk 36、JDK 21），按提示安装 NDK 28.2.13676358 与 CMake 3.22.1，构建 `assembleDebug`。

### 3.2 启动第三级风险服务

```powershell
cd hnu_FinRiskEye\thirdlevel\server
Copy-Item .env.example .env               # 首次：填入 AI_API_KEY 与 RISK_API_TOKEN
npm start                                 # 默认 0.0.0.0:3100；GET /health 返回 version=1.1.0
```

- 密钥只放在服务端 `.env`（已 gitignore），不要写进 APK / 仓库；
- 加固版支持限流（`RATE_LIMIT_PER_MINUTE`，默认 60/分钟）、结构化日志、优雅关闭；
- Docker / nginx / Windows 一键启动方式见 `hnu_FinRiskEye/deploy/README.md`；
- 平板与电脑需在同一局域网，防火墙放行 TCP 3100。

### 3.3 平板安装与使用

1. 安装 `ARTIFACTS/app-debug-thirdlevel-v2-2026-11-13.apk`；
2. 打开 App → 主界面点击「第三级：跨应用风险检测」；
3. 填写风险服务地址与测试令牌；
4. 点击「授予悬浮窗权限」，在系统设置中允许「显示在其他应用上层」；
5. （可选）勾选「同时采集应用播放音频」，授予录音权限；
6. 点击「开始跨应用风险检测」→ 系统屏幕共享弹窗中允许；
7. 打开腾讯会议等目标软件，右上角悬浮窗/通知显示「存在风险 / 无风险 / 检测受限」及证据；
8. 自测：点「演示画面（自测用）」可显示内置普通/风险画面，用于验证采集链路；
9. 结束后点击悬浮窗「停止」或 App 内「停止检测」。

> 开始检测前必须取得会议参与者同意。受 `FLAG_SECURE`/DRM 保护的画面会显示黑屏，此时悬浮窗显示「检测受限」，不会误报「无风险」。

## 4. 文档索引

- 技术方案与实现对照：`docs/第三级-技术方案与实现对照.md`
- 11 月 18 日检查点进展与证据：`docs/11-18检查点-进展与证据.md`
- 11 月 28 日检查点进展与证据（本轮）：`docs/11-28检查点-进展与证据.md`
- 12 月 8 日测试计划与记录表：`docs/12-8测试计划与记录表.md`
- 服务端联调记录：`evidence/server/e2e-report.md`；加固记录：`evidence/server/hardening/server-hardening-report.md`
- 构建记录：`evidence/build/build-env-notes.md`、`evidence/build-v2/build-summary-v2.txt`
- Android 两轮审查与改动：`evidence/android/review.md`、`evidence/android/hardening-2-report.md`、`evidence/android/hardening-3-lint-fixes.md`
- 待确认事项与下一步：`docs/待确认事项与下一步.md`
- 兼容性表：`docs/兼容性表.md`
- 第四级预研：`docs/第四级-全屏持续检测方案.md`
- 演示脚本：`docs/演示脚本.md`
- 接口文档：`hnu_FinRiskEye/thirdlevel/API.md`
- 部署说明：`hnu_FinRiskEye/deploy/README.md`

## 5. 安全与隐私边界

- 模型 API Key 只在服务端环境变量/.env 中，客户端与仓库均不含真实密钥；
- 截图仅在内存中降采样压缩，不写入客户端文件；服务端仅按请求处理，不做长期留存；
- 音频仅在用户勾选并授权后采集，且不降级为麦克风环境声；
- 测试素材均为合成样例，不含真实身份证、银行卡、验证码、密码或未授权会议内容；
- 若密钥曾以明文出现在聊天/共享文档中，请在交付前到服务商控制台轮换。

## 6. 下一步

| 时间 | 事项 | 负责 |
| --- | --- | --- |
| 即刻 | 平板安装 v2 APK，按 `docs/12-8测试计划与记录表.md` 跑完整测试，记录设备/软件版本与延迟 | A |
| 11/28 | 在腾讯会议中跑通完整链路；第二款视频软件兼容性测试；决定音频是否纳入交付 | A + B |
| 12/8 | 用正常/风险样例完整测试，回填测试记录表，修剩余问题 | A + B |
| 12/15 | 部署/安装/使用/接口说明齐备，演示交付（脚本见 `docs/演示脚本.md`） | A + B |

未完成项与风险见 `docs/待确认事项与下一步.md`。

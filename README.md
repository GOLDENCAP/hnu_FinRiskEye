# hnu_FinRiskEye

湖南大学金融反诈风险识别 Android 应用（HNU FinRiskEye）。第三级「跨应用金融风险检测」实现：
用户主动授权后，通过 `MediaProjection` 采集屏幕画面（可选采集目标应用允许的播放音频），本地 OCR / 画面质量检测后发送到自建风险服务，由服务端调用视觉/文本/语音模型完成金融诈骗风险分析，结果通过悬浮窗与通知提示。

> 一、二级（本地视频风险词识别、WebRTC 视频会议风险识别）的原有功能代码保留在本仓库中；第三级代码独立放在 `thirdlevel/` 下，通过 `app/build.gradle.kts` 的 sourceSets 接入，互不影响。

## 第三级能力清单（2026-11-13 两轮开发 + 2026-11-19 真机验证）

- MediaProjection 全屏采集（2 秒采样）+ 本地 ML Kit 中文 OCR + 画面视觉分析；
- 可选 `AudioPlaybackCapture` 应用播放音频采集（16 kHz 单声道、10 秒切片、WAV），服务端转写后做文本风险分析；
- 风险状态机：存在风险（红）/ 无风险（绿）/ 检测受限（橙），画面与语音分别维护状态；
- 黑屏/受保护画面检测（`FrameQualityDetector`）+ 迟滞（连续 2 帧才提示受限，`FrameLimitedGate`）；
- OCR 看门狗（8s 超时降级为仅图片分析，位图只回收一次）；
- 悬浮窗权限中途撤销检测、屏幕共享授权撤销提示；
- 悬浮窗/通知显示风险等级、证据、原因，以及“最近分析：Xs 前，耗时 Y.Ys｜本次会话：分析 N 次，风险 M 次”；
- 自测/演示画面页（内置普通画面/风险转账页，App 内可进入）；
- **Android 15+ edge-to-edge 适配**（页首内容不再被标题栏/状态栏遮挡；小米 14 / Android 16 真机验证）；
- **悬浮窗按屏幕宽度自适应**（手机/平板通用）；
- **分析前裁剪状态栏/导航栏**（避免系统“屏幕共享中”等提示被误判为风险）；
- 检测页保持屏幕常亮（避免熄屏黑屏导致误显示“检测受限”）；
- 服务端（Node，v1.1.0）：文本/画面/语音三接口、Bearer 认证、request_id 幂等、限流（429）、结构化日志、优雅关闭、幂等缓存清理；
- 部署化：Dockerfile、docker-compose、nginx HTTPS 示例、Windows 启动脚本（见 `deploy/`）。

## 目录

```text
app/                      Android 主模块（一、二级功能 + 第三级入口）
thirdlevel/
  ├─ android/             第三级 Android 源码/资源/测试（java/res/test）
  ├─ server/              第三级 Node 风险服务（含 42 项测试）
  ├─ test-assets/         测试图片与语音素材
  ├─ docs/2026-11-13/     交付/验证文档（技术方案、检查点、测试计划、证据摘要）
  ├─ API.md               第三级接口文档（含 429 限流与 /health 字段）
  └─ 第三级测试与交付说明-2026-11-08.md
deploy/                   Docker / nginx / Windows 启动脚本 / 部署说明
gradle/                   Gradle 9.3.1 wrapper 与版本清单
build-debug.ps1           一键构建脚本（使用 ../dev-env 便携环境）
logcat.ps1                真机日志查看脚本（使用 ../dev-env 里的 adb）
```

## 构建

### 方式一：Android Studio（推荐给组内同学）

1. 用 Android Studio 打开本工程根目录；
2. Gradle 同步（Gradle 9.3.1 / AGP 9.1.0 / compileSdk 36 / 需要 JDK 21，`gradle/gradle-daemon-jvm.properties` 已声明）；
3. 同步时按 `settings.gradle.kts` 使用阿里云镜像下载依赖；
4. 构建 `assembleDebug`。工程含 NDK 原生部分（FFmpeg/OpenCV/RTSP），按提示安装 **NDK 28.2.13676358** 与 **CMake 3.22.1**。

### 方式二：命令行 + 便携环境（Windows，无需 Android Studio）

```powershell
# 在工程上一级目录放好 dev-env（见交付包 tools\setup-dev-env.ps1），或手动设置 JDK21 + Android SDK
.\build-debug.ps1
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

> 在本机沙箱/受限环境下请用交付包的 `tools\build-debug-env.ps1`（把 ANDROID_USER_HOME/GRADLE_USER_HOME/TEMP 重定向到工作区）：
> `tools\build-debug-env.ps1 -ProjectPath <工程> -Task assembleDebug`，`-Task` 也支持 `testDebugUnitTest` / `lintDebug`。
> `local.properties`、`gradle.properties` 不入库（可能包含本机 SDK 路径/密钥）；`gradle.properties.example` 给出可参考的公共配置。

## 运行第三级风险服务

```powershell
cd thirdlevel\server
Copy-Item .env.example .env       # 首次：填写 AI_API_KEY（硅基流动等 OpenAI 兼容服务）与 RISK_API_TOKEN
npm start                         # 默认 0.0.0.0:3100；GET /health 返回 version=1.1.0
npm test                          # 42 项单测（不调用真实模型）
```

- 模型 API Key 只放在服务端 `.env`（已 gitignore），**不要写进 APK、`gradle.properties` 或提交到仓库**；
- 平板与电脑需在同一局域网，Windows 防火墙放行 TCP 3100；
- 默认模型：文本 `deepseek-ai/DeepSeek-V3.2`、视觉 `Qwen/Qwen3-VL-8B-Instruct`、语音转写 `FunAudioLLM/SenseVoiceSmall`（可在 `.env` 替换）；
- 限流默认 60 次/分钟（`RATE_LIMIT_PER_MINUTE`，0=关闭）；部署见 `deploy/README.md`；
- 接口与字段约定见 `thirdlevel/API.md`。

## 使用（真机）

1. 安装 `app-debug.apk`；
2. 打开 App → 主界面点击「第三级：跨应用风险检测」；
3. 填写风险服务地址（如 `http://192.168.x.x:3100`）和测试令牌；
4. 授予悬浮窗权限（显示在其他应用上层）；
5. 可选勾选「同时采集应用播放音频」并授予录音权限（Android 10+，目标应用允许采集时生效）；
6. 点击「开始跨应用风险检测」→ 系统屏幕共享授权；
7. 打开腾讯会议等目标软件，右上角悬浮窗/通知显示「存在风险 / 无风险 / 检测受限」及证据；
8. 自测：点「演示画面（自测用）」显示内置测试画面，验证采集与分析链路；
9. 悬浮窗「停止」或 App 内停止。

> 开始检测前必须取得会议参与者同意。

## 测试与验证状态

| 项 | 结果 |
| --- | --- |
| Gradle 单元测试 | 40/40 通过（FrameQualityDetector 11、FrameLimitedGate 11、AnalysisStatsFormatter 13、TextDeduplicator 4、ExampleUnitTest 1） |
| Android Lint | 0 error / 415 warning |
| 服务端 `npm test` | 42/42 通过（含限流/health/日志/幂等清理/优雅关闭） |
| 真实模型 E2E | 文本/画面/语音真实调用通过（设备/模拟器端到端仍待真机复验） |
| 真机验证 | 待同学在平板按测试计划执行（腾讯会议画面/音频、FLAG_SECURE 黑屏、延迟记录） |

## 已知限制

- `FLAG_SECURE`、DRM、厂商录屏限制的页面会采集到黑屏；客户端显示「检测受限」而不是「无风险」；
- 应用音频采集依赖 `AudioPlaybackCapture`，目标应用（如腾讯会议）可能禁止采集，此时显示「语音采集不可用」，不会回退录麦克风；
- 横竖屏切换后建议停止并重新开始检测；
- 视觉模型对模糊/白板手写内容可能存在结果波动，客户端有「连续两次无风险才清除」兜底；
- 当前服务为局域网 HTTP + 测试令牌，正式部署需 HTTPS、随机令牌与访问控制；
- Android 客户端暂不识别 429 的友好提示（会显示分析失败，不会误报无风险）。

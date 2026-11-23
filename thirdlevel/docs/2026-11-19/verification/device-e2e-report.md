# 真机端到端验证与 UI 修复记录（小米 14 / Android 16）

- 日期：2026-11-19
- 设备：Xiaomi 14（型号 `23127PN0CC`），Android 16（SDK 36），HyperOS `OS3.0.307.0.WNCCNXM`
- 连接方式：USB-C（拓展坞）连接电脑，`adb` 调试；服务端通过 `adb reverse tcp:3100 tcp:3100` 以 `http://127.0.0.1:3100` 访问本机风险服务（无需同一 Wi-Fi）
- 被测 APK：`app-debug.apk` versionCode=6 / versionName=1.5（最终修复版），SHA-256 `33C8D50F45A938781F82EBB60F30D4F82032770F83DFFB128E6E98436457145D`
- 服务端：thirdlevel-risk-service v1.1.0，本机 3100 端口，模型为真实硅基流动 API

## 1. 用户反馈的问题与修复

### 问题 1：页面最上一行（Room Name 上面的 Server Address 输入框）被标题栏遮挡

原因：工程 `targetSdk = 36`，Android 15/16 系统强制 **edge-to-edge**（内容绘制到状态栏/导航栏后面），
而工程使用的 `Theme.MaterialComponents.DayNight.DarkActionBar` + 旧版 AppCompat 不会自动为内容补
系统栏 padding，`MainActivity` 的 ActionBar（“FinRiskEye 风险检测”）与第一个输入框重叠。

修复：
- 新增 `EdgeToEdgeInsets` 工具：Android 15+ 时对根布局追加 `systemBars` padding（Android 15 以下保持默认行为，不影响平板 Android 12）；
- `MainActivity`、`ThirdLevelActivity`、`ThirdLevelDemoActivity` 改用 `Theme.Chapter07.NoActionBar` 主题，页面内自绘标题；
- `MainActivity` 顶部新增“FinRiskEye 风险检测”标题；`ThirdLevelActivity` 使用原有页内标题。

对比证据：

| 修复前（v1.0） | 修复后（v1.1+） |
| --- | --- |
| `before-fix-main.png`：Server Address 输入框被紫色标题栏遮挡，只剩底边可见 | `after-fix-main.png`：标题、Server Address、Room Name、按钮全部完整可见 |
| `after-fix-thirdlevel.png`：第三级页面标题/输入框/按钮完整可见（同主题修复） | `fin-thirdlevel-page.png`：v1.5 页面（含演示画面入口） |

### 问题 2（真机测试中发现）：悬浮窗在手机上过宽

原悬浮窗布局固定 `420dp` 宽，在 400dp 宽的手机屏幕上会超出屏幕被裁切。修复：`RiskOverlayController`
按“`min(420dp, 屏幕宽度 - 32dp)`”自适应，手机上实测 `overlay shown, width=1104px`（屏幕 1200px，留 48px 边距），平板上仍是 420dp。

### 问题 3（真机测试中发现）：启动瞬间把系统“屏幕共享”授权弹窗当成风险内容

授权弹窗刚消失的一帧仍可能包含系统提示文字（含“密码/泄露”等），视觉模型会误判为高风险。修复：检测会话启动后
首个分析延迟一个采样周期（约 2 秒），避开对话框退场动画。

### 问题 4（真机测试中发现）：状态栏“屏幕共享中”等系统文案被误判为风险

Android 授权的屏幕共享会在状态栏常驻“屏幕共享中”提示，OCR/视觉模型会把它当成“要求共享屏幕”的高风险行为。
修复：`ScreenRiskService.imageToBitmap` 在分析前裁掉状态栏与底部导航栏（系统栏高度取系统资源），只分析目标应用内容区域。

### 问题 5（真机测试中发现）：检测页面会因熄屏导致黑屏采集

检测页新增 `FLAG_KEEP_SCREEN_ON`，避免演示时自动熄屏；同时保留“黑屏/受保护画面 → 检测受限”的正确提示（不会误报“无风险”）。

## 2. 真机端到端验证结果（v1.5，2026-11-19 11:52–11:54）

流程：安装 APK → 进入第三级 → 服务地址/令牌（预置） → 授予悬浮窗/通知权限 → 开始检测 → 系统屏幕共享（共享整个屏幕）→ 打开内置「演示画面（自测用）」→ 切“风险转账页”/“普通画面” → 停止。

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| 开始检测 | 悬浮窗出现（`width=1104px`），状态“检测中”，首次分析为「无风险」（未再误报系统弹窗） | `logcat-v15-final.txt` |
| 风险转账页 | 约 3–4 秒内出现「存在风险」：`风险等级：高风险`，证据“备注: 洗钱案件资金核直教,请立即转入安全户；客服提示: 不要告诉任何人,转完发截图”，原因“要求向‘安全账户’转账并索要保密，符合典型诈骗特征”；单次分析耗时 1.5s | `fin-demo-risk.png`（悬浮窗为 FLAG_SECURE，截图中显示为黑色区域；悬浮窗文字见下方 logcat）、`logcat-v15-final.txt` |
| 会话统计 | 悬浮窗/状态同步显示“最近分析：刚刚，耗时 1.5s｜本次会话：分析 3 次，风险 1 次” | `logcat-v15-final.txt` |
| 切普通画面 | 约 4–6 秒后显示「无风险」，统计更新为“分析 5 次，风险 1 次” | `logcat-v15-final.txt` |
| 停止检测 | 悬浮窗/通知移除，服务日志“检测已停止” | `fin-stopped.png`、`logcat-v15-final.txt` |
| 黑屏/熄屏场景 | 测试中屏幕短暂熄灭时，应用正确显示「检测受限 / 黑屏或受保护画面」并在画面恢复后自动回到「无风险」（迟滞：单帧黑屏被忽略） | `logcat-v15-final.txt` 中 `single limited frame ignored by hysteresis: BLACK`、`frame quality limited: BLACK` |

关键 logcat（原文摘录）：

```text
I ThirdLevelRisk: screen risk session started, 1200x2670
I ThirdLevelRisk: overlay shown, width=1104px
I ThirdLevelRisk: overlay update: 无风险 | 画面：未发现风险 最近分析：刚刚，耗时 2.2s｜本次会话：分析 1 次，风险 0 次
D ThirdLevelRisk: single limited frame ignored by hysteresis: BLACK
W ThirdLevelRisk: frame quality limited: BLACK
I ThirdLevelRisk: overlay update: 检测受限 | 画面：检测受限 画面：黑屏或受保护画面（可能被目标应用限制）...
I ThirdLevelRisk: screen risk result: high, latencyMs=1535
I ThirdLevelRisk: overlay update: 存在风险 | 风险等级：高风险  画面分析： 备注: 洗钱案件资金核直教,请立即转入安全户 客服提示: 不要告诉任何人,转完发截图 ...
I ThirdLevelRisk: overlay update: 无风险 | 画面：未发现风险 最近分析：刚刚，耗时 1.4s｜本次会话：分析 5 次，风险 1 次
I ThirdLevelRisk: screen risk session stopped: 检测已停止
```

服务端对应日志（同一时段）：`POST /api/v1/risk/screen` 返回 `risk_level=high` 与 `risk_level=none`，`duration_ms` 约 1.2–2.2s，见 `server-v11.log`。

## 3. 说明与已知限制

- 悬浮窗使用 `FLAG_SECURE`（防止把风险提示再次截屏分析），因此**截图/录屏中该区域是黑色**；悬浮窗实际文字通过 logcat 的 `overlay update:` 行取证（本版本新增）。
- 应用播放音频链路已在真机验证通过（见第 5 节）；但 MIUI 会对**后台播放**做音频硬化（`AudioHardening background playback would be muted`），目标软件需要保持前台播放才能被稳定捕获（正常会议使用场景即前台）。
- 受 `FLAG_SECURE`/DRM 保护的第三方页面（如银行 App 支付页）会显示黑屏并进入「检测受限」，本轮新增内置“受保护画面（自测）”页用于验证（v1.6）。
- 一、二级遗留演示页在 Android 15+ 上的 ActionBar 遮挡问题正在通过全局 `FinRiskEyeApplication` 兜底修复（v1.6）。
- 测试使用内置合成画面/合成语音（不含真实会议内容）；真机测试前后屏幕会保持常亮/已解锁，测试完成后已恢复屏幕常亮设置。

## 4. 证据文件索引

| 文件 | 说明 |
| --- | --- |
| `before-fix-main.png` | 修复前：标题栏遮挡 Server Address（用户反馈问题） |
| `after-fix-main.png` | 修复后：主页完整显示 |
| `after-fix-thirdlevel.png` / `fin-thirdlevel-page.png` | 第三级页面显示正常 |
| `fin-demo-risk.png` | 风险转账页（悬浮窗为 FLAG_SECURE 黑色区域） |
| `fin-stopped.png` | 停止检测后的界面 |
| `logcat-v15-final.txt` | v1.5 最终真机 logcat（悬浮窗文字、风险结果、延迟统计） |
| `logcat-device-e2e.txt` | v1.2 首轮真机 E2E logcat（完整风险/正常/停止流程） |
| `device-info.txt` | 设备型号/系统版本 |
| `third_level_settings.xml` | 测试用服务地址/令牌配置（预置方式，便于自动化） |
| `audio/logcat-audio-e2e.txt`、`audio/server-audio-requests.txt` | 应用播放音频链路真机验证 logcat / 服务端请求记录 |
| `audio/web/play.html`、`audio/web/risk_voice.wav` | 音频测试用 HTML5 播放页与 WAV 素材（通过 localhost 提供，手机 adb reverse 访问） |

## 5. 应用播放音频链路真机验证（补充，2026-11-19）

- 目的：验证 `AudioPlaybackCapture` 在真机上能采集应用播放音频，并跑通服务端 ASR + 风险分析。
- 方法：电脑起本地 HTTP 服务（8001）提供 `play.html` + `04_风险语音_冒充公安.wav`；手机 `adb reverse tcp:8001 tcp:8001` 后用内置浏览器打开 `http://127.0.0.1:8001/play.html` 播放；第三级页面勾选“同时采集应用播放音频”，开始检测并共享整个屏幕；浏览器保持前台播放。
- 结果（logcat 原文）：

```text
I ThirdLevelAudio: application playback capture started
I ThirdLevelRisk: overlay update: 检测中 | 画面：等待分析 语音：等待采集
I ThirdLevelRisk: audio risk result: high, latencyMs=17942
I ThirdLevelRisk: overlay update: 存在风险 | 风险等级：高风险  画面：未发现风险  语音分析： 你好，我是公安局的，你的银行卡涉嫌。 冒充公安机关，声称银行卡涉嫌问题，是典型诈骗开场  最近分析：刚刚，耗时 17.9s｜本次会话：分析 4 次，风险 1 次
I ThirdLevelRisk: audio risk result: high, latencyMs=9641
I ThirdLevelRisk: overlay update: 存在风险 | 风险等级：高风险  画面：未发现风险  语音分析： 请把钱转到我们的安全账户。你好，我是公安局的，你的银行卡涉嫌洗钱，请把钱转。 冒充公安机关，要求转账到安全账户  最近分析：刚刚，耗时 9.6s｜本次会话：分析 5 次，风险 2 次
```

- 服务端：第一次 audio 请求遇到上游 ASR 瞬时失败返回 503（5.9s），客户端按约定**沿用同一 request_id 自动重试**，重试返回 HTTP 200 / `risk_level=high`（11.9s）；随后第二个音频片段也返回 high。见 `audio/server-audio-requests.txt`。
- 结论：`AudioPlaybackCapture → WAV 分片 → 服务端 ASR → 风险分析 → 悬浮窗「存在风险」` 链路在真机可用；音频风险提示与画面风险提示可同时显示。


## 6. v1.6 设备修复验证（FLAG_SECURE 黑屏 + 遗留页面 edge-to-edge）

- 测试 APK：v1.6（versionCode 7 / versionName 1.6），SHA-256 `B81DC9F56A950FC2085EE38E0CDECF37CB421E0D01A45D0E5C5312498EB98E5F`
- 新增能力（详见 `evidence/android/device-fixes-2026-11-19.md`）：
  - `ThirdLevelProtectedDemoActivity`：FLAG_SECURE 自测页（演示页 →“受保护画面（自测）”进入）；
  - `FinRiskEyeApplication`：API>=35 时给所有未适配的遗留 Activity 统一下移 systemBars（旧平板 Android 12 不受影响）；
  - 悬浮窗权限检查放宽为“连续 3 次（约 6s）失败才停止”，避免 MIUI 偶发误判；
  - 演示页新增“打开遗留页面（测试）”入口（getposition.get）。

### 6.1 FLAG_SECURE 黑屏检测（通过）

操作：开始检测（共享整个屏幕）→ 演示画面（自测用）→“受保护画面（自测）”→ 等待约 6 秒。

真机 logcat 原文：

`	ext
D ThirdLevelRisk: single limited frame ignored by hysteresis: BLACK
W ThirdLevelRisk: frame quality limited: BLACK
I ThirdLevelRisk: overlay update: 检测受限 | 画面：检测受限 语音：未捕获到应用播放音频 画面：黑屏或受保护画面（可能被目标应用限制） 最近分析：4s 前，耗时 1.1s｜本次会话：分析 2 次，风险 0 次
`

结论：受保护画面（FLAG_SECURE）被 MediaProjection 采集为黑屏，应用显示橙色「检测受限 / 黑屏或受保护画面（可能被目标应用限制）」，**没有误报「无风险」**；离开该页面后自动恢复「无风险」。截图为黑屏（FLAG_SECURE 系统限制，预期），证据以 logcat 为准：`logcat-v16-devicefix-filtered.txt`。

### 6.2 遗留页面全局 edge-to-edge 兜底（通过）

操作：演示画面（自测用）→“打开遗留页面（测试）”→ getposition.get（一、二级遗留 DarkActionBar 页面）。

结果：页面内容（“点击屏幕任意位置 / 坐标将被记录”、记录数量、按钮）完整显示在状态栏下方，ActionBar/首行内容不再被状态栏遮挡（`z-legacy-clear.png`）。

### 6.3 悬浮窗权限检查放宽（已实现，待自然场景复测）

MIUI 上 `Settings.canDrawOverlays` 偶发返回 false（真机出现过一次误停），v1.6 改为连续 3 次失败才停止，1-2 次仅在详情提示，并支持恢复后自动清除；本轮通过真机运行确认不再因单次误判停服务。

## 7. 证据文件索引补充

| 文件 | 说明 |
| --- | --- |
| `logcat-v16-devicefix-filtered.txt` | v1.6 真机 logcat（FLAG_SECURE 检测受限、恢复、会话统计） |
| `z-legacy-clear.png` | 遗留页面 getposition.get 完整显示（全局 insets 兜底） |
| `z-protected.png` | 受保护画面页截图（FLAG_SECURE → 系统截图黑屏，预期） |
| `audio/logcat-audio-e2e.txt`、`audio/server-audio-requests.txt` | 应用播放音频链路真机验证证据 |

## 8. 第三方 App 兼容性抽测与 v1.7 悬浮窗触摸修复

### 8.1 哔哩哔哩 9.17.0（第二款视频软件）

- 画面采集：首页与竖屏视频界面均可正常采集（检测持续运行，未出现「检测受限」）；OCR 可读视频标题/字幕（文本长度约 188 字符）；视觉分析返回 none（正常内容不误报）。
- 应用音频：视频播放音频被 AudioPlaybackCapture 采集并完成 ASR+风险分析（结果 none，符合正常视频预期）。
- 证据：`compat-bili-home.png`、`compat-bili-video2.png`、`logcat-compat-bilibili-wemeet.txt`。

### 8.2 腾讯会议 3.41.10.443（目标软件手机侧复核）

- 画面采集：首页、快速会议设置页、会议中界面均可采集（检测运行中 OCR 有输出、分析返回 none，无误报）。
- 应用音频：快速会议只有本机、无远端发言，未捕获到可分析音频（`语音：未捕获到应用播放音频`）；会议语音是否可采集仍需**真实远端真人发言**复测（关键未知项）。
- 证据：`v17-wemeet-inmeeting.png`、`v17-quickmeet.xml`、`logcat-compat-bilibili-wemeet.txt`。

### 8.3 v1.7 悬浮窗触摸与手机适配

- 发现：在手机上检测运行时，悬浮窗（含 FLAG_SECURE 的窗口区域）会拦截其覆盖区域的点击，导致无法操作下方目标 App（实测点击 Bilibili 视频卡片/腾讯会议“快速会议”被悬浮窗吃掉）。
- 修复（v1.7）：屏幕宽度 <600dp 时，悬浮窗收窄为 300dp、改为 `FLAG_NOT_TOUCHABLE` 并隐藏窗内停止按钮；停止检测使用**通知里的“停止”动作**或 App 内按钮（平板上保持原可点击悬浮窗）。
- 验证：修复后检测运行中可正常点击腾讯会议“快速会议”→“进入会议”，会议中界面采集/OCR/分析正常；通过通知“停止”/结束屏幕共享后，应用日志 `screen risk session stopped: 屏幕共享授权已撤销，请重新授权后开启检测`，状态明确。
- 证据：`logcat-compat-bilibili-wemeet.txt`、`v17-wemeet-inmeeting.png`、`v17-notif.xml`。

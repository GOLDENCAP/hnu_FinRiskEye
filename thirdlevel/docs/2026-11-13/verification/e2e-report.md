# HNU FinRiskEye 第三级风险服务 E2E 联调报告（真实模型）

- 生成时间：2026-11-13T20:53:00+08:00
- 生成脚本：`finriskeye/tools/test-risk-api.ps1`（版本 1.0.0）
- 验证对象：同学 B 交付的第三级 Node 风险服务 + 硅基流动（SiliconFlow）真实 API
- 服务地址：`http://127.0.0.1:3100`（本轮在开发机本机验证；Android 联调改用局域网 IP）
- 证据文件：`finriskeye\evidence\server\e2e-results.json`、`responses\*.json`、`server.log`、`npm-test.log`
- **本轮结论：PASS**（功能用例 13 项通过 13 项、失败 0 项；另有说明项 1 项、注意事项 1 条，见 §3）

## 1. 环境与运行方式

| 项目 | 值 |
| --- | --- |
| 工作区 | `C:\Users\79047\Documents\deepseek-harness\default-workspace` |
| 服务目录 | `C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye\thirdlevel\server` |
| 端口 / Host | 3100 / 0.0.0.0（.env 配置） |
| Node / Python | v24.14.1 / Python 3.9.13 |
| 服务进程 PID | 50172 |
| 本轮是否由脚本启动服务 | False |
| 单测（npm test） | 11 通过 / 0 失败，共 11 项 |
| 稳定性重复次数 | 每个风险素材 3 次 |

服务端默认模型（由 `.env` / `.env.example` 配置，Key 只保存在服务端）：

- 文本：`deepseek-ai/DeepSeek-V3.2`
- 画面：`Qwen/Qwen3-VL-8B-Instruct`
- 语音转写：`FunAudioLLM/SenseVoiceSmall`

启动 / 停止命令（在服务目录执行；`.env` 已被 gitignore，不能提交）：

```powershell
cd "C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye\thirdlevel\server"
node --env-file=.env server.js
# 健康检查
curl.exe http://127.0.0.1:3100/health
# 停止（PID 见上表或 evidence\server\server.pid）
Stop-Process -Id 50172
```

单测：`cd thirdlevel\server; npm test`（11 项，不调用真实模型；日志见 `npm-test.log`）。

## 2. E2E 用例总表

| 编号 | 用例 | 输入摘要 | 期望 | HTTP | risk_level | evidence / reason 摘要 | 耗时(ms) | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| health | 健康检查 | 无请求体、无令牌 | HTTP 200；ok=true | 200 | - |  /  | 37 | 通过 |
| auth_missing | 缺少令牌 -> 401 | 诈骗文本请求，不带 Authorization 头 | HTTP 401；UNAUTHORIZED | 401 | - | error_code=UNAUTHORIZED；认证失败 | 52 | 通过 |
| auth_wrong | 错误令牌 -> 401 | 诈骗文本请求 + Bearer wrong-token-fo… | HTTP 401；UNAUTHORIZED | 401 | - | error_code=UNAUTHORIZED；认证失败 | 40 | 通过 |
| text_fraud | 文本-诈骗样例 | 冒充公安 + 涉嫌洗钱 + 转安全账户（39 字） | HTTP 200；risk_level=high | 200 | high | 我是公安局的，你的银行卡涉嫌洗钱，请把钱转到安全账户，不要告… / 冒充公安要求转账到安全账户、要求保密并催促操作 | 4127 | 通过 |
| text_normal | 文本-正常会议 | 项目进度会议安排（40 字） | HTTP 200；risk_level=none/l… | 200 | none |  / 内容为常规工作安排，无金融诈骗相关特征 | 2408 | 通过 |
| screen_risk | 画面-风险转账页 | 02_风险画面.png -> JPEG 640x480/28… | HTTP 200；risk_level=medium… | 200 | high | 收款人：安全账户（资金核查专户） 转账金额：¥50,000.… / 要求向陌生账户转账，且备注为“洗钱案件资金核查”，并要求保密… | 3965 | 通过 |
| screen_normal | 画面-普通会议页 | 01_普通画面.png -> JPEG 1200x800/3… | HTTP 200；risk_level=none/l… | 200 | none |  / 屏幕显示为普通会议内容，无任何诈骗风险特征 | 1215 | 通过 |
| audio_risk | 语音-冒充公安 | 04_风险语音_冒充公安.wav 切 10 秒/16kHz/… | HTTP 200；risk_level=high；t… | 200 | high | 我是公安局的，你的银行卡涉嫌洗钱，请把钱转到我们的安全账户 / 冒充公安并要求转账到安全账户 / 转写：你好，我是公安局的，你的银行卡涉嫌洗钱，请把钱转到我们的安全… | 6089 | 通过 |
| audio_normal | 语音-项目安排 | 03_普通语音_项目安排.wav 切 10 秒/16kHz/… | HTTP 200；risk_level=none/l… | 200 | none |  / 内容为常规工作会议安排，未涉及任何金融诈骗风险要素 / 转写：今天的会议议程是项目进度汇报和下周工作安排，请大家按计划完成… | 4199 | 通过 |
| audio_long_full | 语音-长音频说明项 | 04_风险语音_冒充公安.wav 原始长音频（未切片） | 说明项（不计入通过/失败）：记录长音频超过 12s … | 200 | high |  /  / 转写：你好，我是公安局的，你的银行卡涉嫌洗钱，请把钱转到我们的安全… | 4377 | 说明项（不计入） |
| idem_repeat_1 | 幂等-首次请求 | 正常文本 + 固定 request_id（首次） | HTTP 200；完成真实分析 | 200 | none |  /  | 2405 | 通过 |
| idem_repeat_2 | 幂等-重复请求（缓存） | 相同 request_id + 相同内容（第二次） | HTTP 200；risk_level 与 anal… | 200 | none |  /  | 37 | 通过 |
| idem_conflict_prepare | 幂等-冲突前置请求 | 内容 A + 固定 request_id | HTTP 200；记录 request_id 与内容… | 200 | none |  /  | 2981 | 通过 |
| idem_conflict | 幂等-同 ID 不同内容 -> 40… | 相同 request_id + 不同内容 B | HTTP 409；REQUEST_ID_CONFLI… | 409 | - | error_code=REQUEST_ID_CONFLICT；request_id 已被不同请求内容使用 | 37 | 通过 |

## 3. 稳定性复测（同一素材重复调用，观察风险等级是否波动）

语音稳定性复测使用由原始素材切出的 **约 10 秒 / 16 kHz / 单声道 WAV 片段**（`derived\04_risk_10s_16k_mono.wav`），与 Android 客户端实际发送格式一致；原始长音频（51.67 秒 / 22050 Hz）单独作为说明项测试，避免长音频超时干扰等级统计。

| 素材 | 接口 | 各次结果（HTTP / risk_level / 耗时） | 是否稳定 | 是否落在期望区间 |
| --- | --- | --- | --- | --- |
| 文本-诈骗样例 | /api/v1/risk/text | HTTP 200 / high / 4314ms → HTTP 200 / high / 4210ms → HTTP 200 / high / 3848ms | 稳定 | 符合 |
| 画面-风险画面(02) | /api/v1/risk/screen | HTTP 200 / high / 5360ms → HTTP 200 / high / 6432ms → HTTP 200 / high / 3976ms | 稳定 | 符合 |
| 语音-风险语音(04) 10s 切片 | /api/v1/risk/audio | HTTP 200 / high / 9170ms → HTTP 200 / high / 6512ms → HTTP 200 / high / 5036ms | 稳定 | 符合 |

### 3.1 长音频说明项（不计入通过/失败）

- 输入：04_风险语音_冒充公安.wav 原始长音频（未切片）；返回 HTTP 200，耗时 4377ms。
- 结论：原始长音频（51670ms/22050Hz/2278690 字节，未切片）返回 HTTP 200：本次上游在超时前返回（risk_level=high，耗时 4377ms），但长音频延迟明显高于 10 秒切片，且存在触发 12 秒 ASR 超时的风险；Android 客户端仍应按 10 秒切片发送。

### 3.2 注意事项（真实模型波动/说明项，不计为功能失败）

1. 说明项「语音-长音频说明项」：原始长音频（51670ms/22050Hz/2278690 字节，未切片）返回 HTTP 200：本次上游在超时前返回（risk_level=high，耗时 4377ms），但长音频延迟明显高于 10 秒切片，且存在触发 12 秒 ASR 超时的风险；Android 客户端仍应按 10 秒切片发送。

## 4. 认证与幂等行为

| 检查项 | 结果 |
| --- | --- |
| 缺少令牌 -> 401 | 通过（HTTP 401） |
| 错误令牌 -> 401 | 通过（HTTP 401） |
| 幂等-首次请求 | 通过（HTTP 200） |
| 幂等-重复请求（缓存） | 通过（HTTP 200） |
| 幂等-冲突前置请求 | 通过（HTTP 200） |
| 幂等-同 ID 不同内容 -> 409 | 通过（HTTP 409） |

## 5. 11 月 18 日检查点结论

**总体结论：PASS** —— 功能用例 13 / 13 通过；三条链路均真实调用硅基流动模型（未使用模拟响应），接口契约、认证和幂等行为符合 `API.md`。

| 链路 | 检查点表述 | 实测数据 |
| --- | --- | --- |
| 文本 /api/v1/risk/text | **PASS**：诈骗文本返回 high，正常会议文本返回 none，evidence / reason 非空 | 延迟 3268ms（2408~4127ms） |
| 画面 /api/v1/risk/screen | **PASS**：风险转账画面返回 high（期望 medium/high 区间），普通会议画面返回 none，evidence / reason 非空 | 延迟 2590ms（1215~3965ms） |
| 语音 /api/v1/risk/audio | **PASS**：10 秒 / 16kHz / 单声道切片转写含“公安局/洗钱/安全账户”，返回 high；普通语音返回 none 且转写非空 | 延迟 5144ms（4199~6089ms） |

注意项（不影响功能结论，检查会上按以下口径说明）：

1. **模型延迟/等级存在正常波动**：同一素材重复调用等级总体稳定（见 §3），但视觉模型单次延迟可在约 1~13 秒波动；客户端已有“连续两次无风险才清除风险”的兜底。
2. **长音频超时**：原始 51.67 秒 / 22050 Hz 语音整段上传时，服务端 ASR 超时 12 秒可能返回 504；Android 客户端实际按约 10 秒 / 16kHz / 单声道切片发送，不会整段上传，因此不影响检查点演示。
3. **网络范围**：本轮只验证 `127.0.0.1:3100`；检查环境用平板访问时改用局域网 IP，并确认防火墙放行 TCP 3100（见 §6）。

演示建议顺序：/health → 诈骗文本 high → 正常文本 none → 风险画面 high → 正常画面 none → 风险语音 high（transcript 可见）→ 幂等缓存/409。

## 6. 给 Android 侧（同学 A）的联调注意事项

1. **服务地址**：`http://<电脑局域网IP>:3100`（本机检测到局域网 IP：172.28.176.1、192.168.5.39）。平板与电脑必须在同一局域网；先用浏览器访问 `http://<电脑局域网IP>:3100/health` 确认连通。
2. **令牌**：请求头 `Authorization: Bearer local-thirdlevel-dev`，与服务端 `.env` 的 `RISK_API_TOKEN` 保持一致；令牌只用于测试，正式部署应更换随机令牌。
3. **超时**：服务端上游超时配置为文本 12 秒、画面 13 秒（本轮由 10 秒放宽到 13 秒以吸收视觉延迟波动，仍低于客户端 15 秒）、语音转写 12 秒，超时统一返回 504。客户端默认 15 秒超时足够，语音读取超时建议保持 30 秒；5xx 时沿用同一 `request_id` 重试一次。
4. **画面**：只接受 JPEG（服务端校验文件头 FF D8 FF）；解码后 ≤6MB、请求体 ≤8MB。当前 1600 长边 + JPEG 质量 65 的降采样参数合适，本机测试样例压缩后仅约 20~30KB。
5. **语音**：PCM 16 位 WAV，单个文件 ≤4MB。本次按客户端真实格式（约 10 秒 / 16kHz / 单声道切片）验证通过；原始 51.67s / 22050Hz 长音频整段上传可能超过服务端 12 秒 ASR 超时返回 504，客户端按 10 秒切片发送不受影响；无语音返回 422 `TRANSCRIPTION_EMPTY`。
6. **幂等重试**：网络失败或 5xx 重试时必须沿用原 `request_id`；同 ID 不同内容会返回 409，不要自动重试 400/401/409。
7. **局域网/防火墙**：服务监听 0.0.0.0:3100。若平板访问不通，在 Windows 管理员 PowerShell 放行 TCP 3100 入站，例如：

   ```powershell
   New-NetFirewallRule -DisplayName "FinRiskEye 3100" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3100 -Profile Private,Domain
   ```

8. **HTTP 明文**：主工程 `AndroidManifest.xml` 已设置 `usesCleartextTraffic="true"`，局域网 HTTP 可直接访问；正式部署建议改 HTTPS。
9. **密钥安全**：APK 不得内置硅基流动 Key；Key 只在服务端 `.env`。

## 7. 费用与调用量注意点

- 三条链路都按硅基流动实际 token / 音频时长计费，本轮 E2E 共发起 18 次真实模型请求（含稳定性复测），端到端总耗时约 84.6 秒。
- 文本：每次约几百 token，成本很低；画面：单张低细节图 + 约 300 token 输出；语音：SenseVoice 按音频时长计费。
- Android 默认 2 秒采样且画面变化才请求，正常演示几分钟的调用量很小；演示风险语音时避免循环播放长音频。
- 具体单价以硅基流动控制台账单为准；建议演示前在控制台确认余额。
- 服务端未配置缓存之外的限流，公开演示前建议加请求频率限制。

## 8. 复现步骤

```powershell
cd "C:\Users\79047\Documents\deepseek-harness\default-workspace"
powershell -NoProfile -ExecutionPolicy Bypass -File finriskeye\tools\test-risk-api.ps1 -AutoStart
# 只重新生成报告（不发起模型调用）
powershell -NoProfile -ExecutionPolicy Bypass -File finriskeye\tools\test-risk-api.ps1 -ReportOnly
```

脚本退出码：0=全部通过；1=有用例失败；2=环境/素材准备失败；3=服务不可达。

## 9. 未解决项与风险

- 本轮只验证 `127.0.0.1:3100`；平板跨机访问需在检查环境用局域网 IP 再验证一次（防火墙/AP 隔离）。
- 视觉模型对模糊、白板手写、动画画面仍可能波动；客户端已有“连续两次无风险才清除”的兜底。
- 语音长音频延迟波动明显：整段上传 51.67 秒素材可能超过 12 秒 ASR 超时返回 504；客户端必须坚持约 10 秒 / 16kHz / 单声道切片。
- 本轮把服务端 `VISION_TIMEOUT_MS` 由 10000 调整为 13000（仍在客户端 15 秒超时内），用于吸收视觉模型延迟波动；该值记录在 `server\.env`，未改服务端代码。
- 本轮语音使用预置 WAV 文件，未验证腾讯会议 `AudioPlaybackCapture` 实采；该能力必须在目标平板实测。
- 服务端幂等缓存是单实例内存缓存，重启后丢失；仅适合本机/单实例测试。
- 测试服务为局域网 HTTP + 固定测试令牌，不能直接用于正式部署。

---

本报告由 `test-risk-api.ps1` 自动生成；所有请求均真实调用硅基流动模型，未使用模拟响应。

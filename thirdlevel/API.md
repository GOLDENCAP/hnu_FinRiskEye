# Third-Level Text Risk API

版本：`v1`（接口契约不变）
服务：`thirdlevel/server`
服务版本：`1.1.0`（可通过 `/health` 的 `version` 字段读取；新增字段只做增量）
适用范围：第三级屏幕 OCR 文字风险分析

## 1. 健康检查

```http
GET /health
```

无需认证、不参与限流。

```json
{
  "ok": true,
  "service": "thirdlevel-risk-service",
  "version": "1.1.0",
  "model_configured": true,
  "uptime_seconds": 3600
}
```

- `ok`、`service`：原有字段，语义不变（Android 客户端只读 `ok`）；
- `version`：服务版本号；
- `model_configured`：服务端是否已配置模型密钥（仅布尔值，**不泄露密钥内容**）；
- `uptime_seconds`：进程已运行秒数。

Android 客户端在申请屏幕共享授权前会先检查该地址；如果检查失败，会提示确认风险服务已经启动、电脑防火墙允许端口访问，并且平板与电脑处于同一局域网。Docker / 负载均衡探活同样使用该接口。

## 2. 文字风险分析

```http
POST /api/v1/risk/text
Authorization: Bearer <RISK_API_TOKEN>
Content-Type: application/json
```

请求：

```json
{
  "session_id": "session-001",
  "request_id": "request-001",
  "source": "ocr",
  "captured_at": "2026-11-07T10:30:00+08:00",
  "text": "请先转账到指定账户，保证收益"
}
```

成功响应：

```json
{
  "session_id": "session-001",
  "request_id": "request-001",
  "status": "success",
  "risk_level": "high",
  "evidence": "请先转账到指定账户，保证收益",
  "reason": "出现要求转账和承诺收益的表述",
  "analyzed_at": "2026-11-07T10:30:03+08:00"
}
```

失败响应：

```json
{
  "session_id": "session-001",
  "request_id": "request-001",
  "status": "error",
  "error_code": "ANALYSIS_UNAVAILABLE",
  "message": "风险分析暂时不可用"
}
```

## 3. 字段和状态

- `session_id`：一次检测过程，开始检测时生成。
- `request_id`：一段新文字生成一个；网络重试沿用原 ID。
- `source`：第一版使用 `ocr`，音频转写接入后可传 `audio` 文本。
- `captured_at`：客户端截取画面的时间，ISO 8601 格式。
- `text`：OCR 文本，默认最长 5000 字。
- `risk_level`：`none`、`low`、`medium`、`high`。
- 失败响应不包含 `risk_level`。

HTTP 状态码：

| 状态码 | 含义 |
| --- | --- |
| 200 | 分析成功，或返回相同 `request_id` 的缓存结果 |
| 400 | JSON、字段或文字长度不合法 |
| 401 | Bearer 令牌缺失或错误 |
| 404 | 接口不存在 |
| 405 | 接口存在但请求方法不是 POST |
| 409 | 相同 `request_id` 携带了不同内容 |
| 413 | 请求体超过大小限制 |
| 429 | 触发限流（`error_code=RATE_LIMITED`），见下文“限流” |
| 502 | 上游模型返回无效结果 |
| 503 | 上游或服务不可用 |
| 504 | 上游超时 |

客户端默认超时 15 秒。网络失败、超时或 5xx 可重试一次，并沿用原 `request_id`；400、401 和 409 不自动重试。

### 3.1 限流（新增，向后兼容）

服务按 **客户端 Bearer 令牌 + 客户端 IP** 做固定 60 秒窗口限流，默认 **60 次/分钟**
（`RATE_LIMIT_PER_MINUTE` 可调，设为 `0` 关闭）。规则：

- 只对真正开始新分析的 `/api/v1/risk/*` 请求计数；
- `/health`、400/401 等校验失败请求不消耗配额；
- **幂等缓存命中优先于限流**：相同 `request_id` + 相同内容的重试直接返回 200 缓存，
  不消耗配额（见 §2 幂等规则）；
- 超限返回 HTTP 429：

```json
{
  "session_id": "session-001",
  "request_id": "request-009",
  "status": "error",
  "error_code": "RATE_LIMITED",
  "message": "请求过于频繁：每分钟最多 60 次，请在 12 秒后重试"
}
```

429 响应带以下响应头，客户端可据此退避重试（可用默认退避策略，不强制）：

| 响应头 | 说明 |
| --- | --- |
| `Retry-After` | 建议等待秒数 |
| `X-RateLimit-Limit` | 当前窗口配额上限 |
| `X-RateLimit-Remaining` | 剩余配额（429 时为 0） |
| `X-RateLimit-Reset` | 窗口重置的 Unix 时间戳（秒） |

客户端收到 429 时不应立即重试同一新分析；等待 `Retry-After` 后重试即可。Android
现有客户端即使不处理 429，也只会把该次分析当作失败（不会误报风险等级），不影响
其他请求。

## 4. 第三级屏幕图片分析

```http
POST /api/v1/risk/screen
Authorization: Bearer <RISK_API_TOKEN>
Content-Type: application/json
```

该接口用于腾讯会议白板等“文字由手写笔绘制、无法被 OCR 读取”的场景。图片只在内存中降采样和压缩，不写入客户端文件。

请求：

```json
{
  "session_id": "session-002",
  "request_id": "request-002",
  "captured_at": "2026-11-07T14:30:00+08:00",
  "ocr_text": "普通会议工具栏文字",
  "image_base64": "/9j/4AAQSkZJRgABAQ..."
}
```

限制：

- 仅支持 JPEG；
- 解码后最大 6 MB；
- 请求体最大 8 MB；
- 服务端使用 `AI_VISION_MODEL`，默认 `Qwen/Qwen3-VL-8B-Instruct`；
- 成功、失败和重复请求规则与文字接口相同。

## 5. 第三级语音分析

```http
POST /api/v1/risk/audio
Authorization: Bearer <RISK_API_TOKEN>
Content-Type: application/json
```

Android 通过 `AudioPlaybackCapture` 采集目标应用允许采集的播放音频，统一转换为：

- WAV；
- PCM 16 位；
- 16 kHz；
- 单声道；
- 每段约 10 秒，保留 0.5 秒重叠。

请求：

```json
{
  "session_id": "session-003",
  "request_id": "request-003",
  "captured_at": "2026-11-08T10:30:00+08:00",
  "duration_ms": 10000,
  "audio_base64": "UklGR..."
}
```

服务端先调用 `AI_ASR_MODEL` 转写，再把转写文字交给金融风险分析。成功响应在通用字段之外可包含：

```json
{
  "risk_level": "high",
  "evidence": "请把钱转到安全账户",
  "reason": "要求转账到安全账户",
  "transcript": "你好，我是公安局的……请把钱转到安全账户"
}
```

限制：

- 仅接受 PCM WAV，最大 4 MB；
- 如果没有识别出语音，返回 HTTP 422 和 `TRANSCRIPTION_EMPTY`；
- 腾讯会议是否允许 `AudioPlaybackCapture` 必须在目标设备实测；
- 采集不可用时，客户端明确显示“语音采集不可用”，不会回退录麦克风环境声。

## 6. 部署与稳定性说明

服务已做部署化，文件在 `finriskeye/hnu_FinRiskEye/deploy/`（详细步骤见其
`README.md`）：

- `Dockerfile`：`node:22-alpine`、非 root `node` 用户、只装生产依赖、
  `HEALTHCHECK` 调 `/health`；
- `docker-compose.yml`：`env_file` 注入服务端 `.env`、端口映射、`restart`、
  `healthcheck`、只读根文件系统；
- `nginx-risk-service.conf`：HTTPS 反向代理示例（证书占位、`client_max_body_size 9m`、
  `proxy_read_timeout 60s`、可选 nginx 层限流）；
- `start-windows.ps1`：Windows 一键本地启动（非 Docker）。

运维注意：

- **配置**：`RATE_LIMIT_PER_MINUTE`（默认 60，0 关闭）、`TRUST_PROXY`（反向代理后
  设为 1 才会按 `X-Forwarded-For` 识别客户端 IP）、`IDEMPOTENCY_TTL_MS` 等见
  `deploy/README.md` 变量表；密钥只放服务端 `thirdlevel/server/.env`（gitignored），
  不要写入镜像、文档或客户端；
- **日志**：每次请求向 stdout 输出一行 JSON（`event=request` 含 `request_id`、
  `source`、`status`、`risk_level`、`duration_ms`、`error_code`），不含 OCR 文本、
  图片/音频内容、Authorization 头或密钥；`docker logs` 可直接采集；
- **限流为单实例内存实现**：多副本部署时各副本独立计数，幂等缓存也不共享；
  教学演示/单机部署可接受，生产化需引入 Redis 等共享存储；
- **优雅关闭**：收到 SIGINT/SIGTERM 时停止接收新连接、清理幂等与限流定时器后退出
  （`docker stop` 会触发）；Windows 本地按 `Ctrl+C`；
- **HTTPS 与 Android**：现有客户端按 `http://<IP>:3100` 访问且未信任自签证书，
  教学演示建议保持 HTTP；公网/生产切 HTTPS 时 Android 需改为 https 并信任对应 CA。

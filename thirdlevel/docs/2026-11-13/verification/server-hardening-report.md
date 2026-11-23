# 第三级风险服务加固报告（限流 / 健康检查 / 结构化日志 / 幂等清理 / 部署化）

- 生成时间：2026-11-13
- 任务：task-6（12/8 稳定性要求 + 12/15 部署化水平）
- 服务目录：`finriskeye/hnu_FinRiskEye/thirdlevel/server`
- 服务版本：`1.1.0`（原 1.0.0）
- 结论：**PASS** —— `npm test` 42/42 通过；临时端口真实模型 E2E 7/7 通过；
  接口保持向后兼容（Android 现有客户端不改也能用）；部署文件齐备（本机无
  Docker/nginx，未做容器构建与 nginx 加载实测，已在局限中说明）。
- 现有 3100 服务未被停止/重启（PID 50172 仍在运行**旧代码**，见 §8 遗留项 1）。

---

## 1. 交付内容对照（任务 1–8）

| # | 要求 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 限流：令牌+IP，默认 60/分钟，`0` 关闭，429+`RATE_LIMITED`，幂等缓存优先 | ✅ | `test/rateLimit.test.js`（12 项）+ `rate-limit-demo.md` |
| 2 | `/health` 增量：保留 `ok`/`service`，新增 `version`/`model_configured`/`uptime_seconds` | ✅ | `test/health.test.js`（5 项）+ `health-response.json` |
| 3 | 结构化请求日志 + 优雅关闭（SIGINT/SIGTERM，清理 timer） | ✅ | `test/logging.test.js`（5 项）、`test/gracefulShutdown.test.js`（5 项）、`temp-server-3101.log` |
| 4 | 幂等缓存周期清理 | ✅ | `test/idempotencyCleanup.test.js`（4 项） |
| 5 | 部署化：Dockerfile / compose / nginx / Windows 脚本 / README / .dockerignore | ✅（文件齐备，容器未实测） | `deploy/`、`deployment-files.md` |
| 6 | 测试：扩展 `node --test` + 原有 11 项回归 + 真实文本 E2E | ✅ | `test/*.test.js`、`npm-test.log`、`temp-port-e2e-results.json` |
| 7 | 更新 `thirdlevel/API.md`（429、/health 新字段、部署注意，不改已有语义） | ✅ | `thirdlevel/API.md` |
| 8 | 证据目录 `evidence/server/hardening/` | ✅ | 本目录 |

---

## 2. 代码改动

### 2.1 新增文件

| 文件 | 内容 |
| --- | --- |
| `thirdlevel/server/rateLimiter.js`（161 行） | 固定窗口限流器：令牌+IP 分桶、`parsePerMinute`、桶过期清理、内存上限淘汰、`unref` 定时器、`perMinute=0` 关闭 |
| `thirdlevel/server/logger.js`（26 行） | 极简 JSON 行日志（`createJsonLogger`，默认写 stdout，可注入 stream/clock） |
| `thirdlevel/server/.dockerignore` | 构建上下文排除 `.env`/`node_modules`/`test`/日志 |
| `thirdlevel/server/test/helpers.js` | 新测试共用工具（随机端口服务、请求封装、waitFor） |
| `thirdlevel/server/test/rateLimit.test.js`（12 项） | 限流单元 + HTTP 集成（429、缓存优先、/health 免限流、0 关闭、IP 分桶、窗口滚动） |
| `thirdlevel/server/test/health.test.js`（5 项） | /health 新字段、不泄露密钥、向后兼容、uptime 注入、响应头 |
| `thirdlevel/server/test/logging.test.js`（5 项） | 结构化日志字段、错误 code、不记录文本/令牌、analysis_start、/health 日志 |
| `thirdlevel/server/test/idempotencyCleanup.test.js`（4 项） | 过期清理、pending 不清理、周期定时器、`server.cleanup()` |
| `thirdlevel/server/test/gracefulShutdown.test.js`（5 项） | SIGTERM 流程、重复信号幂等、关闭异常、超时强制、真实 server 关闭 |

### 2.2 修改文件

| 文件 | 改动 |
| --- | --- |
| `thirdlevel/server/server.js`（+362/-37 行） | 限流接入（幂等缓存/处理中请求优先、仅新分析消耗配额）、429 + `Retry-After`/`X-RateLimit-*`、/health 新字段、请求级结构化日志（request_id/source/status/risk_level/duration_ms/error_code，不含内容与密钥）、`analysis_start` 事件、`X-Request-Id` 透传、`TRUST_PROXY`、优雅关闭（`createShutdownHandler`，超时强制断开）、幂等周期清理定时器、`server.cleanup()`、启动配置摘要日志（无密钥） |
| `thirdlevel/server/idempotencyStore.js` | 增加 `size` getter（测试/运维观察用） |
| `thirdlevel/server/package.json` | 版本 `1.0.0 -> 1.1.0`；`test` 脚本改为 `node --test "test/*.test.js"`（显式限定用例文件，避免把 `helpers.js` 计为测试） |
| `thirdlevel/server/.env.example` | 新增 `RATE_LIMIT_PER_MINUTE`、`TRUST_PROXY`、`IDEMPOTENCY_CLEANUP_INTERVAL_MS`、`SHUTDOWN_TIMEOUT_MS` 及说明 |
| `thirdlevel/server/test/server.test.js` | 仅更新 health 用例断言（保留原 11 项测试名称/语义，适配增量字段） |
| `thirdlevel/API.md` | /health 新字段、429 限流章节与响应头、状态码表补 405/429、§6 部署与稳定性说明；已有字段语义未改 |

### 2.3 关键实现决策

1. **限流位置**：在完成认证、请求体校验、幂等 `begin()` 之后才检查限流。
   - 因此 `400/401/404/409` 等请求不消耗配额；
   - `cached`（幂等缓存命中）与 `pending`（同 request_id 并发重复）直接返回 200，
     **优先于限流**；只有进入模型分析的新请求才消耗一次配额；
   - 限流被拒时 `idempotencyStore.fail()` 释放 pending 记录，等待中的重复请求同样收到 429。
2. **限流键**：`sha256(Bearer 令牌)[:16] + "|" + 客户端 IP`，不保存明文令牌；
   `TRUST_PROXY=1` 时才取 `X-Forwarded-For` 首位（默认关闭，防伪造绕过）。
3. **429 响应**：`error_code=RATE_LIMITED`，带 `Retry-After`、`X-RateLimit-Limit`、
   `X-RateLimit-Remaining`、`X-RateLimit-Reset`；错误体不含 `risk_level`（遵循既有契约）。
4. **日志**：一行一个 JSON；请求结束（`finish` 或客户端断开 `close`）各输出一次；
   字段 `ts/level/event/method/path/remote_ip/request_id/source/status/risk_level/error_code/duration_ms`；
   错误请求 `level=warn` 且 `error_code` 为具体业务码；无 Authorization、无请求内容。
5. **优雅关闭**：`createShutdownHandler` 可注入 `exit`/`log`/`timeoutMs`，便于单测；
   关闭时清 `idempotencyCleanupTimer` 与限流器 timer；Windows 本地 Ctrl+C（SIGINT）
   与 Linux `docker stop`（SIGTERM）均生效；超时强制 `closeAllConnections()` 后退出。
6. **幂等清理**：默认每 60s 清理一次过期完成项（`IDEMPOTENCY_CLEANUP_INTERVAL_MS`
   可调，上限 60s、下限 1s，随 TTL 自适应）；pending 项不动。

---

## 3. 测试结果（npm test）

- 命令：`cd finriskeye/hnu_FinRiskEye/thirdlevel/server; npm test`
- 结果：**42 项全部通过 / 0 失败 / 0 跳过**（`npm-test.log`，本次 duration ≈ 601ms）
- 构成：**原有 11 项回归**（health/认证/文本/超时/幂等/画面/语音）+ **新增 31 项**
  - 限流 12 项：默认 60/0 关闭、固定窗口、429 + 响应头、缓存优先、/health 免限流、
    401/400 不消耗、令牌+IP 分桶、窗口滚动、清理；
  - /health 5 项：增量字段、不泄露密钥、向后兼容、uptime、响应头；
  - 结构化日志 5 项：字段完整性、错误 code（401/404/429）、无内容/令牌泄露、
    `analysis_start`、/health 日志；
  - 幂等清理 4 项：过期删除、pending 保留、周期清理、cleanup 幂等；
  - 优雅关闭 5 项：正常关闭、重复信号、关闭异常、兜底超时、真实 http server。
- 测试不调用真实模型、不使用真实密钥（fake token + 注入 analyzer/logger）。

---

## 4. 真实模型 E2E（临时端口，未影响 3100）

- 脚本：`finriskeye/evidence/server/hardening/run-temp-port-e2e.ps1`（可复现）
- 端口：3101（真实模型 + `RATE_LIMIT_PER_MINUTE=1`）、3102（默认配置冒烟）
- 真实模型：SiliconFlow `deepseek-ai/DeepSeek-V3.2`，**只调用 1 次**（3.94s）
- 结果：**7/7 PASS**（`temp-port-e2e-results.json`）

| 检查 | 结果 |
| --- | --- |
| /health 新字段 | 200，`version=1.1.0`、`model_configured=true`、`uptime_seconds` |
| 真实文本分析 | 200，`risk_level=high`，3.94s，响应字段与旧版一致 |
| 幂等缓存优先 | 相同 request_id 重试 200（3ms，`analyzed_at` 相同），未消耗配额 |
| 限流 429 | 429 + `RATE_LIMITED` + 全部限流响应头 |
| 401 不受限流影响 | 401 `UNAUTHORIZED`（非 429） |
| /health 不受限流影响 | 配额耗尽后仍 200 |
| 默认限流 60 | 3102 启动日志 `rate_limit_per_minute=60` |

详细请求/响应见 `rate-limit-demo.md`、`temp-port-e2e-transcript.txt`、
`temp-server-3101.log`（结构化日志样例，无任何密钥）。

---

## 5. 向后兼容性结论

- **成功响应字段完全未变**：`session_id/request_id/status/risk_level/evidence/reason/
  analyzed_at`（语音另有 `transcript`）；E2E 实际返回逐字段一致。
- **错误响应结构未变**：`session_id/request_id/status/error_code/message`，仍不含
  `risk_level`；新增的 429 只是多一个错误码，不改变既有错误语义。
- **/health**：原 `ok`/`service` 保留，仅新增 3 个字段；Android 只读 `ok`，
  旧客户端解析无影响。
- **原有行为保留**：认证、幂等（cached/pending/409）、请求体大小限制、404/405、
  各接口校验与错误码均与旧版一致（11 项回归测试通过）。
- 客户端即使完全不处理 429，也只会把该次分析视为失败（不会误报 risk_level），
  其他请求不受影响；后续可按需在 Android 端对 429/`Retry-After` 做友好提示。

---

## 6. 部署化产物

详见 `deployment-files.md` 与 `deploy/README.md`：

- `deploy/Dockerfile`：node:22-alpine、非 root、`npm ci --omit=dev`（无 lock 回退
  `npm install --omit=dev`）、HEALTHCHECK 调 /health；
- `deploy/docker-compose.yml`：`env_file` + `3100:3100` 端口映射 +
  `restart: unless-stopped` + healthcheck + 只读根文件系统/tmpfs/no-new-privileges；
- `deploy/nginx-risk-service.conf`：HTTPS 证书占位、`client_max_body_size 9m`、
  `proxy_read_timeout 60s`、转发 X-Forwarded-For/X-Request-Id、可选 nginx 层限流；
- `deploy/start-windows.ps1`：Node/.env/端口检查 + 可选防火墙规则 + 前台启动；
- `deploy/README.md`：变量表、两种启动方式、健康检查、防火墙、HTTPS/证书、
  密钥轮换、局限、故障排查；
- `deploy/Dockerfile.dockerignore` + `thirdlevel/server/.dockerignore`：排除
  `.env`/`node_modules`。

验证状态：PowerShell 脚本经 Parser 解析通过并以“端口占用”场景实测退出逻辑；
compose YAML 经 PyYAML 解析且 context/dockerfile/env_file 相对路径均确认存在；
**本机无 Docker 与 nginx，容器构建与 nginx 加载未实测**（需在目标机执行
`docker compose ... up -d --build` 与 `nginx -t`）。

---

## 7. 安全与密钥处理

- 真实 `AI_API_KEY` 全程**未读取、未打印、未复制**到任何文件/日志/报告；
  演示脚本使用一次性令牌 `demo-rate-limit-token` 通过环境变量覆盖。
- 服务端可观测输出只含：请求元数据（方法/路径/IP/request_id/source/status/
  risk_level/耗时/error_code）；`model_configured` 仅为布尔值。
- 证据目录已扫描 `sk-*`、长 Bearer 令牌、`AI_API_KEY=` 等模式：**无匹配**。
- `.env` 未被修改；部署产物与镜像均不含 `.env` 内容。

---

## 8. 遗留项与建议（按优先级）

1. **⚠️ 3100 现有进程仍是旧代码**（PID 50172，本次全程未动）。加固与 429/health
   新字段需重启服务后才生效。建议流程：等 emulator-verifier 完成当前联调 →
   Lead 协调停止 50172 → 在 `thirdlevel/server` 重新执行
   `$env:TEMP=$env:TMP="<工作区>\finriskeye\build-home\tmp"; node --env-file=.env server.js`
   （或 `deploy/start-windows.ps1`）→ `curl /health` 确认 `version=1.1.0`。
2. **Docker/nginx 未在本机实测**（无 Docker/nginx）：首次部署请执行镜像构建、
   `docker compose ps` 确认 healthy、`nginx -t` 后再 reload；
   `deploy/README.md` 已给出命令与排错表。
3. **Windows 无信号级优雅关闭**：Windows 下 `Stop-Process` 为强制结束（无法投递
   SIGTERM）；优雅关闭逻辑已由单测覆盖，Linux/Docker `docker stop` 会真实触发。
   本地演示停止建议用 Ctrl+C（SIGINT）。
4. **单实例内存实现局限**：限流与幂等缓存不跨进程/不持久化，重启即清空；多副本
   部署时各自计数、缓存不共享。生产化建议引入 Redis 共享限流+幂等存储。
5. **Android 端 429 体验**：现有客户端不区分 429，仅显示失败；如需“请求过于频繁”
   提示，需 Android 侧按 `error_code=RATE_LIMITED`/`Retry-After` 做退避与提示
   （本次不改 Android，保持向后兼容）。
6. **HTTPS 与 Android**：nginx 示例含证书占位；现有 Android 客户端未信任自签证书，
   教学演示建议继续 HTTP，公网部署时 Android 需改 https 并导入 CA。
7. `package.json` 无 `package-lock.json`：Dockerfile 已做 `npm ci` 失败回退
   `npm install --omit=dev`；后续若新增依赖建议提交 lock 文件以固定版本。

---

## 9. 复现命令

```powershell
# 1) 单测（42 项，不调用真实模型）
cd C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\hnu_FinRiskEye\thirdlevel\server
$env:TEMP=$env:TMP="C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye\build-home\tmp"
npm test

# 2) 临时端口真实 E2E（会真实调用文本模型 1 次；自动启停 3101/3102）
cd ..\..\..\..\finriskeye\evidence\server\hardening
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-temp-port-e2e.ps1

# 3) 查看 /health（服务启动后）
curl.exe http://127.0.0.1:3100/health
```

---

## 10. 证据文件清单（本目录）

| 文件 | 内容 |
| --- | --- |
| `server-hardening-report.md` | 本报告 |
| `npm-test.log` | npm test 完整输出（42 pass / 0 fail） |
| `rate-limit-demo.md` | 限流/幂等优先/健康检查演示证据 |
| `deployment-files.md` | 部署文件清单、验证状态、使用命令、安全属性 |
| `run-temp-port-e2e.ps1` | 可复现 E2E 脚本 |
| `temp-port-e2e-transcript.txt` | E2E 执行过程转录 |
| `temp-port-e2e-results.json` | 7 项检查结构化结果（pass=true） |
| `text-e2e-response.json` / `text-e2e-headers.txt` | 真实模型文本分析响应/响应头 |
| `text-cached-response.json` / `text-cached-headers.txt` | 幂等缓存命中响应 |
| `rate-limit-429-response.json` / `rate-limit-429-headers.txt` | 429 响应体/头 |
| `unauthorized-401-response.json` / `unauthorized-401-headers.txt` | 401 响应体/头 |
| `health-response.json` / `health-after-limit-response.json` / `health-default-config-response.json` / `health-headers.txt` | /health 证据 |
| `temp-server-3101.log` / `temp-server-3102.log` | 服务端结构化 JSON 日志 |
| `temp-server-3101.err.log` / `temp-server-3102.err.log` | stderr（空） |
| `request-a.json` / `request-b.json` | 演示请求体 |

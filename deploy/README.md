# 第三级风险服务部署说明（deploy/）

本目录是第三级 Node 风险服务的部署化产物：Docker 镜像、Compose 编排、nginx HTTPS
反向代理示例、Windows 一键启动脚本。服务本体仍在 `thirdlevel/server/`，接口契约见
`thirdlevel/API.md`。

```
thirdlevel/server/    # 服务源码（server.js / contract.js / riskAnalyzer.js / ...）
thirdlevel/API.md     # 接口文档（含 429 与 /health 新字段）
deploy/
  Dockerfile               # node:22-alpine，非 root，HEALTHCHECK 调 /health
  Dockerfile.dockerignore  # BuildKit 与 Dockerfile 同名的忽略文件
  docker-compose.yml       # env_file + 端口映射 + restart + healthcheck
  nginx-risk-service.conf  # HTTPS 反代示例（证书占位、超时、9m 体积、限流）
  start-windows.ps1        # Windows 本地一键启动（非 Docker 路径）
  README.md                # 本文件
```

服务版本：`1.1.0`（`/health` 的 `version` 字段）。

---

## 1. 环境变量

配置来源优先级：**进程环境变量 > `--env-file=.env` 文件**。密钥只放服务端
`thirdlevel/server/.env`（已被 gitignore），不要写进任何源码/文档/镜像。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `3100` | HTTP 监听端口 |
| `HOST` | `0.0.0.0` | 监听地址；仅本机可改 `127.0.0.1` |
| `RISK_API_TOKEN` | 无（必填） | Android 客户端 Bearer 令牌；缺失时服务拒绝启动 |
| `AI_API_KEY` | 无 | 硅基流动（SiliconFlow）密钥，只在服务端使用，不返回给客户端 |
| `AI_BASE_URL` | `https://api.siliconflow.cn/v1` | 模型服务地址 |
| `AI_MODEL` | `deepseek-ai/DeepSeek-V3.2` | 文本风险模型 |
| `AI_VISION_MODEL` | `Qwen/Qwen3-VL-8B-Instruct` | 画面风险模型 |
| `AI_ASR_MODEL` | `FunAudioLLM/SenseVoiceSmall` | 语音转写模型 |
| `UPSTREAM_TIMEOUT_MS` | `12000` | 文本模型超时 |
| `VISION_TIMEOUT_MS` | `13000` | 画面模型超时（需低于客户端 15s 超时） |
| `ASR_TIMEOUT_MS` | `12000` | 语音转写超时 |
| `MAX_TEXT_LENGTH` | `5000` | 文本最大长度 |
| `IDEMPOTENCY_TTL_MS` | `600000` | 幂等缓存保留时间（10 分钟） |
| `IDEMPOTENCY_CLEANUP_INTERVAL_MS` | `60000` | 幂等缓存过期清理周期 |
| `RATE_LIMIT_PER_MINUTE` | `60` | 每（客户端令牌 + IP）每分钟分析次数；`0` 关闭限流 |
| `TRUST_PROXY` | `0` | 设 `1` 时才信任 `X-Forwarded-For`（仅当服务只暴露给可信 nginx 时） |
| `SHUTDOWN_TIMEOUT_MS` | `10000` | SIGINT/SIGTERM 优雅关闭等待上限 |

首次部署：`cp thirdlevel/server/.env.example thirdlevel/server/.env`，再填入
`RISK_API_TOKEN` 与 `AI_API_KEY`。

---

## 2. 启动方式一：Windows 本地（最快，适合教学演示）

```powershell
# 在 finriskeye/hnu_FinRiskEye 目录
.\deploy\start-windows.ps1                    # 3100，监听 0.0.0.0
.\deploy\start-windows.ps1 -Port 3101 -Bind 127.0.0.1   # 临时端口自测
.\deploy\start-windows.ps1 -AddFirewallRule   # 管理员：自动放行入站端口
```

> 若系统执行策略禁止运行本地脚本（报 "not digitally signed"），改用：
> `powershell -ExecutionPolicy Bypass -File .\deploy\start-windows.ps1`

脚本会先检查 Node.js >= 20、`.env` 是否存在、端口是否被占用；前台运行，
`Ctrl+C` 触发服务端优雅关闭（清理幂等/限流定时器后退出）。

Windows 项目根的构建/模拟器约定（TEMP 重定向到工作区）不影响本脚本；如在本仓
沙箱子进程中运行，请先把 `TEMP`/`TMP` 指到工作区内目录。

---

## 3. 启动方式二：Docker Compose（部署到服务器/长期运行）

```bash
# 前提：thirdlevel/server/.env 已配置好
cd finriskeye/hnu_FinRiskEye

docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml ps          # STATUS 应为 healthy
docker compose -f deploy/docker-compose.yml logs -f     # JSON 行日志
```

或等价的手动构建（Docker CLI 支持 Dockerfile 在构建上下文之外）：

```bash
docker build -f deploy/Dockerfile -t hnufinriskeye/thirdlevel-risk:1.1.0 thirdlevel/server
docker run -d --name thirdlevel-risk-service \
  --env-file thirdlevel/server/.env \
  -e PORT=3100 -e HOST=0.0.0.0 \
  -p 3100:3100 --restart unless-stopped \
  --read-only --tmpfs /tmp:size=16m \
  hnufinriskeye/thirdlevel-risk:1.1.0
```

镜像要点：`node:22-alpine`、`npm ci --omit=dev`（无 `package-lock.json` 时回退
`npm install --omit=dev`）、非 root `node` 用户、`HEALTHCHECK` 请求 `/health`、
`.env`/`node_modules` 被忽略（`thirdlevel/server/.dockerignore` 与
`deploy/Dockerfile.dockerignore` 双保险）。

Compose 要点：`env_file` 注入密钥、`3100:3100` 端口映射、`restart: unless-stopped`、
`healthcheck`、只读根文件系统 + `no-new-privileges`、json-file 日志轮转。
如需只允许本机 nginx 反代访问，把端口映射改为 `127.0.0.1:3100:3100`。

> 本机当前未安装 Docker（2026-11-13 验证），以上命令需在装有 Docker 的机器执行；
> Compose 语法已按 v2 校验，Windows 本地路径请用 `start-windows.ps1`。

---

## 4. 健康检查

```bash
curl -s http://127.0.0.1:3100/health
```

```json
{
  "ok": true,
  "service": "thirdlevel-risk-service",
  "version": "1.1.0",
  "model_configured": true,
  "uptime_seconds": 3600
}
```

- `ok` / `service`：原有字段，Android 客户端只读 `ok`；
- `version`：服务版本；
- `model_configured`：模型密钥是否已配置（**布尔值，不含密钥**）；
- `uptime_seconds`：进程运行秒数。

`/health` 不要求认证、不参与限流，可安全用于负载均衡探活。Dockerfile 的
`HEALTHCHECK` 与 compose 的 `healthcheck` 都调用该接口。

---

## 5. 限流与幂等（12/8 稳定性要求）

- 限流键 = **客户端 Bearer 令牌 + 客户端 IP**，固定 60 秒窗口，默认 60 次/分钟；
- 只对真正开始新分析的 `/api/v1/risk/*` 请求计数；`/health`、4xx 校验失败、
  401 认证失败不消耗配额；
- **幂等缓存命中优先**：同一 `request_id` + 相同内容的重复请求直接返回 200 缓存，
  不消耗限流配额（客户端网络重试不会被误伤）；
- 超限返回 HTTP 429 + `error_code: "RATE_LIMITED"`，并带
  `Retry-After`、`X-RateLimit-Limit/Remaining/Reset` 响应头；
- `RATE_LIMIT_PER_MINUTE=0` 可完全关闭限流（便于压测/演示）；
- 反代场景（nginx）必须设置 `TRUST_PROXY=1` 才会读取 `X-Forwarded-For`，
  否则所有请求都会被算作 127.0.0.1；**只有服务不直接暴露公网时才可开启**，
  否则客户端可伪造该头绕过限流。

幂等缓存默认 10 分钟过期，服务每 `IDEMPOTENCY_CLEANUP_INTERVAL_MS`（默认 60s）
清理一次过期项；处理中（pending）的请求不会被清理。

---

## 6. 防火墙

Windows（管理员 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "FinRiskEye Risk 3100" -Direction Inbound `
  -Protocol TCP -LocalPort 3100 -Action Allow
```

或用脚本：`.\deploy\start-windows.ps1 -AddFirewallRule`。
Linux 服务器（仅暴露必要的 443 端口；3100 只监听本机）：

```bash
sudo ufw allow 443/tcp
sudo ufw deny 3100/tcp    # 若服务只走 nginx 反代
```

Android 模拟器访问宿主机服务用 `http://10.0.2.2:3100`；局域网真机用
`http://<电脑局域网IP>:3100`，需确认平板与电脑同一网段、电脑防火墙放行该端口。

---

## 7. HTTPS / 证书（nginx 反向代理）

`nginx-risk-service.conf` 是可直接改域名/证书路径使用的示例，覆盖：

- 80 -> 443 跳转；
- 证书占位 `ssl_certificate` / `ssl_certificate_key`；
- `client_max_body_size 9m`（画面接口 8MB 请求体 + 余量）；
- `proxy_read_timeout 60s` / `proxy_send_timeout 60s`（分析单次最长约 15s）；
- `X-Forwarded-For` / `X-Real-IP` / `X-Request-Id` 透传；
- `/health` 免限流免访问日志，`/api/v1/risk/` 加 nginx 层限流兜底（120r/m，burst 20）。

证书获取：

- 公网域名：`certbot --nginx -d risk.example.com`（Let's Encrypt 自动续期）；
- 内网/演示：自签证书（`openssl req -x509 -newkey rsa:2048 -nodes ...`），
  浏览器会提示不受信任，属于预期行为。

**Android 客户端注意**：当前第三级客户端按 `http://<IP>:3100` 访问、未内置自签
证书信任。教学演示建议继续走 HTTP；若切 HTTPS，Android 需改地址为 https 并在
网络安全配置/系统信任链中导入对应 CA（或使用受信任的公网证书），否则会 TLS 失败。
反向代理后记得在服务 `.env` 设置 `TRUST_PROXY=1`。

---

## 8. 密钥轮换

模型密钥（`AI_API_KEY`）泄露/到期时的轮换步骤：

1. 到硅基流动控制台创建新 Key，并禁用/删除旧 Key；
2. 修改服务器 `thirdlevel/server/.env` 的 `AI_API_KEY`（不要 commit，不要写进文档）；
   `RISK_API_TOKEN` 轮换同理，但要同步更新 Android 客户端（App 内填写的令牌）；
3. 重启使新配置生效：
   - Docker：`docker compose -f deploy/docker-compose.yml restart risk-service`；
   - Windows 本地：`Ctrl+C`（优雅关闭）后重新运行 `start-windows.ps1`；
4. 验证：`curl /health` 返回 `"model_configured": true`，再发一条文本分析请求确认
   200（`/health` 不会暴露密钥本身）；
5. 旧 Key 确认已失效；检查 `docker logs` / 本地 stdout 日志中无密钥痕迹（服务只输出
   结构化请求日志，本就不记录 Authorization 与请求内容）。

注意：镜像内不含 `.env`；更新密钥不需要重新构建镜像，只需重启容器。

---

## 9. 局限（如实说明）

- **单实例内存实现**：幂等缓存与限流都在进程内存中，重启即丢失、多副本不共享；
  只适合教学演示/单机部署。生产化需换 Redis 等共享存储，并做多副本负载均衡；
- **无持久化存储**：服务不落盘（日志只到 stdout），重启后幂等历史清零；
- **无用户级鉴权**：只有一个共享 Bearer 令牌，所有客户端同权；令牌泄露需立即轮换；
- **限流基于客户端令牌 + IP**：同一 NAT 后的多台设备会共享 IP 计数；多副本时
  每副本各自计数（更宽松）；
- **模型调用有费用**：每次新分析都会调用硅基流动接口，演示时注意次数；
- **健康检查只证明进程可响应**：`model_configured=true` 只说明配置了 Key，
  不保证上游模型一定可用；上游故障时分析接口返回 503/504；
- **HTTPS 示例不包含证书自动续期**：生产请配合 certbot 或云厂商证书服务；
- 本目录产物未在本机实际执行 `docker build`（本机无 Docker），部署前请在目标机
  执行一次构建与 `/health` 验证。

---

## 10. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 启动报“缺少 RISK_API_TOKEN” | 未创建/未填 `.env` | 复制 `.env.example` 为 `.env` 并填令牌 |
| `EADDRINUSE` | 3100 被占用 | 换端口（`PORT=3101`）或停止旧进程 |
| compose 报 env_file 不存在 | `thirdlevel/server/.env` 缺失 | 先创建 `.env`（gitignored） |
| Android 连不上 | 防火墙/网段/地址端口错误 | 放行端口；模拟器用 `10.0.2.2`；先 `curl /health` |
| 429 `RATE_LIMITED` | 触发限流 | 等 `Retry-After` 秒数或调大 `RATE_LIMIT_PER_MINUTE` |
| 401 `UNAUTHORIZED` | 令牌不一致 | 检查 `.env` 的 `RISK_API_TOKEN` 与客户端填写一致 |
| 503 / 504 | 上游模型不可用/超时 | 检查 `AI_API_KEY`、余额、网络与超时配置 |
| 容器 unhealthy | `.env` 缺令牌导致进程未起 | `docker logs thirdlevel-risk-service` 查看 `start_failed` |

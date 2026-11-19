# syntax=docker/dockerfile:1
#
# 第三级风险服务镜像（Node 风险分析 API）
# 构建上下文必须是 thirdlevel/server 目录（见 deploy/README.md）：
#   docker build -f deploy/Dockerfile -t hnufinriskeye/thirdlevel-risk:1.1.0 thirdlevel/server
#
# 安全设计：
# - 只装生产依赖（npm ci --omit=dev；无 package-lock 时回退 npm install --omit=dev）；
# - 以非 root 的 node 用户运行；
# - 镜像内不含 .env / 模型密钥（由 docker-compose env_file 或运行参数注入）；
# - HEALTHCHECK 定期请求 /health（ok=true 即健康）。
FROM node:22-alpine

ENV NODE_ENV=production \
    PORT=3100 \
    HOST=0.0.0.0

WORKDIR /app

# 依赖层单独缓存。当前服务没有运行时依赖，保留 npm 流程以兼容后续扩展。
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then \
      npm ci --omit=dev --no-audit --no-fund; \
    else \
      npm install --omit=dev --no-audit --no-fund; \
    fi \
    && npm cache clean --force

# 只复制运行所需文件：不含 .env、test、日志、本地脚本。
COPY contract.js idempotencyStore.js logger.js rateLimiter.js riskAnalyzer.js server.js package.json ./

USER node

EXPOSE 3100

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 3100) + '/health').then((response) => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "server.js"]

'use strict';

const http = require('http');
const crypto = require('crypto');
const {
  ApiError,
  buildErrorResponse,
  buildSuccessResponse,
  requestFingerprint,
  validateAudioRequest,
  validateScreenRequest,
  validateTextRequest,
} = require('./contract');
const {
  IdempotencyStore,
  RequestIdConflictError,
} = require('./idempotencyStore');
const {
  audioRequestFingerprint,
  createAudioRiskAnalyzer,
  createRiskAnalyzer,
  createVisionRiskAnalyzer,
  screenRequestFingerprint,
} = require('./riskAnalyzer');
const {
  DEFAULT_RATE_LIMIT_PER_MINUTE,
  RateLimiter,
  parsePerMinute,
} = require('./rateLimiter');
const { createJsonLogger } = require('./logger');
const packageInfo = require('./package.json');

const SERVICE_NAME = 'thirdlevel-risk-service';
const SERVICE_VERSION = packageInfo.version;

const MAX_TEXT_BODY_BYTES = 64 * 1024;
const MAX_SCREEN_BODY_BYTES = 8 * 1024 * 1024;
const MAX_AUDIO_BODY_BYTES = 6 * 1024 * 1024;

const ANALYSIS_PATHS = new Set([
  '/api/v1/risk/text',
  '/api/v1/risk/screen',
  '/api/v1/risk/audio',
]);

const DEFAULT_LOGGER = createJsonLogger();

/**
 * 创建风险服务（纯 HTTP 层，不读进程环境变量，便于单测注入）。
 *
 * 新增（向后兼容）：
 * - rateLimitPerMinute：按“客户端令牌 + IP”限流，0 = 关闭；只作用于分析接口，
 *   且幂等缓存命中 / 正在处理中的重复请求优先返回 200，不消耗配额；
 * - modelConfigured：仅暴露布尔值给 /health，绝不泄露密钥；
 * - log：结构化日志注入点（默认输出 JSON 行到 stdout，测试可注入收集器）。
 */
function createRiskServer(options) {
  const {
    analyzeText,
    analyzeScreen,
    analyzeAudio,
    riskApiToken,
    maxTextLength = 5000,
    idempotencyTtlMs = 600000,
    idempotencyCleanupIntervalMs = defaultIdempotencyCleanupInterval(idempotencyTtlMs),
    rateLimitPerMinute = DEFAULT_RATE_LIMIT_PER_MINUTE,
    trustProxy = false,
    modelConfigured = false,
    log = DEFAULT_LOGGER,
    now = Date.now,
    uptime = () => process.uptime(),
  } = options;

  if (typeof analyzeText !== 'function') {
    throw new Error('createRiskServer 需要 analyzeText');
  }

  // 统一补齐 ts：注入的日志收集器（单测）也能拿到完整结构化记录。
  const emitLog = (record) => log({ ts: new Date().toISOString(), ...record });

  const idempotencyStore = new IdempotencyStore(idempotencyTtlMs);
  const idempotencyCleanupTimer = setInterval(
    () => idempotencyStore.cleanup(),
    idempotencyCleanupIntervalMs
  );
  if (typeof idempotencyCleanupTimer.unref === 'function') {
    idempotencyCleanupTimer.unref();
  }

  const rateLimiter = new RateLimiter({
    perMinute: rateLimitPerMinute,
    now,
  });
  rateLimiter.startCleanupTimer();

  const server = http.createServer(async (req, res) => {
    const startedAt = Date.now();
    const pathname = getPathname(req.url);
    const requestContext = {
      request_id: getHeaderValue(req, 'x-request-id'),
      source: null,
      risk_level: null,
      error_code: null,
    };

    let logged = false;
    const writeRequestLog = () => {
      if (logged) {
        return;
      }
      logged = true;
      emitLog({
        level: requestContext.error_code ? 'warn' : 'info',
        event: 'request',
        method: req.method,
        path: pathname,
        remote_ip: getClientIp(req, trustProxy),
        request_id: requestContext.request_id || null,
        source: requestContext.source,
        status: res.statusCode,
        risk_level: requestContext.risk_level,
        error_code: requestContext.error_code,
        duration_ms: Date.now() - startedAt,
      });
    };
    res.on('finish', writeRequestLog);
    // 客户端提前断开（没有 finish）时也保证有一条日志。
    res.on('close', writeRequestLog);

    setCommonHeaders(res);
    if (requestContext.request_id) {
      res.setHeader('X-Request-Id', requestContext.request_id);
    }

    let request = null;
    let ownership = null;
    try {
      if (req.method === 'GET' && pathname === '/health') {
        sendJson(res, 200, buildHealthPayload({ modelConfigured, uptime }));
        return;
      }

      if (!ANALYSIS_PATHS.has(pathname)) {
        throw new ApiError(404, 'NOT_FOUND', '接口不存在');
      }
      if (req.method !== 'POST') {
        throw new ApiError(405, 'METHOD_NOT_ALLOWED', '只支持 POST');
      }
      if (!isAuthorized(req, riskApiToken)) {
        throw new ApiError(401, 'UNAUTHORIZED', '认证失败');
      }

      const isScreenRequest = pathname === '/api/v1/risk/screen';
      const isAudioRequest = pathname === '/api/v1/risk/audio';
      const body = await readJsonBody(
        req,
        isScreenRequest
          ? MAX_SCREEN_BODY_BYTES
          : (isAudioRequest ? MAX_AUDIO_BODY_BYTES : MAX_TEXT_BODY_BYTES)
      );
      if (isScreenRequest) {
        request = validateScreenRequest(body, maxTextLength);
      } else if (isAudioRequest) {
        request = validateAudioRequest(body);
      } else {
        request = validateTextRequest(body, maxTextLength);
      }
      requestContext.request_id = request.request_id;
      requestContext.source = request.source;

      ownership = idempotencyStore.begin(
        request.request_id,
        isScreenRequest
          ? screenRequestFingerprint(request)
          : (isAudioRequest
            ? audioRequestFingerprint(request)
            : requestFingerprint(request))
      );

      // 幂等缓存命中优先于限流：重试同一 request_id 不消耗配额。
      if (ownership.kind === 'cached') {
        requestContext.risk_level = ownership.response.risk_level;
        sendJson(res, 200, ownership.response);
        return;
      }
      if (ownership.kind === 'pending') {
        const pendingResponse = await ownership.promise;
        requestContext.risk_level = pendingResponse.risk_level;
        sendJson(res, 200, pendingResponse);
        return;
      }

      if (isScreenRequest && typeof analyzeScreen !== 'function') {
        throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '画面风险分析未配置');
      }
      if (isAudioRequest && typeof analyzeAudio !== 'function') {
        throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '语音风险分析未配置');
      }

      // 只有真正要调用模型的“新分析”才消耗限流配额。
      const rateLimitResult = rateLimiter.consume(
        RateLimiter.buildKey(
          extractBearerToken(req),
          getClientIp(req, trustProxy)
        )
      );
      if (!rateLimitResult.allowed) {
        setRateLimitHeaders(res, rateLimitResult);
        throw new ApiError(
          429,
          'RATE_LIMITED',
          `请求过于频繁：每分钟最多 ${rateLimitResult.limit} 次，`
            + `请在 ${rateLimitResult.retryAfterSeconds} 秒后重试`
        );
      }

      const analysisKind = isScreenRequest
        ? 'screen'
        : (isAudioRequest ? 'audio' : 'text');
      emitLog({
        level: 'info',
        event: 'analysis_start',
        request_id: request.request_id,
        source: request.source,
        kind: analysisKind,
      });

      const analysis = isScreenRequest
        ? await analyzeScreen(request)
        : (isAudioRequest ? await analyzeAudio(request) : await analyzeText(request));
      const response = buildSuccessResponse(request, analysis);
      idempotencyStore.complete(request.request_id, response);
      requestContext.risk_level = response.risk_level;
      sendJson(res, 200, response);
    } catch (error) {
      if (ownership && ownership.kind === 'owner') {
        idempotencyStore.fail(request.request_id, error);
      }
      handleError(res, error, request, requestContext, emitLog);
    }
  });

  // 优雅关闭 / 测试用：清理周期定时器，释放内存。
  server.cleanup = () => {
    clearInterval(idempotencyCleanupTimer);
    rateLimiter.stop();
  };
  // 暴露给测试与运维观察（不属于 HTTP 契约）。
  server.idempotencyStore = idempotencyStore;
  server.rateLimiter = rateLimiter;
  server.serviceVersion = SERVICE_VERSION;

  return server;
}

function buildHealthPayload({ modelConfigured, uptime }) {
  return {
    // 原有字段保持不变（Android 客户端只读 ok）。
    ok: true,
    service: SERVICE_NAME,
    // 新增字段：只做增量，不改变已有语义。
    version: SERVICE_VERSION,
    model_configured: Boolean(modelConfigured),
    uptime_seconds: Math.max(0, Math.floor(Number(uptime()) || 0)),
  };
}

function defaultIdempotencyCleanupInterval(ttlMs) {
  const ttl = Number(ttlMs);
  if (!Number.isFinite(ttl) || ttl <= 0) {
    return 60 * 1000;
  }
  return Math.max(1000, Math.min(60 * 1000, Math.floor(ttl / 2)));
}

function handleError(res, error, request, requestContext, log) {
  if (res.writableEnded) {
    return;
  }
  if (error instanceof RequestIdConflictError) {
    requestContext.error_code = 'REQUEST_ID_CONFLICT';
    sendJson(res, 409, buildErrorResponse(
      request && request.session_id,
      request && request.request_id,
      'REQUEST_ID_CONFLICT',
      error.message,
    ));
    return;
  }
  if (error instanceof ApiError) {
    requestContext.error_code = error.code;
    sendJson(res, error.status, buildErrorResponse(
      request && request.session_id,
      request && request.request_id,
      error.code,
      error.message,
    ));
    return;
  }
  requestContext.error_code = 'INTERNAL_ERROR';
  log({
    level: 'error',
    event: 'unexpected_error',
    // 只记录错误消息（截断），绝不记录请求内容/密钥。
    message: String((error && error.message) || error).slice(0, 300),
  });
  sendJson(res, 500, buildErrorResponse(
    request && request.session_id,
    request && request.request_id,
    'INTERNAL_ERROR',
    '服务内部错误',
  ));
}

function setRateLimitHeaders(res, result) {
  res.setHeader('Retry-After', String(result.retryAfterSeconds));
  res.setHeader('X-RateLimit-Limit', String(result.limit));
  res.setHeader('X-RateLimit-Remaining', String(result.remaining));
  res.setHeader('X-RateLimit-Reset', String(Math.ceil(result.resetAtMs / 1000)));
}

function readJsonBody(req, maxBodyBytes = MAX_TEXT_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > maxBodyBytes) {
        const error = new ApiError(413, 'PAYLOAD_TOO_LARGE', '请求体超过大小限制');
        reject(error);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(new ApiError(400, 'INVALID_JSON', '请求体不是有效 JSON'));
      }
    });
    req.on('error', reject);
  });
}

function extractBearerToken(req) {
  const header = req.headers.authorization || '';
  return header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
}

function isAuthorized(req, expectedToken) {
  if (!expectedToken) {
    return false;
  }
  const header = req.headers.authorization || '';
  if (!header.startsWith('Bearer ')) {
    return false;
  }
  const actualToken = header.slice('Bearer '.length);
  const actual = Buffer.from(actualToken);
  const expected = Buffer.from(expectedToken);
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function getPathname(rawUrl) {
  const value = String(rawUrl || '/');
  const index = value.indexOf('?');
  const pathname = index >= 0 ? value.slice(0, index) : value;
  return pathname || '/';
}

function getHeaderValue(req, name) {
  const value = req.headers[name];
  if (Array.isArray(value)) {
    return String(value[0] || '').trim().slice(0, 128) || null;
  }
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim().slice(0, 128);
  return normalized || null;
}

function getClientIp(req, trustProxy) {
  if (trustProxy) {
    const forwarded = getHeaderValue(req, 'x-forwarded-for');
    if (forwarded) {
      return forwarded.split(',')[0].trim();
    }
  }
  return (req.socket && req.socket.remoteAddress) || 'unknown';
}

function setCommonHeaders(res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
}

function sendJson(res, status, payload) {
  if (res.writableEnded) {
    return;
  }
  res.writeHead(status);
  res.end(JSON.stringify(payload));
}

/**
 * SIGINT/SIGTERM 优雅关闭：先停止接收新连接，再清理周期定时器，最后退出。
 * 超时后强制断开连接并退出，避免长连接卡住部署滚动更新 / docker stop。
 */
function createShutdownHandler(options) {
  const {
    server,
    log = DEFAULT_LOGGER,
    exit = (code) => process.exit(code),
    timeoutMs = 10000,
  } = options;

  let shuttingDown = false;
  return function shutdown(signal = 'SIGTERM') {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    log({ level: 'info', event: 'shutdown_begin', signal });

    const forceTimer = setTimeout(() => {
      log({ level: 'warn', event: 'shutdown_forced', signal });
      if (typeof server.closeAllConnections === 'function') {
        try {
          server.closeAllConnections();
        } catch (error) {
          // 忽略：强制关闭阶段的清理失败不阻塞退出。
        }
      }
      exit(1);
    }, timeoutMs);
    if (typeof forceTimer.unref === 'function') {
      forceTimer.unref();
    }

    server.close((error) => {
      clearTimeout(forceTimer);
      if (typeof server.cleanup === 'function') {
        server.cleanup();
      }
      log({
        level: error ? 'error' : 'info',
        event: 'shutdown_complete',
        signal,
        error: error ? error.message : null,
      });
      exit(error ? 1 : 0);
    });
  };
}

function startFromEnvironment() {
  const port = Number(process.env.PORT || 3100);
  const host = process.env.HOST || '0.0.0.0';
  const riskApiToken = process.env.RISK_API_TOKEN || '';
  if (!riskApiToken) {
    throw new Error('缺少 RISK_API_TOKEN；请从 .env.example 创建 .env');
  }

  const rateLimitPerMinute = parsePerMinute(
    process.env.RATE_LIMIT_PER_MINUTE,
    DEFAULT_RATE_LIMIT_PER_MINUTE
  );
  const trustProxy = parseBoolean(process.env.TRUST_PROXY, false);
  const modelConfigured = Boolean(process.env.AI_API_KEY);
  const idempotencyTtlMs = Number(process.env.IDEMPOTENCY_TTL_MS || 600000);
  const idempotencyCleanupIntervalMs = process.env.IDEMPOTENCY_CLEANUP_INTERVAL_MS
    ? Number(process.env.IDEMPOTENCY_CLEANUP_INTERVAL_MS)
    : defaultIdempotencyCleanupInterval(idempotencyTtlMs);

  const analyzeText = createRiskAnalyzer({
    apiKey: process.env.AI_API_KEY || '',
    baseUrl: process.env.AI_BASE_URL || 'https://api.siliconflow.cn/v1',
    model: process.env.AI_MODEL || 'deepseek-ai/DeepSeek-V3.2',
    timeoutMs: Number(process.env.UPSTREAM_TIMEOUT_MS || 12000),
  });
  const analyzeScreen = createVisionRiskAnalyzer({
    apiKey: process.env.AI_API_KEY || '',
    baseUrl: process.env.AI_BASE_URL || 'https://api.siliconflow.cn/v1',
    model: process.env.AI_VISION_MODEL || 'Qwen/Qwen3-VL-8B-Instruct',
    timeoutMs: Number(process.env.VISION_TIMEOUT_MS || 10000),
  });
  const analyzeAudio = createAudioRiskAnalyzer({
    apiKey: process.env.AI_API_KEY || '',
    baseUrl: process.env.AI_BASE_URL || 'https://api.siliconflow.cn/v1',
    model: process.env.AI_ASR_MODEL || 'FunAudioLLM/SenseVoiceSmall',
    timeoutMs: Number(process.env.ASR_TIMEOUT_MS || 12000),
  }, analyzeText);
  const server = createRiskServer({
    analyzeText,
    analyzeScreen,
    analyzeAudio,
    riskApiToken,
    maxTextLength: Number(process.env.MAX_TEXT_LENGTH || 5000),
    idempotencyTtlMs,
    idempotencyCleanupIntervalMs,
    rateLimitPerMinute,
    trustProxy,
    modelConfigured,
  });

  server.on('error', (error) => {
    DEFAULT_LOGGER({
      level: 'error',
      event: 'server_error',
      message: String((error && error.message) || error).slice(0, 300),
    });
    process.exitCode = 1;
  });

  const shutdownTimeoutMs = Number(process.env.SHUTDOWN_TIMEOUT_MS || 10000);
  const shutdown = createShutdownHandler({
    server,
    log: DEFAULT_LOGGER,
    timeoutMs: shutdownTimeoutMs,
  });
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  server.listen(port, host, () => {
    // 启动配置摘要：只包含布尔/数值，不含任何密钥。
    DEFAULT_LOGGER({
      level: 'info',
      event: 'listening',
      service: SERVICE_NAME,
      version: SERVICE_VERSION,
      host,
      port,
      rate_limit_per_minute: rateLimitPerMinute,
      trust_proxy: trustProxy,
      model_configured: modelConfigured,
      idempotency_ttl_ms: idempotencyTtlMs,
    });
    console.log(`[thirdlevel-risk] listening on http://${host}:${port}`);
  });
  return server;
}

function parseBoolean(value, fallback = false) {
  if (value === undefined || value === null || `${value}`.trim() === '') {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].includes(`${value}`.trim().toLowerCase());
}

if (require.main === module) {
  try {
    startFromEnvironment();
  } catch (error) {
    DEFAULT_LOGGER({
      level: 'error',
      event: 'start_failed',
      message: String((error && error.message) || error).slice(0, 300),
    });
    process.exitCode = 1;
  }
}

module.exports = {
  SERVICE_NAME,
  SERVICE_VERSION,
  createRiskServer,
  createShutdownHandler,
  isAuthorized,
  readJsonBody,
  startFromEnvironment,
};

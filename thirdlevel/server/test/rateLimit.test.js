'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const {
  DEFAULT_RATE_LIMIT_PER_MINUTE,
  RateLimiter,
  parsePerMinute,
} = require('../rateLimiter');
const { postRisk, sampleRequest, startTestServer } = require('./helpers');

test('parsePerMinute 默认 60，0 关闭，非法值回退默认', () => {
  assert.equal(parsePerMinute(undefined), DEFAULT_RATE_LIMIT_PER_MINUTE);
  assert.equal(DEFAULT_RATE_LIMIT_PER_MINUTE, 60);
  assert.equal(parsePerMinute(''), 60);
  assert.equal(parsePerMinute('0'), 0);
  assert.equal(parsePerMinute('120'), 120);
  assert.equal(parsePerMinute(' 30 '), 30);
  assert.equal(parsePerMinute('-5'), 60);
  assert.equal(parsePerMinute('abc'), 60);
});

test('RateLimiter 固定窗口内放行 N 次后拒绝，窗口滚动后恢复', () => {
  let current = 1_000_000;
  const limiter = new RateLimiter({ perMinute: 2, now: () => current });
  const key = RateLimiter.buildKey('token-a', '10.0.0.1');

  assert.equal(limiter.consume(key).allowed, true);
  assert.equal(limiter.consume(key).allowed, true);
  const denied = limiter.consume(key);
  assert.equal(denied.allowed, false);
  assert.equal(denied.limit, 2);
  assert.equal(denied.remaining, 0);
  assert.ok(denied.retryAfterSeconds >= 1 && denied.retryAfterSeconds <= 60);

  current += 60_001;
  assert.equal(limiter.consume(key).allowed, true);
});

test('RateLimiter perMinute=0 时完全关闭且不写状态', () => {
  const limiter = new RateLimiter({ perMinute: 0 });
  assert.equal(limiter.enabled, false);
  for (let i = 0; i < 100; i += 1) {
    assert.equal(limiter.consume('any-key').allowed, true);
  }
  assert.equal(limiter.size, 0);
});

test('RateLimiter 按令牌+IP 分桶，且不保存明文令牌', () => {
  const keys = [
    RateLimiter.buildKey('token-a', '10.0.0.1'),
    RateLimiter.buildKey('token-a', '10.0.0.2'),
    RateLimiter.buildKey('token-b', '10.0.0.1'),
  ];
  assert.equal(new Set(keys).size, 3);
  for (const key of keys) {
    assert.equal(key.includes('token-a'), false);
    assert.equal(key.includes('token-b'), false);
  }
});

test('RateLimiter cleanup 清理过期桶并保留活跃桶', () => {
  let current = 1_000_000;
  const limiter = new RateLimiter({ perMinute: 5, now: () => current });
  limiter.consume('stale');
  current += 120_001;
  limiter.consume('fresh');
  limiter.cleanup();
  assert.equal(limiter.size, 1);
  assert.equal(limiter.buckets.has('fresh'), true);
});

test('超出 RATE_LIMIT_PER_MINUTE 返回 429 + RATE_LIMITED，且带限流响应头', async () => {
  const { baseUrl, close } = await startTestServer({ rateLimitPerMinute: 1 });
  try {
    const first = await postRisk(baseUrl, sampleRequest());
    assert.equal(first.status, 200);

    const limited = await postRisk(
      baseUrl,
      sampleRequest({ request_id: 'request-second' })
    );
    assert.equal(limited.status, 429);
    assert.match(limited.headers.get('retry-after') || '', /^\d+$/);
    assert.equal(limited.headers.get('x-ratelimit-limit'), '1');
    assert.equal(limited.headers.get('x-ratelimit-remaining'), '0');
    assert.match(limited.headers.get('x-ratelimit-reset') || '', /^\d+$/);

    const body = await limited.json();
    assert.equal(body.status, 'error');
    assert.equal(body.error_code, 'RATE_LIMITED');
    assert.equal(typeof body.message, 'string');
    assert.ok(body.message.length > 0);
    // 错误响应仍然不包含 risk_level（与既有契约一致）。
    assert.equal(Object.hasOwn(body, 'risk_level'), false);
    // 失败响应仍回填已知的 session_id / request_id。
    assert.equal(body.session_id, 'session-test');
    assert.equal(body.request_id, 'request-second');
  } finally {
    await close();
  }
});

test('幂等缓存命中优先于限流：配额用尽后重复请求仍返回 200 且不触发分析', async () => {
  const { baseUrl, calls, close } = await startTestServer({ rateLimitPerMinute: 1 });
  try {
    const first = await postRisk(baseUrl, sampleRequest());
    assert.equal(first.status, 200);
    assert.equal(calls.length, 1);

    const cached = await postRisk(baseUrl, sampleRequest());
    assert.equal(cached.status, 200);
    assert.equal(calls.length, 1);
    assert.deepEqual(await cached.json(), await first.json());
  } finally {
    await close();
  }
});

test('/health 不受限流影响', async () => {
  const { baseUrl, close } = await startTestServer({ rateLimitPerMinute: 1 });
  try {
    for (let i = 0; i < 5; i += 1) {
      const response = await fetch(`${baseUrl}/health`);
      assert.equal(response.status, 200);
    }
    // 如果 /health 消耗了配额，这里第一次分析就会被拒。
    const analysis = await postRisk(baseUrl, sampleRequest());
    assert.equal(analysis.status, 200);
  } finally {
    await close();
  }
});

test('RATE_LIMIT_PER_MINUTE=0 关闭限流', async () => {
  const { baseUrl, calls, close } = await startTestServer({ rateLimitPerMinute: 0 });
  try {
    for (let i = 0; i < 5; i += 1) {
      const response = await postRisk(baseUrl, sampleRequest({ request_id: `request-${i}` }));
      assert.equal(response.status, 200);
    }
    assert.equal(calls.length, 5);
  } finally {
    await close();
  }
});

test('无效请求与认证失败不消耗限流配额', async () => {
  const { baseUrl, close } = await startTestServer({ rateLimitPerMinute: 1 });
  try {
    const invalidBody = sampleRequest();
    invalidBody.text = '   ';
    const invalid = await postRisk(baseUrl, invalidBody);
    assert.equal(invalid.status, 400);

    const unauthorized = await postRisk(baseUrl, sampleRequest(), { token: null });
    assert.equal(unauthorized.status, 401);

    const valid = await postRisk(baseUrl, sampleRequest());
    assert.equal(valid.status, 200);
  } finally {
    await close();
  }
});

test('按客户端 IP 分桶（TRUST_PROXY=1 时使用 X-Forwarded-For 首位）', async () => {
  const { baseUrl, close } = await startTestServer({
    rateLimitPerMinute: 1,
    trustProxy: true,
  });
  try {
    const clientA = await postRisk(baseUrl, sampleRequest({ request_id: 'request-a' }), {
      headers: { 'X-Forwarded-For': '10.0.0.1, 172.16.0.1' },
    });
    assert.equal(clientA.status, 200);

    const clientB = await postRisk(baseUrl, sampleRequest({ request_id: 'request-b' }), {
      headers: { 'X-Forwarded-For': '10.0.0.2' },
    });
    assert.equal(clientB.status, 200);

    const clientAAgain = await postRisk(
      baseUrl,
      sampleRequest({ request_id: 'request-a2' }),
      { headers: { 'X-Forwarded-For': '10.0.0.1' } }
    );
    assert.equal(clientAAgain.status, 429);
  } finally {
    await close();
  }
});

test('窗口滚动后恢复配额', async () => {
  let current = 1_000_000;
  const { baseUrl, close } = await startTestServer({
    rateLimitPerMinute: 1,
    now: () => current,
  });
  try {
    assert.equal((await postRisk(baseUrl, sampleRequest({ request_id: 'request-1' }))).status, 200);
    assert.equal((await postRisk(baseUrl, sampleRequest({ request_id: 'request-2' }))).status, 429);
    current += 60_001;
    assert.equal((await postRisk(baseUrl, sampleRequest({ request_id: 'request-3' }))).status, 200);
  } finally {
    await close();
  }
});

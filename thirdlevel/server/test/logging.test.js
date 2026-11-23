'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const {
  postRisk,
  sampleRequest,
  startTestServer,
  waitFor,
} = require('./helpers');

function findRequestLogs(logs) {
  return logs.filter((record) => record.event === 'request');
}

test('成功请求输出结构化日志：request_id/source/status/risk_level/耗时', async () => {
  const { baseUrl, logs, close } = await startTestServer();
  try {
    assert.equal((await postRisk(baseUrl, sampleRequest())).status, 200);
    assert.ok(await waitFor(() => findRequestLogs(logs).length === 1));

    const record = findRequestLogs(logs)[0];
    assert.equal(record.level, 'info');
    assert.equal(record.method, 'POST');
    assert.equal(record.path, '/api/v1/risk/text');
    assert.equal(record.request_id, 'request-test');
    assert.equal(record.source, 'ocr');
    assert.equal(record.status, 200);
    assert.equal(record.risk_level, 'high');
    assert.equal(record.error_code, null);
    assert.equal(typeof record.duration_ms, 'number');
    assert.ok(record.duration_ms >= 0);
    assert.equal(typeof record.ts, 'string');
    assert.ok(!Number.isNaN(Date.parse(record.ts)));
  } finally {
    await close();
  }
});

test('错误请求日志包含 error_code（401/404/429）', async () => {
  const { baseUrl, logs, close } = await startTestServer({ rateLimitPerMinute: 1 });
  try {
    await postRisk(baseUrl, sampleRequest(), { token: null });
    await postRisk(baseUrl, sampleRequest({ request_id: 'r-ok' }));
    await postRisk(baseUrl, sampleRequest({ request_id: 'r-limited' }));

    const unauthorized = await fetch(`${baseUrl}/api/v1/risk/text`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    assert.equal(unauthorized.status, 401);
    const missing = await fetch(`${baseUrl}/api/v1/risk/unknown`, { method: 'POST' });
    assert.equal(missing.status, 404);

    assert.ok(await waitFor(() => findRequestLogs(logs).length === 5));
    const byStatus = new Map(findRequestLogs(logs).map((r) => [r.status, r]));
    assert.equal(byStatus.get(401).error_code, 'UNAUTHORIZED');
    assert.equal(byStatus.get(404).error_code, 'NOT_FOUND');
    assert.equal(byStatus.get(429).error_code, 'RATE_LIMITED');
  } finally {
    await close();
  }
});

test('日志不包含 OCR 文本、令牌或 Authorization 头', async () => {
  const { baseUrl, logs, close } = await startTestServer();
  try {
    const request = sampleRequest({
      text: '这是一段不应出现在日志里的转账文本-UNIQUE_MARKER',
    });
    assert.equal((await postRisk(baseUrl, request)).status, 200);
    await waitFor(() => findRequestLogs(logs).length > 0);

    const serialized = JSON.stringify(logs);
    assert.equal(serialized.includes('UNIQUE_MARKER'), false);
    assert.equal(serialized.includes('这是一段不应出现在日志里'), false);
    assert.equal(serialized.includes('unit-test-token'), false);
    assert.equal(serialized.toLowerCase().includes('authorization'), false);
  } finally {
    await close();
  }
});

test('每次新分析输出 analysis_start 日志，但不记录分析内容', async () => {
  const { baseUrl, logs, close } = await startTestServer();
  try {
    await postRisk(baseUrl, sampleRequest());
    assert.ok(await waitFor(() => logs.some((r) => r.event === 'analysis_start')));
    const start = logs.find((r) => r.event === 'analysis_start');
    assert.equal(start.request_id, 'request-test');
    assert.equal(start.source, 'ocr');
    assert.equal(start.kind, 'text');
    assert.equal(JSON.stringify(start).includes('转账'), false);
  } finally {
    await close();
  }
});

test('/health 请求也会留下结构化日志（status=200）', async () => {
  const { baseUrl, logs, close } = await startTestServer();
  try {
    await fetch(`${baseUrl}/health`);
    assert.ok(await waitFor(() => findRequestLogs(logs).length === 1));
    const record = findRequestLogs(logs)[0];
    assert.equal(record.path, '/health');
    assert.equal(record.status, 200);
    assert.equal(record.risk_level, null);
  } finally {
    await close();
  }
});

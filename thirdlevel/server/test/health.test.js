'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const packageInfo = require('../package.json');
const { startTestServer, TOKEN } = require('./helpers');

test('/health 保留 ok/service 并增量返回 version/model_configured/uptime_seconds', async () => {
  const { baseUrl, close } = await startTestServer();
  try {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
    const body = await response.json();
    // 向后兼容字段（Android 客户端只读 ok）。
    assert.equal(body.ok, true);
    assert.equal(body.service, 'thirdlevel-risk-service');
    // 新增字段。
    assert.equal(body.version, packageInfo.version);
    assert.equal(body.model_configured, false);
    assert.equal(typeof body.uptime_seconds, 'number');
    assert.ok(body.uptime_seconds >= 0);
  } finally {
    await close();
  }
});

test('/health 不要求认证', async () => {
  const { baseUrl, close } = await startTestServer();
  try {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
  } finally {
    await close();
  }
});

test('/health model_configured 只暴露布尔值且不泄露密钥', async () => {
  const { baseUrl, close } = await startTestServer({ modelConfigured: true });
  try {
    const response = await fetch(`${baseUrl}/health`);
    const body = await response.json();
    assert.equal(body.model_configured, true);
    const serialized = JSON.stringify(body).toLowerCase();
    assert.equal(serialized.includes('api_key'), false);
    assert.equal(serialized.includes('apikey'), false);
    assert.equal(/sk-[a-z0-9]{16,}/.test(serialized), false);
    assert.equal(serialized.includes(TOKEN), false);
  } finally {
    await close();
  }
});

test('/health uptime_seconds 取整且可注入（便于测试）', async () => {
  const { baseUrl, close } = await startTestServer({ uptime: () => 123.9 });
  try {
    const body = await (await fetch(`${baseUrl}/health`)).json();
    assert.equal(body.uptime_seconds, 123);
  } finally {
    await close();
  }
});

test('/health 响应头仍是 no-store JSON', async () => {
  const { baseUrl, close } = await startTestServer();
  try {
    const response = await fetch(`${baseUrl}/health`);
    assert.match(response.headers.get('content-type') || '', /application\/json/);
    assert.equal(response.headers.get('cache-control'), 'no-store');
  } finally {
    await close();
  }
});

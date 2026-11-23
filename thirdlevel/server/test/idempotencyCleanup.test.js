'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { IdempotencyStore } = require('../idempotencyStore');
const {
  postRisk,
  sampleRequest,
  startTestServer,
  waitFor,
} = require('./helpers');

function completedStore(ttlMs) {
  const store = new IdempotencyStore(ttlMs);
  const ownership = store.begin('request-1', 'fingerprint-1');
  assert.equal(ownership.kind, 'owner');
  store.complete('request-1', { status: 'success' });
  return store;
}

test('IdempotencyStore.cleanup 删除已过期的完成项', () => {
  const store = completedStore(10);
  assert.equal(store.size, 1);
  store.cleanup(Date.now() + 5);
  assert.equal(store.size, 1);
  store.cleanup(Date.now() + 60_000);
  assert.equal(store.size, 0);
});

test('IdempotencyStore.cleanup 不删除处理中的项', () => {
  const store = new IdempotencyStore(10);
  store.begin('request-pending', 'fingerprint-1');
  store.cleanup(Date.now() + 60_000);
  assert.equal(store.size, 1);
  // 收尾，避免悬挂 Promise。
  store.fail('request-pending', new Error('cleanup test'));
});

test('服务端周期性清理幂等缓存（idempotencyCleanupIntervalMs）', async () => {
  const { baseUrl, server, close } = await startTestServer({
    idempotencyTtlMs: 10,
    idempotencyCleanupIntervalMs: 10,
  });
  try {
    assert.equal((await postRisk(baseUrl, sampleRequest())).status, 200);
    assert.equal(server.idempotencyStore.size, 1);
    assert.ok(await waitFor(() => server.idempotencyStore.size === 0, 2000));
  } finally {
    await close();
  }
});

test('server.cleanup 幂等且可重复调用', () => {
  // 通过 startTestServer 不可用时退化为 pure createRiskServer 检查。
  const { createRiskServer } = require('../server');
  const server = createRiskServer({
    analyzeText: async () => ({ risk_level: 'none', evidence: '', reason: '' }),
    riskApiToken: 'cleanup-token',
    log: () => {},
    idempotencyCleanupIntervalMs: 10,
  });
  try {
    assert.equal(typeof server.cleanup, 'function');
    server.cleanup();
    server.cleanup();
  } finally {
    server.close();
  }
});

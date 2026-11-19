'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createRiskServer, createShutdownHandler } = require('../server');
const { sampleAnalysis, waitFor } = require('./helpers');

function createFakeServer(options = {}) {
  const { closeError = null, closeBehavior = 'callback' } = options;
  const state = { closeCalls: 0, cleaned: 0 };
  return {
    state,
    server: {
      close(callback) {
        state.closeCalls += 1;
        if (closeBehavior === 'never') {
          return;
        }
        callback(closeError);
      },
      cleanup() {
        state.cleaned += 1;
      },
    },
  };
}

function createExitRecorder() {
  const codes = [];
  let resolveExit;
  const exited = new Promise((resolve) => {
    resolveExit = resolve;
  });
  return {
    codes,
    exited,
    exit: (code) => {
      codes.push(code);
      resolveExit();
    },
  };
}

test('SIGTERM：关闭 server、清理 timer，并以 0 退出', async () => {
  const logs = [];
  const { server, state } = createFakeServer();
  const recorder = createExitRecorder();
  const shutdown = createShutdownHandler({
    server,
    log: (record) => logs.push(record),
    exit: recorder.exit,
  });

  shutdown('SIGTERM');
  await recorder.exited;

  assert.deepEqual(recorder.codes, [0]);
  assert.equal(state.closeCalls, 1);
  assert.equal(state.cleaned, 1);
  assert.equal(logs[0].event, 'shutdown_begin');
  assert.equal(logs[0].signal, 'SIGTERM');
  assert.equal(logs.at(-1).event, 'shutdown_complete');
  assert.equal(logs.at(-1).error, null);
});

test('重复信号只执行一次关闭', async () => {
  const logs = [];
  const { server, state } = createFakeServer();
  const recorder = createExitRecorder();
  const shutdown = createShutdownHandler({
    server,
    log: (record) => logs.push(record),
    exit: recorder.exit,
  });

  shutdown('SIGINT');
  shutdown('SIGINT');
  shutdown('SIGTERM');
  await recorder.exited;
  await new Promise((resolve) => setTimeout(resolve, 20));

  assert.equal(state.closeCalls, 1);
  assert.deepEqual(recorder.codes, [0]);
  assert.equal(logs.filter((record) => record.event === 'shutdown_begin').length, 1);
});

test('关闭过程报错时以 1 退出并记录 error', async () => {
  const logs = [];
  const { server } = createFakeServer({ closeError: new Error('close failed') });
  const recorder = createExitRecorder();
  const shutdown = createShutdownHandler({
    server,
    log: (record) => logs.push(record),
    exit: recorder.exit,
  });

  shutdown('SIGTERM');
  await recorder.exited;

  assert.deepEqual(recorder.codes, [1]);
  const last = logs.at(-1);
  assert.equal(last.event, 'shutdown_complete');
  assert.equal(last.level, 'error');
  assert.equal(last.error, 'close failed');
});

test('关闭超时后强制断开连接并以 1 退出', async () => {
  const logs = [];
  const { server } = createFakeServer({ closeBehavior: 'never' });
  const recorder = createExitRecorder();
  const shutdown = createShutdownHandler({
    server,
    log: (record) => logs.push(record),
    exit: recorder.exit,
    timeoutMs: 10,
  });

  shutdown('SIGTERM');
  await recorder.exited;

  assert.deepEqual(recorder.codes, [1]);
  assert.equal(logs.some((record) => record.event === 'shutdown_forced'), true);
});

test('真实 http server 优雅关闭后不再监听且限流 timer 已清理', async () => {
  const logs = [];
  const server = createRiskServer({
    analyzeText: async () => sampleAnalysis(),
    riskApiToken: 'shutdown-token',
    log: () => {},
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));

  const shutdown = createShutdownHandler({
    server,
    log: (record) => logs.push(record),
    exit: () => {},
  });
  shutdown('SIGINT');

  assert.ok(await waitFor(() => logs.some((record) => record.event === 'shutdown_complete')));
  assert.equal(server.listening, false);
  assert.equal(server.rateLimiter.timer, null);
});

'use strict';

const { createRiskServer } = require('../server');

const TOKEN = 'unit-test-token';

function sampleAnalysis(overrides = {}) {
  return {
    risk_level: 'high',
    evidence: '请先转账到指定账户',
    reason: '要求转账并承诺收益',
    ...overrides,
  };
}

function sampleRequest(overrides = {}) {
  return {
    session_id: 'session-test',
    request_id: 'request-test',
    source: 'ocr',
    captured_at: '2026-11-07T10:30:00+08:00',
    text: '请先转账到指定账户，保证收益',
    ...overrides,
  };
}

function sampleWav() {
  const audio = Buffer.alloc(128);
  audio.write('RIFF', 0, 'ascii');
  audio.writeUInt32LE(120, 4);
  audio.write('WAVEfmt ', 8, 'ascii');
  audio.writeUInt32LE(16, 16);
  audio.writeUInt16LE(1, 20);
  audio.writeUInt16LE(1, 22);
  audio.writeUInt32LE(16000, 24);
  audio.writeUInt32LE(32000, 28);
  audio.writeUInt16LE(2, 32);
  audio.writeUInt16LE(16, 34);
  audio.write('data', 36, 'ascii');
  audio.writeUInt32LE(84, 40);
  return audio;
}

/**
 * 启动一个带内存日志收集器的测试服务（随机端口）。
 * 返回 close() 用于关闭；logs 收集结构化日志记录。
 */
async function startTestServer(overrides = {}) {
  const calls = [];
  const logs = [];
  const options = {
    analyzeText: async (request) => {
      calls.push(request);
      return sampleAnalysis();
    },
    riskApiToken: TOKEN,
    log: (record) => logs.push(record),
    ...overrides,
  };
  const server = createRiskServer(options);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    server,
    baseUrl: `http://127.0.0.1:${port}`,
    calls,
    logs,
    close: () => new Promise((resolve) => {
      server.cleanup();
      server.close(resolve);
    }),
  };
}

function postRisk(baseUrl, body, options = {}) {
  return postJson(`${baseUrl}/api/v1/risk/text`, body, options);
}

function postJson(url, body, options = {}) {
  const {
    token = TOKEN,
    headers: extraHeaders = {},
  } = options;
  const headers = { 'Content-Type': 'application/json', ...extraHeaders };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
}

async function waitFor(predicate, timeoutMs = 2000, intervalMs = 10) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return predicate();
}

module.exports = {
  TOKEN,
  postJson,
  postRisk,
  sampleAnalysis,
  sampleRequest,
  sampleWav,
  startTestServer,
  waitFor,
};

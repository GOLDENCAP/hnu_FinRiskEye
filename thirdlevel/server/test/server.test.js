'use strict';

const assert = require('node:assert/strict');
const { afterEach, test } = require('node:test');
const { createRiskServer } = require('../server');

const TOKEN = 'unit-test-token';
const openServers = new Set();

afterEach(async () => {
  await Promise.all([...openServers].map((server) => new Promise((resolve) => {
    server.close(resolve);
  })));
  openServers.clear();
});

test('health endpoint does not require authentication', async () => {
  const { baseUrl } = await startTestServer(async () => sampleAnalysis());
  const response = await fetch(`${baseUrl}/health`);
  assert.equal(response.status, 200);
  const body = await response.json();
  // 原有字段保持不变，新增字段只做增量（见 test/health.test.js）。
  assert.equal(body.ok, true);
  assert.equal(body.service, 'thirdlevel-risk-service');
  assert.equal(typeof body.version, 'string');
  assert.equal(typeof body.model_configured, 'boolean');
  assert.equal(typeof body.uptime_seconds, 'number');
});

test('text endpoint rejects missing bearer token', async () => {
  const { baseUrl } = await startTestServer(async () => sampleAnalysis());
  const response = await postRisk(baseUrl, sampleRequest(), null);
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error_code, 'UNAUTHORIZED');
});

test('text endpoint returns normalized success response', async () => {
  const { baseUrl, calls } = await startTestServer(async () => sampleAnalysis());
  const response = await postRisk(baseUrl, sampleRequest());
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.status, 'success');
  assert.equal(body.risk_level, 'high');
  assert.equal(body.session_id, 'session-test');
  assert.equal(body.request_id, 'request-test');
  assert.equal(calls.length, 1);
});

test('text endpoint rejects invalid text', async () => {
  const { baseUrl, calls } = await startTestServer(async () => sampleAnalysis());
  const request = sampleRequest();
  request.text = '   ';
  const response = await postRisk(baseUrl, request);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error_code, 'INVALID_REQUEST');
  assert.equal(calls.length, 0);
});

test('upstream timeout is surfaced without risk_level', async () => {
  const error = Object.assign(new Error('timeout'), {
    status: 504,
    code: 'UPSTREAM_TIMEOUT',
  });
  const { baseUrl } = await startTestServer(async () => {
    const { ApiError } = require('../contract');
    throw new ApiError(error.status, error.code, '风险分析超时');
  });
  const response = await postRisk(baseUrl, sampleRequest());
  assert.equal(response.status, 504);
  const body = await response.json();
  assert.equal(body.error_code, 'UPSTREAM_TIMEOUT');
  assert.equal(Object.hasOwn(body, 'risk_level'), false);
});

test('same request id and payload are analyzed only once', async () => {
  const { baseUrl, calls } = await startTestServer(async () => sampleAnalysis());
  const first = await postRisk(baseUrl, sampleRequest());
  const second = await postRisk(baseUrl, sampleRequest());
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  assert.deepEqual(await first.json(), await second.json());
  assert.equal(calls.length, 1);
});

test('same request id with different text is rejected', async () => {
  const { baseUrl } = await startTestServer(async () => sampleAnalysis());
  assert.equal((await postRisk(baseUrl, sampleRequest())).status, 200);
  const changed = sampleRequest();
  changed.text = '这是另一段文字';
  const response = await postRisk(baseUrl, changed);
  assert.equal(response.status, 409);
  assert.equal((await response.json()).error_code, 'REQUEST_ID_CONFLICT');
});

test('screen endpoint accepts JPEG and returns high risk', async () => {
  const screenCalls = [];
  const { baseUrl } = await startTestServer(
    async () => sampleAnalysis(),
    async (request) => {
      screenCalls.push(request);
      return sampleAnalysis();
    }
  );
  const response = await postJson(`${baseUrl}/api/v1/risk/screen`, {
    session_id: 'session-screen',
    request_id: 'request-screen',
    captured_at: '2026-11-07T10:30:00+08:00',
    image_base64: Buffer.from([0xff, 0xd8, 0xff, 0xd9]).toString('base64'),
    ocr_text: '转账到这个账号',
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.risk_level, 'high');
  assert.equal(screenCalls.length, 1);
  assert.equal(screenCalls[0].source, 'screen');
});

test('screen endpoint rejects non-JPEG data', async () => {
  const { baseUrl } = await startTestServer(
    async () => sampleAnalysis(),
    async () => sampleAnalysis()
  );
  const response = await postJson(`${baseUrl}/api/v1/risk/screen`, {
    session_id: 'session-screen',
    request_id: 'request-invalid',
    captured_at: '2026-11-07T10:30:00+08:00',
    image_base64: Buffer.from('not-jpeg').toString('base64'),
  });
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error_code, 'INVALID_REQUEST');
});

test('audio endpoint accepts WAV and returns transcription risk', async () => {
  const audioCalls = [];
  const { baseUrl } = await startTestServer(
    async () => sampleAnalysis(),
    async () => sampleAnalysis(),
    async (request) => {
      audioCalls.push(request);
      return { ...sampleAnalysis(), transcript: '请把资金转入安全账户' };
    }
  );
  const response = await postJson(`${baseUrl}/api/v1/risk/audio`, {
    session_id: 'session-audio',
    request_id: 'request-audio',
    captured_at: '2026-11-08T10:30:00+08:00',
    duration_ms: 10000,
    audio_base64: makeWav().toString('base64'),
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.risk_level, 'high');
  assert.equal(body.transcript, '请把资金转入安全账户');
  assert.equal(audioCalls.length, 1);
});

test('audio endpoint rejects invalid WAV data', async () => {
  const { baseUrl } = await startTestServer(
    async () => sampleAnalysis(),
    async () => sampleAnalysis(),
    async () => sampleAnalysis()
  );
  const response = await postJson(`${baseUrl}/api/v1/risk/audio`, {
    session_id: 'session-audio',
    request_id: 'request-invalid-audio',
    captured_at: '2026-11-08T10:30:00+08:00',
    duration_ms: 1000,
    audio_base64: Buffer.from('not-wave').toString('base64'),
  });
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error_code, 'INVALID_REQUEST');
});

function sampleAnalysis() {
  return {
    risk_level: 'high',
    evidence: '请先转账到指定账户',
    reason: '要求转账并承诺收益',
  };
}

function sampleRequest() {
  return {
    session_id: 'session-test',
    request_id: 'request-test',
    source: 'ocr',
    captured_at: '2026-11-07T10:30:00+08:00',
    text: '请先转账到指定账户，保证收益',
  };
}

async function startTestServer(analyzeText, analyzeScreen = null, analyzeAudio = null) {
  const calls = [];
  const wrappedAnalyzer = async (request) => {
    calls.push(request);
    return analyzeText(request);
  };
  const server = createRiskServer({
    analyzeText: wrappedAnalyzer,
    analyzeScreen,
    analyzeAudio,
    riskApiToken: TOKEN,
  });
  openServers.add(server);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    calls,
  };
}

function makeWav() {
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

function postRisk(baseUrl, body, token = TOKEN) {
  return postJson(`${baseUrl}/api/v1/risk/text`, body, token);
}

function postJson(url, body, token = TOKEN) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
}

'use strict';

const RISK_LEVELS = new Set(['none', 'low', 'medium', 'high']);
const TEXT_SOURCE_TYPES = new Set(['ocr', 'audio']);
const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
const MAX_AUDIO_BYTES = 4 * 1024 * 1024;

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

function requireString(body, field, maxLength) {
  const value = body[field];
  if (typeof value !== 'string' || !value.trim()) {
    throw new ApiError(400, 'INVALID_REQUEST', `${field} 必须是非空字符串`);
  }
  const normalized = value.trim();
  if (maxLength && normalized.length > maxLength) {
    throw new ApiError(400, 'INVALID_REQUEST', `${field} 超过最大长度 ${maxLength}`);
  }
  return normalized;
}

function validateTextRequest(body, maxTextLength) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new ApiError(400, 'INVALID_REQUEST', '请求体必须是 JSON 对象');
  }

  const sessionId = requireString(body, 'session_id', 128);
  const requestId = requireString(body, 'request_id', 128);
  const source = requireString(body, 'source', 16);
  const capturedAt = requireString(body, 'captured_at', 64);
  const text = requireString(body, 'text', maxTextLength);

  if (!TEXT_SOURCE_TYPES.has(source)) {
    throw new ApiError(400, 'INVALID_REQUEST', 'source 暂时只支持 ocr 或 audio');
  }
  if (Number.isNaN(Date.parse(capturedAt))) {
    throw new ApiError(400, 'INVALID_REQUEST', 'captured_at 必须是可解析的 ISO 8601 时间');
  }

  return {
    session_id: sessionId,
    request_id: requestId,
    source,
    captured_at: capturedAt,
    text,
  };
}

function validateScreenRequest(body, maxTextLength) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new ApiError(400, 'INVALID_REQUEST', '请求体必须是 JSON 对象');
  }

  const sessionId = requireString(body, 'session_id', 128);
  const requestId = requireString(body, 'request_id', 128);
  const capturedAt = requireString(body, 'captured_at', 64);
  if (Number.isNaN(Date.parse(capturedAt))) {
    throw new ApiError(400, 'INVALID_REQUEST', 'captured_at 必须是可解析的 ISO 8601 时间');
  }

  const imageBase64 = requireString(body, 'image_base64', Math.ceil(MAX_IMAGE_BYTES * 4 / 3) + 128);
  const encoded = imageBase64.replace(/^data:image\/jpeg;base64,/i, '');
  if (!/^[A-Za-z0-9+/=\r\n]+$/.test(encoded)) {
    throw new ApiError(400, 'INVALID_REQUEST', 'image_base64 不是有效 Base64');
  }
  const imageBuffer = Buffer.from(encoded, 'base64');
  if (imageBuffer.length < 4
      || imageBuffer[0] !== 0xff
      || imageBuffer[1] !== 0xd8
      || imageBuffer[2] !== 0xff) {
    throw new ApiError(400, 'INVALID_REQUEST', '只支持 JPEG 图片');
  }
  if (imageBuffer.length > MAX_IMAGE_BYTES) {
    throw new ApiError(413, 'PAYLOAD_TOO_LARGE', '图片超过 6 MB');
  }

  const ocrText = typeof body.ocr_text === 'string'
    ? body.ocr_text.trim().slice(0, maxTextLength)
    : '';
  return {
    session_id: sessionId,
    request_id: requestId,
    source: 'screen',
    captured_at: capturedAt,
    ocr_text: ocrText,
    image_buffer: imageBuffer,
  };
}

function validateAudioRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new ApiError(400, 'INVALID_REQUEST', '请求体必须是 JSON 对象');
  }

  const sessionId = requireString(body, 'session_id', 128);
  const requestId = requireString(body, 'request_id', 128);
  const capturedAt = requireString(body, 'captured_at', 64);
  if (Number.isNaN(Date.parse(capturedAt))) {
    throw new ApiError(400, 'INVALID_REQUEST', 'captured_at 必须是可解析的 ISO 8601 时间');
  }

  const audioBase64 = requireString(
    body,
    'audio_base64',
    Math.ceil(MAX_AUDIO_BYTES * 4 / 3) + 128
  );
  const encoded = audioBase64.replace(/^data:audio\/wav;base64,/i, '');
  if (!/^[A-Za-z0-9+/=\r\n]+$/.test(encoded)) {
    throw new ApiError(400, 'INVALID_REQUEST', 'audio_base64 不是有效 Base64');
  }
  const audioBuffer = Buffer.from(encoded, 'base64');
  if (audioBuffer.length < 44
      || audioBuffer.subarray(0, 4).toString('ascii') !== 'RIFF'
      || audioBuffer.subarray(8, 12).toString('ascii') !== 'WAVE') {
    throw new ApiError(400, 'INVALID_REQUEST', '只支持 WAV 音频');
  }
  if (audioBuffer.length > MAX_AUDIO_BYTES) {
    throw new ApiError(413, 'PAYLOAD_TOO_LARGE', '音频超过 4 MB');
  }

  return {
    session_id: sessionId,
    request_id: requestId,
    source: 'audio',
    captured_at: capturedAt,
    duration_ms: Number.isFinite(Number(body.duration_ms))
      ? Math.max(0, Number(body.duration_ms))
      : 0,
    audio_buffer: audioBuffer,
  };
}

function buildSuccessResponse(request, analysis, analyzedAt = new Date().toISOString()) {
  if (!analysis || !RISK_LEVELS.has(analysis.risk_level)) {
    throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回了无效的风险等级');
  }

  const response = {
    session_id: request.session_id,
    request_id: request.request_id,
    status: 'success',
    risk_level: analysis.risk_level,
    evidence: String(analysis.evidence || '').trim().slice(0, 500),
    reason: String(analysis.reason || '').trim().slice(0, 500),
    analyzed_at: analyzedAt,
  };
  if (typeof analysis.transcript === 'string' && analysis.transcript.trim()) {
    response.transcript = analysis.transcript.trim().slice(0, 2000);
  }
  return response;
}

function buildErrorResponse(sessionId, requestId, code, message) {
  return {
    session_id: sessionId || '',
    request_id: requestId || '',
    status: 'error',
    error_code: code,
    message,
  };
}

function requestFingerprint(request) {
  return JSON.stringify([
    request.session_id,
    request.source,
    request.captured_at,
    request.text,
  ]);
}

module.exports = {
  ApiError,
  RISK_LEVELS,
  buildErrorResponse,
  buildSuccessResponse,
  requestFingerprint,
  validateAudioRequest,
  validateScreenRequest,
  validateTextRequest,
};

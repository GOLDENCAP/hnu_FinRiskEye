'use strict';

const { ApiError } = require('./contract');
const crypto = require('crypto');

const RISK_PROMPT = `你是金融反诈骗风险分析器。下面是从用户主动授权的屏幕中识别出的一段文字。
请只依据文字内容，判断其中是否存在金融诈骗风险。

重点识别：
1. 要求转账到“安全账户”、核查账户或陌生账户；
2. 冒充公安、检察院、法院、银行或客服要求配合调查；
3. 承诺高收益、稳赚不赔、内部消息；
4. 索要验证码、密码、身份证、银行卡信息；
5. 要求安装远程控制软件或共享屏幕；
6. 要求保密、不要告诉家人或催促立即操作。

没有明显风险时返回 none。文字可能是 OCR 结果，可能存在少量错字和断行，不要因为排版问题直接判定风险。

只返回一个 JSON 对象，禁止 Markdown 和额外说明：
{
  "risk_level": "none|low|medium|high",
  "evidence": "触发风险的关键原文，没有风险时可为空字符串",
  "reason": "简短中文解释"
}

待分析文字：
`;

function createRiskAnalyzer(config, fetchImpl = globalThis.fetch) {
  const {
    apiKey,
    baseUrl,
    model,
    timeoutMs = 12000,
  } = config;

  return async function analyzeText(request) {
    if (!apiKey) {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '风险分析服务未配置模型密钥');
    }
    if (typeof fetchImpl !== 'function') {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '当前 Node 运行时不支持 fetch');
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let response;
    try {
      response = await fetchImpl(`${trimTrailingSlash(baseUrl)}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          model,
          temperature: 0.1,
          max_tokens: 400,
          stream: false,
          messages: [
            {
              role: 'user',
              content: RISK_PROMPT + request.text,
            },
          ],
        }),
        signal: controller.signal,
      });
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError(504, 'UPSTREAM_TIMEOUT', '风险分析超时');
      }
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '风险分析暂时不可用');
    } finally {
      clearTimeout(timer);
    }

    let responseBody;
    try {
      responseBody = await response.json();
    } catch (error) {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回了非 JSON 响应');
    }

    if (!response.ok) {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '风险模型服务暂时不可用');
    }

    const content = extractMessageContent(responseBody);
    const jsonText = extractJsonObject(content);
    if (!jsonText) {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型未返回结果 JSON');
    }

    let parsed;
    try {
      parsed = JSON.parse(jsonText);
    } catch (error) {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回的 JSON 无法解析');
    }

    if (!['none', 'low', 'medium', 'high'].includes(parsed.risk_level)) {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回了未知风险等级');
    }
    if (typeof parsed.evidence !== 'string' || typeof parsed.reason !== 'string') {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型结果缺少 evidence 或 reason');
    }

    return {
      risk_level: parsed.risk_level,
      evidence: parsed.evidence.trim(),
      reason: parsed.reason.trim(),
    };
  };
}

function createVisionRiskAnalyzer(config, fetchImpl = globalThis.fetch) {
  const {
    apiKey,
    baseUrl,
    model,
    timeoutMs = 15000,
  } = config;

  return async function analyzeScreen(request) {
    if (!apiKey) {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '画面风险服务未配置模型密钥');
    }
    if (typeof fetchImpl !== 'function') {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '当前 Node 运行时不支持 fetch');
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const imageDataUrl = `data:image/jpeg;base64,${request.image_buffer.toString('base64')}`;
    let response;
    try {
      response = await fetchImpl(`${trimTrailingSlash(baseUrl)}/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          model,
          temperature: 0.1,
          max_tokens: 450,
          stream: false,
          messages: [
            {
              role: 'user',
              content: [
                {
                  type: 'text',
                  text: VISION_RISK_PROMPT
                    + `\nOCR辅助文字：${request.ocr_text || '无'}`,
                },
                {
                  type: 'image_url',
                  image_url: {
                    url: imageDataUrl,
                    detail: 'low',
                  },
                },
              ],
            },
          ],
        }),
        signal: controller.signal,
      });
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError(504, 'UPSTREAM_TIMEOUT', '画面风险分析超时');
      }
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '画面风险分析暂时不可用');
    } finally {
      clearTimeout(timer);
    }

    let responseBody;
    try {
      responseBody = await response.json();
    } catch (error) {
      throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '视觉模型返回了非 JSON 响应');
    }
    if (!response.ok) {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '视觉模型服务暂时不可用');
    }
    return validateModelAnalysis(extractMessageContent(responseBody));
  };
}

function createAudioRiskAnalyzer(config, analyzeText, fetchImpl = globalThis.fetch) {
  const {
    apiKey,
    baseUrl,
    model,
    timeoutMs = 12000,
  } = config;

  if (typeof analyzeText !== 'function') {
    throw new Error('createAudioRiskAnalyzer 需要 analyzeText');
  }

  return async function analyzeAudio(request) {
    if (!apiKey) {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '语音识别服务未配置模型密钥');
    }
    if (typeof fetchImpl !== 'function') {
      throw new ApiError(503, 'ANALYSIS_UNAVAILABLE', '当前 Node 运行时不支持 fetch');
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const form = new FormData();
    form.append('model', model);
    form.append('language', 'zh');
    form.append(
      'file',
      new Blob([request.audio_buffer], { type: 'audio/wav' }),
      'screen-audio.wav'
    );

    let response;
    try {
      response = await fetchImpl(
        `${trimTrailingSlash(baseUrl)}/audio/transcriptions`,
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${apiKey}`,
          },
          body: form,
          signal: controller.signal,
        }
      );
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new ApiError(504, 'ASR_TIMEOUT', '语音转写超时');
      }
      throw new ApiError(503, 'ASR_UNAVAILABLE', '语音转写服务暂时不可用');
    } finally {
      clearTimeout(timer);
    }

    let responseBody;
    try {
      responseBody = await response.json();
    } catch (error) {
      throw new ApiError(502, 'ASR_INVALID_RESPONSE', '语音服务返回了非 JSON 响应');
    }
    if (!response.ok) {
      throw new ApiError(503, 'ASR_UNAVAILABLE', '语音转写服务暂时不可用');
    }

    const transcript = typeof responseBody.text === 'string'
      ? responseBody.text.trim()
      : '';
    if (!transcript) {
      throw new ApiError(422, 'TRANSCRIPTION_EMPTY', '没有识别出可分析的语音');
    }

    const analysis = await analyzeText({
      session_id: request.session_id,
      request_id: request.request_id,
      source: 'audio',
      captured_at: request.captured_at,
      text: transcript,
    });
    return {
      ...analysis,
      transcript,
    };
  };
}

function validateModelAnalysis(content) {
  const jsonText = extractJsonObject(content);
  if (!jsonText) {
    throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型未返回结果 JSON');
  }
  let parsed;
  try {
    parsed = JSON.parse(jsonText);
  } catch (error) {
    throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回的 JSON 无法解析');
  }
  if (!['none', 'low', 'medium', 'high'].includes(parsed.risk_level)) {
    throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型返回了未知风险等级');
  }
  if (typeof parsed.evidence !== 'string' || typeof parsed.reason !== 'string') {
    throw new ApiError(502, 'ANALYSIS_INVALID_RESPONSE', '风险模型结果缺少 evidence 或 reason');
  }
  return {
    risk_level: parsed.risk_level,
    evidence: parsed.evidence.trim(),
    reason: parsed.reason.trim(),
  };
}

function screenRequestFingerprint(request) {
  return crypto.createHash('sha256')
    .update(request.session_id)
    .update(request.captured_at)
    .update(request.ocr_text)
    .update(request.image_buffer)
    .digest('hex');
}

function audioRequestFingerprint(request) {
  return crypto.createHash('sha256')
    .update(request.session_id)
    .update(request.captured_at)
    .update(String(request.duration_ms))
    .update(request.audio_buffer)
    .digest('hex');
}

const VISION_RISK_PROMPT = `你是金融反诈骗风险分析器。下面是一张用户主动授权采集的手机屏幕。
请同时阅读屏幕中清晰可见的印刷文字、手写的白板文字、转账页面内容和共享文档。

重点识别：
1. 要求向陌生账户或“安全账户”转账；
2. 冒充公安、检察院、法院、银行或客服要求配合调查；
3. 承诺高收益、稳赚不赔或内部消息；
4. 索要验证码、密码、身份证、银行卡信息；
5. 要求安装远程控制软件或共享屏幕；
6. 要求保密、不要告诉家人或催促立即操作。

普通会议界面、工具栏按钮或无法确认的内容不能单独当成风险。没有明显风险时返回 none。
只返回一个 JSON 对象，禁止 Markdown 和额外说明：
{
  "risk_level": "none|low|medium|high",
  "evidence": "触发风险的关键屏幕原文，没有风险时可为空字符串",
  "reason": "简短中文解释"
}
`;

function extractMessageContent(responseBody) {
  const content = responseBody
    && responseBody.choices
    && responseBody.choices[0]
    && responseBody.choices[0].message
    && responseBody.choices[0].message.content;

  if (typeof content === 'string') {
    return content;
  }
  if (Array.isArray(content)) {
    return content
      .filter((item) => item && item.type === 'text')
      .map((item) => item.text || '')
      .join('\n');
  }
  return '';
}

function extractJsonObject(text) {
  if (typeof text !== 'string') {
    return '';
  }
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  return start >= 0 && end > start ? text.slice(start, end + 1) : '';
}

function trimTrailingSlash(value) {
  return String(value || '').replace(/\/+$/, '');
}

module.exports = {
  RISK_PROMPT,
  VISION_RISK_PROMPT,
  audioRequestFingerprint,
  createAudioRiskAnalyzer,
  createRiskAnalyzer,
  createVisionRiskAnalyzer,
  extractJsonObject,
  extractMessageContent,
  screenRequestFingerprint,
  validateModelAnalysis,
};

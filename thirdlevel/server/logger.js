'use strict';

/**
 * 极简结构化日志：每次调用输出一行 JSON 到 stdout。
 *
 * 设计约束（第三级风险服务）：
 * - 只输出服务端可控字段（request_id/status/risk_level/耗时等）；
 * - 绝不输出 OCR 文本、图片/音频内容、Authorization 头或任何密钥；
 * - 一行一个 JSON，方便 `docker logs`、nginx 日志或日志采集直接解析。
 */

function createJsonLogger(options = {}) {
  const stream = options.stream || process.stdout;
  const clock = options.clock || (() => new Date());

  return function log(record) {
    if (!record || typeof record !== 'object') {
      return;
    }
    const entry = Object.assign(
      { ts: clock().toISOString(), level: 'info' },
      record
    );
    stream.write(`${JSON.stringify(entry)}\n`);
  };
}

module.exports = {
  createJsonLogger,
};

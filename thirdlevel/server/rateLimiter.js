'use strict';

const crypto = require('crypto');

const DEFAULT_RATE_LIMIT_PER_MINUTE = 60;
const DEFAULT_WINDOW_MS = 60 * 1000;
// 单实例内存型限流器最多保留的桶数量，防止大量随机令牌/IP 撑爆内存。
const DEFAULT_MAX_BUCKETS = 10000;

/**
 * 解析 RATE_LIMIT_PER_MINUTE 之类的配置：
 * - 未配置 / 空字符串 -> fallback（默认 60）
 * - 0 -> 关闭限流
 * - 负数 / NaN / 非数字 -> fallback（避免误配导致服务直接不可用）
 */
function parsePerMinute(value, fallback = DEFAULT_RATE_LIMIT_PER_MINUTE) {
  if (value === undefined || value === null || `${value}`.trim() === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) {
    return fallback;
  }
  return Math.floor(parsed);
}

/**
 * 固定窗口限流：按“客户端令牌 + 客户端 IP”分桶，每窗口（默认 60s）最多 N 次。
 * 0 = 关闭限流（enabled=false，consume 永远放行且不产生状态）。
 *
 * 注意：这是单实例内存实现，多副本部署时每个副本各自计数；
 * 教学演示项目可接受，生产环境应换 Redis 等共享存储。
 */
class RateLimiter {
  constructor(options = {}) {
    const perMinute = Number(options.perMinute);
    this.perMinute = Number.isFinite(perMinute) && perMinute > 0
      ? Math.floor(perMinute)
      : 0;
    this.windowMs = Number(options.windowMs) > 0
      ? Number(options.windowMs)
      : DEFAULT_WINDOW_MS;
    this.maxBuckets = Number(options.maxBuckets) > 0
      ? Math.floor(Number(options.maxBuckets))
      : DEFAULT_MAX_BUCKETS;
    this.now = typeof options.now === 'function' ? options.now : Date.now;
    this.buckets = new Map();
    this.timer = null;
  }

  get enabled() {
    return this.perMinute > 0;
  }

  get size() {
    return this.buckets.size;
  }

  /**
   * 分桶键：sha256(token) 前 16 位 + "|" + IP。
   * 不直接保存明文令牌，避免内存快照/日志泄露密钥。
   */
  static buildKey(token, clientIp) {
    const digest = crypto
      .createHash('sha256')
      .update(String(token || ''))
      .digest('hex')
      .slice(0, 16);
    return `${digest}|${String(clientIp || 'unknown')}`;
  }

  /**
   * 尝试消费一次配额。
   * @returns {{allowed:boolean, enabled:boolean, limit:number, remaining:number,
   *            resetAtMs:number, retryAfterSeconds:number}}
   */
  consume(key) {
    if (!this.enabled) {
      return {
        allowed: true,
        enabled: false,
        limit: 0,
        remaining: 0,
        resetAtMs: 0,
        retryAfterSeconds: 0,
      };
    }

    const nowMs = this.now();
    let bucket = this.buckets.get(key);
    if (!bucket || nowMs - bucket.windowStart >= this.windowMs) {
      bucket = { windowStart: nowMs, count: 0 };
      this.buckets.set(key, bucket);
      this.enforceCapacity(nowMs);
    }

    const resetAtMs = bucket.windowStart + this.windowMs;
    if (bucket.count >= this.perMinute) {
      return {
        allowed: false,
        enabled: true,
        limit: this.perMinute,
        remaining: 0,
        resetAtMs,
        retryAfterSeconds: Math.max(1, Math.ceil((resetAtMs - nowMs) / 1000)),
      };
    }

    bucket.count += 1;
    return {
      allowed: true,
      enabled: true,
      limit: this.perMinute,
      remaining: this.perMinute - bucket.count,
      resetAtMs,
      retryAfterSeconds: 0,
    };
  }

  /** 清理已过期的桶；窗口结束时间超过 2 个窗口即视为过期。 */
  cleanup(nowMs = this.now()) {
    for (const [key, bucket] of this.buckets) {
      if (nowMs - bucket.windowStart >= this.windowMs * 2) {
        this.buckets.delete(key);
      }
    }
  }

  /** 超过 maxBuckets 时按插入顺序淘汰最旧的桶，保证内存有上界。 */
  enforceCapacity(nowMs) {
    if (this.buckets.size <= this.maxBuckets) {
      return;
    }
    this.cleanup(nowMs);
    while (this.buckets.size > this.maxBuckets) {
      const oldestKey = this.buckets.keys().next().value;
      if (oldestKey === undefined) {
        break;
      }
      this.buckets.delete(oldestKey);
    }
  }

  /** 启动周期清理定时器（unref，不阻止进程退出）。 */
  startCleanupTimer(intervalMs = Math.min(this.windowMs, 60 * 1000)) {
    if (this.timer) {
      return this.timer;
    }
    this.timer = setInterval(() => this.cleanup(), intervalMs);
    if (typeof this.timer.unref === 'function') {
      this.timer.unref();
    }
    return this.timer;
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

module.exports = {
  DEFAULT_MAX_BUCKETS,
  DEFAULT_RATE_LIMIT_PER_MINUTE,
  DEFAULT_WINDOW_MS,
  RateLimiter,
  parsePerMinute,
};

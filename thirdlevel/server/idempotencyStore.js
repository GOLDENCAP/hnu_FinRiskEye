'use strict';

class RequestIdConflictError extends Error {
  constructor() {
    super('request_id 已被不同请求内容使用');
    this.name = 'RequestIdConflictError';
  }
}

class IdempotencyStore {
  constructor(ttlMs) {
    this.ttlMs = ttlMs;
    this.entries = new Map();
  }

  get size() {
    return this.entries.size;
  }

  cleanup(now = Date.now()) {
    for (const [key, entry] of this.entries) {
      if (entry.state === 'complete' && entry.expiresAt <= now) {
        this.entries.delete(key);
      }
    }
  }

  begin(requestId, fingerprint) {
    this.cleanup();
    const existing = this.entries.get(requestId);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new RequestIdConflictError();
      }
      if (existing.state === 'complete') {
        return { kind: 'cached', response: existing.response };
      }
      return { kind: 'pending', promise: existing.promise };
    }

    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
      resolve = res;
      reject = rej;
    });
    // 失败只由当前请求或等待中的重复请求处理，避免 owner 路径产生未处理拒绝。
    promise.catch(() => {});
    const entry = {
      fingerprint,
      state: 'pending',
      promise,
      resolve,
      reject,
      expiresAt: Number.POSITIVE_INFINITY,
    };
    this.entries.set(requestId, entry);
    return { kind: 'owner', entry };
  }

  complete(requestId, response) {
    const entry = this.entries.get(requestId);
    if (!entry || entry.state !== 'pending') {
      return;
    }
    entry.state = 'complete';
    entry.response = response;
    entry.expiresAt = Date.now() + this.ttlMs;
    entry.resolve(response);
    delete entry.resolve;
    delete entry.reject;
  }

  fail(requestId, error) {
    const entry = this.entries.get(requestId);
    if (!entry || entry.state !== 'pending') {
      return;
    }
    this.entries.delete(requestId);
    entry.reject(error);
  }
}

module.exports = {
  IdempotencyStore,
  RequestIdConflictError,
};

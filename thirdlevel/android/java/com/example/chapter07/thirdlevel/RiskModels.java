package com.example.chapter07.thirdlevel;

/**
 * 第三级风险检测使用的轻量数据模型。
 * 该类不依赖前两级的 WebRTC 分析器，便于搭档替换采集或展示实现。
 */
public final class RiskModels {

    private RiskModels() {
    }

    public static final class RiskRequest {
        public final String sessionId;
        public final String requestId;
        public final String source;
        public final String capturedAt;
        public final String text;
        public final int durationMs;

        public RiskRequest(String sessionId, String requestId, String source,
                           String capturedAt, String text) {
            this(sessionId, requestId, source, capturedAt, text, 0);
        }

        public RiskRequest(String sessionId, String requestId, String source,
                           String capturedAt, String text, int durationMs) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.source = source;
            this.capturedAt = capturedAt;
            this.text = text;
            this.durationMs = durationMs;
        }
    }

    public static final class RiskResult {
        public boolean success;
        public String riskLevel = "";
        public String evidence = "";
        public String reason = "";
        public String errorCode = "";
        public String message = "";
        /** 本次分析请求耗时（毫秒，含重试）；-1 表示未记录/未知。12/8 验收“记录延迟”用。 */
        public int latencyMs = -1;

        public static RiskResult success(String riskLevel, String evidence, String reason) {
            RiskResult result = new RiskResult();
            result.success = true;
            result.riskLevel = riskLevel == null ? "" : riskLevel;
            result.evidence = evidence == null ? "" : evidence;
            result.reason = reason == null ? "" : reason;
            return result;
        }

        public static RiskResult error(String errorCode, String message) {
            RiskResult result = new RiskResult();
            result.success = false;
            result.errorCode = errorCode == null ? "" : errorCode;
            result.message = message == null ? "风险分析失败" : message;
            return result;
        }

        public boolean shouldAlert() {
            return success && ("high".equals(riskLevel) || "medium".equals(riskLevel));
        }
    }
}

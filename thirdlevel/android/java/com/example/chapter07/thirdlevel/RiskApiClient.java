package com.example.chapter07.thirdlevel;

import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 第三级风险服务客户端。
 * Android 只访问本项目自己的服务，不直接持有外部模型密钥。
 */
public final class RiskApiClient {

    public interface ResultCallback {
        void onComplete(RiskModels.RiskResult result);
    }

    public interface HealthCallback {
        void onHealthy();

        void onUnavailable(String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final String token;
    private Call activeCall;

    public RiskApiClient(String baseUrl, String token) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void analyze(RiskModels.RiskRequest request, ResultCallback callback) {
        execute("/api/v1/risk/text", request, null, null, true, callback);
    }

    public void analyzeScreen(RiskModels.RiskRequest request, String imageBase64,
                              ResultCallback callback) {
        execute("/api/v1/risk/screen", request, imageBase64, null, true, callback);
    }

    public void analyzeAudio(RiskModels.RiskRequest request, String audioBase64,
                             ResultCallback callback) {
        execute("/api/v1/risk/audio", request, null, audioBase64, true, callback);
    }

    public void checkHealth(HealthCallback callback) {
        Request request = new Request.Builder()
                .url(baseUrl + "/health")
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onUnavailable(
                        "无法连接风险分析服务，请确认服务已启动且平板与电脑在同一网络"
                );
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody ignored = response.body()) {
                    if (response.isSuccessful()) {
                        callback.onHealthy();
                    } else {
                        callback.onUnavailable("风险分析服务返回 HTTP " + response.code());
                    }
                }
            }
        });
    }

    public void cancel() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    private void execute(String path, RiskModels.RiskRequest riskRequest,
                         String imageBase64,
                         String audioBase64,
                         boolean retryAllowed,
                         ResultCallback callback) {
        execute(path, riskRequest, imageBase64, audioBase64, retryAllowed, callback,
                SystemClock.elapsedRealtime());
    }

    /**
     * 实际请求入口。
     *
     * @param startedAtMs 本次分析（含可能的重试）的起始时间，用于 12/8 验收要求的延迟统计；
     *                    重试时沿用同一个起点，统计到用户拿到的最终结果为止
     */
    private void execute(String path, RiskModels.RiskRequest riskRequest,
                         String imageBase64,
                         String audioBase64,
                         boolean retryAllowed,
                         ResultCallback callback,
                         long startedAtMs) {
        RequestBody body;
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", riskRequest.sessionId);
            json.put("request_id", riskRequest.requestId);
            json.put("source", riskRequest.source);
            json.put("captured_at", riskRequest.capturedAt);
            json.put("text", riskRequest.text);
            if (imageBase64 != null) {
                json.put("ocr_text", riskRequest.text);
                json.put("image_base64", imageBase64);
            }
            if (audioBase64 != null) {
                json.put("duration_ms", riskRequest.durationMs);
                json.put("audio_base64", audioBase64);
            }
            body = RequestBody.create(json.toString(), JSON);
        } catch (Exception error) {
            deliver(callback, RiskModels.RiskResult.error(
                    "CLIENT_REQUEST_ERROR",
                    "无法构造风险请求"
            ), startedAtMs);
            return;
        }

        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .post(body)
                .header("Content-Type", "application/json");
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }

        OkHttpClient callClient = audioBase64 == null
                ? client
                : client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build();
        activeCall = callClient.newCall(builder.build());
        activeCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                if (call.isCanceled()) {
                    return;
                }
                if (retryAllowed) {
                    execute(path, riskRequest, imageBase64, audioBase64, false, callback,
                            startedAtMs);
                } else {
                    deliver(callback, RiskModels.RiskResult.error(
                            "NETWORK_ERROR",
                            "无法连接风险分析服务，请确认服务已启动且平板与电脑在同一网络"
                    ), startedAtMs);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    String responseText = responseBody == null ? "" : responseBody.string();
                    RiskModels.RiskResult parsed = parseResponse(response.code(), responseText);
                    if (!parsed.success && response.code() >= 500 && retryAllowed) {
                        execute(path, riskRequest, imageBase64, audioBase64, false, callback,
                                startedAtMs);
                        return;
                    }
                    deliver(callback, parsed, startedAtMs);
                } catch (Exception error) {
                    deliver(callback, RiskModels.RiskResult.error(
                            "CLIENT_RESPONSE_ERROR",
                            "无法解析风险分析响应"
                    ), startedAtMs);
                }
            }
        });
    }

    /** 统一回传结果：把本次请求（含重试）的总耗时写入 RiskResult.latencyMs。 */
    private static void deliver(ResultCallback callback,
                                RiskModels.RiskResult result,
                                long startedAtMs) {
        result.latencyMs = (int) Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
        callback.onComplete(result);
    }

    private static RiskModels.RiskResult parseResponse(int httpCode, String responseText) {
        try {
            JSONObject root = new JSONObject(responseText);
            if (httpCode == 200 && "success".equals(root.optString("status"))) {
                String riskLevel = root.optString("risk_level", "");
                if (!"none".equals(riskLevel)
                        && !"low".equals(riskLevel)
                        && !"medium".equals(riskLevel)
                        && !"high".equals(riskLevel)) {
                    return RiskModels.RiskResult.error(
                            "INVALID_SERVER_RESPONSE",
                            "风险服务返回了无效等级"
                    );
                }
                return RiskModels.RiskResult.success(
                        riskLevel,
                        root.optString("evidence", ""),
                        root.optString("reason", "")
                );
            }
            return RiskModels.RiskResult.error(
                    root.optString("error_code", "HTTP_" + httpCode),
                    root.optString("message", "风险分析失败")
            );
        } catch (Exception error) {
            return RiskModels.RiskResult.error(
                    "INVALID_SERVER_RESPONSE",
                    httpCode == 401 ? "风险服务认证失败" : "风险服务响应格式错误"
            );
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String suffix = "/api/v1/risk/text";
        if (normalized.endsWith(suffix)) {
            return normalized.substring(0, normalized.length() - suffix.length());
        }
        suffix = "/api/v1/risk/screen";
        if (normalized.endsWith(suffix)) {
            return normalized.substring(0, normalized.length() - suffix.length());
        }
        return normalized;
    }
}

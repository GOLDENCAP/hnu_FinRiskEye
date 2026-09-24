package com.example.chapter07;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FraudVideoAnalyzer：金融诈骗实时视频检测分析器。
 *
 * 功能：
 *   - 实现 WebRTC VideoSink 接口，实时接收远端视频帧
 *   - 定时（例如每 5 秒）抓取一帧并转换为 JPEG 图像
 *   - 调用 OpenAI GPT-4o vision API（多模态对话）对图像进行金融诈骗风险识别
 *   - 通过回调接口实时反馈分析结果（含风险等级、评分、检测到的特征、摘要和建议）
 *
 * 检测场景（针对视频画面）：
 *   - 出现身份证、银行卡、社保卡等敏感证件
 *   - 出现银行 APP / 支付 APP / 转账界面
 *   - 出现对方手持法律文书、警察证、法院传票
 *   - 屏幕共享中有输入密码、验证码、身份证号等敏感信息
 *   - 出现"安全账户""资金核查""洗钱"等诈骗关键词
 *   - 出现下载安装未知 APP / 远程控制软件（如 AnyDesk/TeamViewer）
 *
 * 使用方式（最小侵入，独立的 VideoSink）：
 *   FraudVideoAnalyzer analyzer = new FraudVideoAnalyzer(context, "YOUR_OPENAI_API_KEY");
 *   analyzer.setFraudVideoListener(new FraudVideoAnalyzer.FraudVideoListener() { ... });
 *   // 调用 setup() 注册 VideoSink；调用 teardown() 释放资源
 *   analyzer.setup();
 *   analyzer.setPaused(false);                  // 开始采样分析
 *   analyzer.teardown();
 *
 * 远端视频帧可通过外部调用 addSinkToVideoTrack() 自动挂到 mRemoteVideoTrack，
 * 也可以单独调用 addSinkToVideoTrack(VideoTrack) 把分析器挂到任意远程 VideoTrack。
 */
public class FraudVideoAnalyzer implements VideoSink {

    private static final String TAG = "FraudVideoAnalyzer";

    // ========== OpenAI API 配置（硬编码） ==========
    // TODO: 请替换为你的 OpenAI API Key
    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY_HERE";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String CHATGPT_API_URL = OPENAI_BASE_URL + "/chat/completions";
    private static final String GPT_VISION_MODEL = "gpt-4o-mini"; // 支持视觉的模型

    // ========== 采样参数 ==========
    // 默认每 5 秒采样一帧（避免 API 调用太频繁）
    private static final long DEFAULT_SAMPLE_INTERVAL_MS = 5_000L;
    // JPEG 压缩质量（50~80 足够识别内容）
    private static final int JPEG_QUALITY = 60;
    // 输出图片最大尺寸（缩小长边到 1024，控制请求体大小）
    private static final int MAX_IMAGE_EDGE = 1024;

    // ========== 风险分析提示词 ==========
    private static final String FRAUD_VIDEO_PROMPT =
        "你是一名金融反诈骗专家。请仔细分析这张视频通话画面截图，判断通话中是否存在以下金融诈骗特征：\n" +
        "1. 屏幕中显示身份证、银行卡、社保卡、护照等敏感证件\n" +
        "2. 屏幕中显示银行 APP、网银界面、支付 APP、汇款界面\n" +
        "3. 屏幕中显示转账金额、收款账户、银行流水\n" +
        "4. 屏幕中出现'安全账户'、'资金核查'、'洗钱'、'冻结账户'等可疑文字\n" +
        "5. 屏幕共享中输入密码、验证码、身份证号等敏感信息\n" +
        "6. 对方手持警察证、法院传票、法律文书等公检法相关物品\n" +
        "7. 屏幕中出现下载安装未知 APP、远程控制软件（如 AnyDesk/TeamViewer/向日葵）\n" +
        "8. 屏幕中出现'高收益投资'、'稳赚不赔'、'内幕消息'、'杀猪盘'字样\n" +
        "9. 屏幕中出现不正常的贷款、信用卡套现、刷流水等内容\n" +
        "10. 镜头内人员神情紧张、言辞激烈、有胁迫性肢体语言\n\n" +
        "如果画面中没有任何可疑内容，请给出无风险的结论。\n\n" +
        "请以严格的 JSON 格式返回分析结果，不要包含任何额外文字，格式如下：\n" +
        "{\n" +
        "  \"risk_level\": \"high/medium/low/none\",\n" +
        "  \"risk_score\": 0-100,\n" +
        "  \"detected_features\": [\"特征1\", \"特征2\"],\n" +
        "  \"summary\": \"画面简要分析说明\",\n" +
        "  \"advice\": \"建议采取的措施\"\n" +
        "}";

    // ========== 状态变量 ==========
    private final Context mContext;
    private final String mApiKey;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread mSamplingThread;
    private final Handler mSamplingHandler;
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final AtomicBoolean mIsAnalyzing = new AtomicBoolean(false);
    private final AtomicBoolean mPaused = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Bitmap> mFramePool = new ConcurrentLinkedQueue<>();
    // 回调
    private FraudVideoListener mListener;
    // 采样间隔
    private long mSampleIntervalMs = DEFAULT_SAMPLE_INTERVAL_MS;
    // 累计采样次数
    private long mSampledCount = 0;
    private long mAnalyzedCount = 0;

    // 临时 JPEG 文件（用于某些 API 不接受 base64 时回退使用 URL 上传）
    private File mTempJpegFile;

    /**
     * 诈骗风险检测结果
     */
    public static class FraudVideoResult {
        public String riskLevel;          // high/medium/low/none
        public int riskScore;             // 0-100
        public String[] detectedFeatures; // 检测到的诈骗特征
        public String summary;            // 画面分析摘要
        public String advice;             // 建议措施
        public String rawResponse;        // 原始 API 响应
        public long timestampMs;          // 该帧分析完成时间戳
    }

    /**
     * 视频诈骗检测回调
     */
    public interface FraudVideoListener {
        /** 检测到诈骗风险（每帧分析完成都回调） */
        void onFraudVideoDetected(FraudVideoResult result);

        /** 错误回调 */
        void onError(String error);

        /** 状态更新 */
        void onStatusUpdate(String status);
    }

    public FraudVideoAnalyzer(Context context, String apiKey) {
        this.mContext = context.getApplicationContext();
        this.mApiKey = (apiKey == null || apiKey.isEmpty()) ? OPENAI_API_KEY : apiKey;
        this.mSamplingThread = new HandlerThread("FraudVideoAnalyzer");
        this.mSamplingThread.start();
        this.mSamplingHandler = new Handler(this.mSamplingThread.getLooper());
        try {
            this.mTempJpegFile = new File(mContext.getCacheDir(), "fraud_video_frame.jpg");
        } catch (Exception ignore) { }
    }

    /**
     * 是否正在采样（外部用于校验启动是否真正生效）
     */
    public boolean isRunning() {
        return mIsRunning.get();
    }

    /**
     * 设置回调
     */
    public void setFraudVideoListener(FraudVideoListener listener) {
        this.mListener = listener;
    }

    /**
     * 设置采样间隔（毫秒），默认 5000
     */
    public void setSampleIntervalMs(long ms) {
        if (ms < 1000) ms = 1000;
        this.mSampleIntervalMs = ms;
    }

    /**
     * 启动：开启采样循环
     */
    public void setup() {
        if (mIsRunning.compareAndSet(false, true)) {
            mPaused.set(false);
            mSamplingHandler.post(mSamplingTick);
            notifyStatus("视频诈骗检测已启动");
        }
    }

    /**
     * 暂停/恢复采样（窗口切换时可暂停）
     */
    public void setPaused(boolean paused) {
        mPaused.set(paused);
    }

    /**
     * 停止并释放
     */
    public void teardown() {
        if (!mIsRunning.compareAndSet(true, false)) {
            return;
        }
        mSamplingHandler.removeCallbacksAndMessages(null);
        mSamplingHandler.post(() -> mFramePool.clear());

        notifyStatus("视频诈骗检测已停止");
    }

    /**
     * 释放所有资源（含后台 HandlerThread）
     */
    public void release() {
        teardown();
        mSamplingThread.quitSafely();
        // 清理临时文件
        if (mTempJpegFile != null && mTempJpegFile.exists()) {
            try { mTempJpegFile.delete(); } catch (Exception ignore) { }
        }
        Log.i(TAG, "FraudVideoAnalyzer released");
    }

    /**
     * 把分析器挂到任意远端 VideoTrack 上，自动接收视频帧。
     * 调用前请保证传入的 VideoTrack 已经存在。
     */
    public void attachToVideoTrack(org.webrtc.VideoTrack track) {
        if (track == null) {
            notifyError("VideoTrack 为空，无法挂载 FraudVideoAnalyzer");
            return;
        }
        try { track.removeSink(this); } catch (Throwable ignore) { }
        track.addSink(this);
        Log.i(TAG, "FraudVideoAnalyzer attached to VideoTrack id=" + track.id());
    }

    public void detachFromVideoTrack(org.webrtc.VideoTrack track) {
        if (track == null) return;
        try { track.removeSink(this); } catch (Throwable ignore) { }
    }

    // ============ WebRTC VideoSink 接口实现 ============

    /**
     * WebRTC 每一帧视频数据到达时调用（运行在 WebRTC 内部线程）。
     * 这里只做"复制一帧最新数据到内存"，不阻塞、不抛异常。
     */
    @Override
    public void onFrame(VideoFrame frame) {
        if (frame == null) return;
        if (!mIsRunning.get() || mPaused.get()) {
            return;
        }
        try {
            // 从 VideoFrame 拿 I420 buffer
            VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
            if (i420 == null) {
                return;
            }
            int width = i420.getWidth();
            int height = i420.getHeight();

            // 拷贝出 YUV420 planar 数据
            int ySize = width * height;
            int uvWidth = (width + 1) / 2;
            int uvHeight = (height + 1) / 2;
            int uvSize = uvWidth * uvHeight;

            ByteBuffer yBuf = i420.getDataY();
            ByteBuffer uBuf = i420.getDataU();
            ByteBuffer vBuf = i420.getDataV();
            i420.release();

            if (yBuf == null || uBuf == null || vBuf == null) {
                return;
            }
            byte[] yData = new byte[ySize];
            byte[] uData = new byte[uvSize];
            byte[] vData = new byte[uvSize];
            yBuf.rewind(); uBuf.rewind(); vBuf.rewind();
            yBuf.get(yData); uBuf.get(uData); vBuf.get(vData);

            // 提交到采样线程转 Bitmap
            mSamplingHandler.post(() -> convertFrameToBitmap(yData, uData, vData, uvWidth, uvHeight, width, height));

            // 同时丢帧旧的、过深的池
            if (mFramePool.size() > 3) {
                Bitmap old;
                while ((old = mFramePool.poll()) != null) {
                    if (old != null && !old.isRecycled()) old.recycle();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onFrame error", t);
        }
    }

    /**
     * 在采样线程上把 YUV420 planar -> JPEG byte[] -> Bitmap，并放入池中。
     * 同步写一个副本 JPEG 文件以便回退到 multipart 上传（可选）。
     */
    private void convertFrameToBitmap(byte[] y, byte[] u, byte[] v,
                                      int uvWidth, int uvHeight,
                                      int width, int height) {
        try {
            // YUV420 planar 转 NV21（YUV420sp）：Y 全 + VU 交错
            byte[] nv21 = new byte[y.length + u.length + v.length];
            System.arraycopy(y, 0, nv21, 0, y.length);
            int uvOffset = y.length;
            for (int i = 0; i < u.length; i++) {
                nv21[uvOffset + 2 * i] = v[i];
                nv21[uvOffset + 2 * i + 1] = u[i];
            }
            // 用 YuvImage 转 JPEG
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, baos);
            byte[] jpeg = baos.toByteArray();

            // 解码为 Bitmap
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) return;

            // 缩小（若超过 MAX_IMAGE_EDGE）
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int maxEdge = Math.max(w, h);
            if (maxEdge > MAX_IMAGE_EDGE) {
                float scale = (float) MAX_IMAGE_EDGE / maxEdge;
                int nw = Math.round(w * scale);
                int nh = Math.round(h * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            // 旋转：远端视频默认是 0°，本地视频要 270° 才正常，
            // 这里我们只关心画面内容，无需旋转；如画面颠倒可在此按需 rotate

            // 队列里只保留最新 1 张图
            Bitmap old;
            while ((old = mFramePool.poll()) != null) {
                if (old != null && !old.isRecycled()) old.recycle();
            }
            mFramePool.offer(bmp);

            // 顺便写一份到磁盘（保留方便调试 / 未来上传场景）
            if (mTempJpegFile != null) {
                try (FileOutputStream fos = new FileOutputStream(mTempJpegFile)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                } catch (Exception ignore) { }
            }
        } catch (Throwable t) {
            Log.e(TAG, "convertFrameToBitmap error", t);
        }
    }

    // ============ 采样触发 ============

    /**
     * 周期任务：到点时取最新一帧做分析
     */
    private final Runnable mSamplingTick = new Runnable() {
        @Override
        public void run() {
            if (!mIsRunning.get()) return;

            if (!mPaused.get() && !mIsAnalyzing.get()) {
                Bitmap bmp = mFramePool.poll();
                if (bmp != null && !bmp.isRecycled()) {
                    mSampledCount++;
                    analyzeBitmap(bmp);
                } else if (bmp != null) {
                    bmp.recycle();
                }
            }

            if (mIsRunning.get()) {
                mSamplingHandler.postDelayed(this, mSampleIntervalMs);
            }
        }
    };

    /**
     * 把 Bitmap 编码为 base64 data url，调用 GPT-4o vision 分析
     */
    private void analyzeBitmap(Bitmap bmp) {
        mIsAnalyzing.set(true);
        mSamplingHandler.post(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
                byte[] jpeg = baos.toByteArray();
                bmp.recycle();

                String base64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
                String dataUrl = "data:image/jpeg;base64," + base64;

                FraudVideoResult result = callVisionApi(dataUrl);
                mAnalyzedCount++;

                if (result != null) {
                    result.timestampMs = System.currentTimeMillis();
                    final FraudVideoResult fr = result;
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onFraudVideoDetected(fr);
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "analyzeBitmap error", t);
                notifyError("视频分析异常: " + t.getMessage());
            } finally {
                mIsAnalyzing.set(false);
            }
        });
    }

    /**
     * 调用 OpenAI Chat Completion API（GPT-4o vision）
     */
    private FraudVideoResult callVisionApi(String dataUrl) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(CHATGPT_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", GPT_VISION_MODEL);
            requestBody.put("temperature", 0.2);

            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");

            JSONArray content = new JSONArray();
            // 文本部分
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", FRAUD_VIDEO_PROMPT);
            content.put(textPart);
            // 图片部分
            JSONObject imgPart = new JSONObject();
            imgPart.put("type", "image_url");
            JSONObject imgUrl = new JSONObject();
            imgUrl.put("url", dataUrl);
            imgUrl.put("detail", "low"); // low 模式省钱且够识别诈骗特征
            imgPart.put("image_url", imgUrl);
            content.put(imgPart);

            userMsg.put("content", content);
            messages.put(userMsg);
            requestBody.put("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toString().getBytes("UTF-8"));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStreamReader isr = (code >= 200 && code < 300)
                    ? new InputStreamReader(conn.getInputStream())
                    : new InputStreamReader(conn.getErrorStream());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String body = sb.toString();
            Log.i(TAG, "vision api code=" + code + " body=" + body);

            if (code < 200 || code >= 300) {
                notifyError("API 返回错误 " + code + ": " + body);
                return null;
            }
            return parseFraudResult(body);
        } catch (Exception e) {
            Log.e(TAG, "callVisionApi failed", e);
            notifyError("调用 GPT-4o 失败: " + e.getMessage());
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignore) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 解析 GPT-4o 返回结果
     */
    private FraudVideoResult parseFraudResult(String responseJson) {
        FraudVideoResult result = new FraudVideoResult();
        result.rawResponse = responseJson;
        try {
            JSONObject root = new JSONObject(responseJson);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                result.riskLevel = "unknown";
                result.summary = "API 未返回 choices";
                return result;
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) {
                result.riskLevel = "unknown";
                result.summary = "API 未返回 message";
                return result;
            }
            Object contentObj = msg.opt("content");
            String contentText;
            if (contentObj instanceof JSONArray) {
                // 多模态内容合并（理论上 vision 走的是简化路径，单 content:string 即可）
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) contentObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null && "text".equals(p.optString("type"))) {
                        sb.append(p.optString("text"));
                    }
                }
                contentText = sb.toString();
            } else {
                contentText = String.valueOf(contentObj);
            }

            // 提取 JSON 子串
            String jsonStr = extractJson(contentText);
            if (jsonStr != null) {
                JSONObject json = new JSONObject(jsonStr);
                result.riskLevel = json.optString("risk_level", "unknown");
                result.riskScore = json.optInt("risk_score", 0);
                JSONArray features = json.optJSONArray("detected_features");
                if (features != null) {
                    result.detectedFeatures = new String[features.length()];
                    for (int i = 0; i < features.length(); i++) {
                        result.detectedFeatures[i] = features.optString(i);
                    }
                }
                result.summary = json.optString("summary", "");
                result.advice = json.optString("advice", "");
            } else {
                // 解析不到 JSON，退化为原文
                result.riskLevel = "unknown";
                result.summary = contentText;
            }
        } catch (Exception e) {
            Log.e(TAG, "parseFraudResult error", e);
            result.riskLevel = "unknown";
            result.summary = "解析失败: " + e.getMessage();
        }
        return result;
    }

    /**
     * 从 GPT 文本中提取第一个 {...} JSON 块
     */
    private static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // ============ 工具方法 ============

    private void notifyStatus(String msg) {
        Log.i(TAG, msg);
        mMainHandler.post(() -> {
            if (mListener != null) mListener.onStatusUpdate(msg);
        });
    }

    private void notifyError(String err) {
        Log.e(TAG, err);
        mMainHandler.post(() -> {
            if (mListener != null) mListener.onError(err);
        });
    }

    public long getSampledCount() { return mSampledCount; }
    public long getAnalyzedCount() { return mAnalyzedCount; }
}

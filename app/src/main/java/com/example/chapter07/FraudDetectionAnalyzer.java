package com.example.chapter07;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FraudDetectionAnalyzer：金融诈骗实时检测分析器。
 *
 * 功能：
 *   - 实时录制视频会议中的远端音频（PCM 数据）
 *   - 将录制的音频转换为文本（Whisper API）
 *   - 将文本发送给 OpenAI GPT 分析是否有金融诈骗风险
 *   - 通过回调接口实时反馈分析结果
 *
 * 使用方式：
 *   FraudDetectionAnalyzer analyzer = new FraudDetectionAnalyzer(context, "YOUR_API_KEY");
 *   analyzer.setFraudDetectionListener(new FraudDetectionListener() {
 *       @Override
 *       public void onFraudRiskDetected(FraudRiskResult result) {
 *           // 处理风险检测结果
 *       }
 *       @Override
 *       public void onTranscription(String text) {
 *           // 处理转录文本
 *       }
 *       @Override
 *       public void onError(String error) {
 *           // 处理错误
 *       }
 *   });
 *   analyzer.start();
 *   // ... 录制完成后
 *   analyzer.stop();
 */
public class FraudDetectionAnalyzer {

    private static final String TAG = "FraudDetectionAnalyzer";

    // ========== OpenAI API 配置 ==========
    // TODO: 请替换为你的 OpenAI API Key
    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY_HERE";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    // ========== Whisper API 配置 ==========
    private static final String WHISPER_API_URL = OPENAI_BASE_URL + "/audio/transcriptions";

    // ========== ChatGPT API 配置 ==========
    private static final String CHATGPT_API_URL = OPENAI_BASE_URL + "/chat/completions";
    private static final String GPT_MODEL = "gpt-4o-mini";

    // ========== 诈骗检测提示词 ==========
    private static final String FRAUD_DETECTION_PROMPT =
        "你是一个专业的金融反诈骗专家。请分析以下通话内容，判断是否存在以下诈骗特征：\n" +
        "1. 冒充公检法、银行、政府机构等官方身份\n" +
        "2. 要求转账到安全账户、核查账户资金\n" +
        "3. 声称涉及洗钱、信用卡透支、社保卡异常等\n" +
        "4. 要求提供验证码、密码、身份证照片等敏感信息\n" +
        "5. 威胁冻结账户、逮捕、起诉等\n" +
        "6. 要求安装未知APP、开启屏幕共享\n" +
        "7. 投资理财诈骗（杀猪盘、虚假投资平台）\n" +
        "8. 冒充熟人、领导要求汇款\n" +
        "9. 冒充电商客服、快递丢失理赔\n" +
        "10. 要求关闭或修改贷款额度\n\n" +
        "通话内容：\n" +
        "[CONTENT]\n\n" +
        "请以JSON格式返回分析结果，格式如下：\n" +
        "{\n" +
        "  \"risk_level\": \"high/medium/low/none\",\n" +
        "  \"risk_score\": 0-100,\n" +
        "  \"detected_features\": [\"特征1\", \"特征2\"],\n" +
        "  \"summary\": \"简要分析说明\",\n" +
        "  \"advice\": \"建议采取的措施\"\n" +
        "}";

    // ========== 状态变量 ==========
    private final Context mContext;
    private final String mApiKey;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;

    private AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private AtomicBoolean mIsAnalyzing = new AtomicBoolean(false);

    // 音频缓冲区配置
    private static final int SAMPLE_RATE = 16000;  // Whisper 需要 16kHz
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_BUFFER_SIZE = SAMPLE_RATE * 60 * 2; // 最多缓存60秒音频

    private ByteBuffer mAudioBuffer;
    private int mBufferPosition = 0;

    // WebRTC 音频反射器（用于获取远端音频 PCM）
    private WebRtcAudioTrackReflector mAudioReflector;

    // 录制线程
    private Thread mRecordingThread;

    // 临时文件
    private File mTempPcmFile;
    private File mTempWavFile;

    // 回调接口
    private FraudDetectionListener mListener;

    /**
     * 诈骗风险检测结果
     */
    public static class FraudRiskResult {
        public String riskLevel;          // high/medium/low/none
        public int riskScore;             // 0-100
        public String[] detectedFeatures; // 检测到的诈骗特征
        public String summary;            // 分析摘要
        public String advice;             // 建议措施
        public String rawResponse;        // 原始API响应
    }

    /**
     * 诈骗检测回调接口
     */
    public interface FraudDetectionListener {
        /**
         * 检测到诈骗风险时回调
         */
        void onFraudRiskDetected(FraudRiskResult result);

        /**
         * 实时转录文本回调
         */
        void onTranscription(String text);

        /**
         * 错误回调
         */
        void onError(String error);

        /**
         * 状态更新回调
         */
        void onStatusUpdate(String status);
    }

    /**
     * 构造函数
     * @param context Android 上下文
     * @param apiKey OpenAI API Key（硬编码或从配置获取）
     */
    public FraudDetectionAnalyzer(Context context, String apiKey) {
        this.mContext = context.getApplicationContext();
        // 与另外两个分析器保持一致：入参为空时回退到类内常量
        this.mApiKey = (apiKey == null || apiKey.isEmpty()) ? OPENAI_API_KEY : apiKey;
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mExecutor = Executors.newCachedThreadPool();

        // 初始化音频缓冲区
        mAudioBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE);
        mAudioBuffer.order(ByteOrder.LITTLE_ENDIAN);

        // 创建临时文件
        try {
            mTempPcmFile = new File(mContext.getCacheDir(), "temp_audio.pcm");
            mTempWavFile = new File(mContext.getCacheDir(), "temp_audio.wav");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create temp files", e);
        }
    }

    /**
     * 设置诈骗检测回调
     */
    public boolean isRunning() {
        return mIsRecording.get();
    }

    public void setFraudDetectionListener(FraudDetectionListener listener) {
        this.mListener = listener;
    }

    /**
     * 设置音频反射器（用于从 WebRTC 获取远端音频 PCM）
     */
    public void setAudioReflector(WebRtcAudioTrackReflector reflector) {
        this.mAudioReflector = reflector;
    }

    /**
     * 开始实时录制和分析
     */
    public void start() {
        if (mIsRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        if (mAudioReflector == null) {
            notifyError("Audio reflector not set. Please call setAudioReflector() first.");
            return;
        }

        mIsRecording.set(true);
        mBufferPosition = 0;
        mAudioBuffer.clear();

        notifyStatus("开始诈骗检测...");

        // 启动录制线程
        mRecordingThread = new Thread(this::recordingLoop, "FraudDetection-Recording");
        mRecordingThread.start();

        Log.i(TAG, "Fraud detection started");
    }

    /**
     * 停止录制并分析
     */
    public void stop() {
        if (!mIsRecording.get()) {
            return;
        }

        mIsRecording.set(false);

        if (mRecordingThread != null) {
            try {
                mRecordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Recording thread interrupted", e);
            }
            mRecordingThread = null;
        }

        notifyStatus("录制停止，开始分析...");

        // 触发分析
        analyzeBuffer();

        Log.i(TAG, "Fraud detection stopped");
    }

    /**
     * 录制循环 - 从 WebRTC 音频反射器获取 PCM 数据
     */
    private void recordingLoop() {
        Log.i(TAG, "Recording loop started");

        while (mIsRecording.get()) {
            try {
                if (mAudioReflector != null) {
                    // 从 WebRTC 音频反射器读取 PCM 数据
                    byte[] pcmData = mAudioReflector.readLatestAudio();

                    if (pcmData != null && pcmData.length > 0) {
                        // 需要进行重采样（WebRTC 通常是 48kHz，需要转换为 16kHz）
                        byte[] resampledData = resampleAudio(pcmData);

                        // 写入缓冲区
                        synchronized (mAudioBuffer) {
                            if (mBufferPosition + resampledData.length <= MAX_BUFFER_SIZE) {
                                mAudioBuffer.position(mBufferPosition);
                                mAudioBuffer.put(resampledData);
                                mBufferPosition += resampledData.length;
                            } else {
                                // 缓冲区满，触发一次分析
                                Log.i(TAG, "Buffer full, triggering analysis");
                                mMainHandler.post(this::analyzeBuffer);
                                mBufferPosition = 0;
                                mAudioBuffer.clear();
                            }
                        }
                    }
                }

                // 每100ms检查一次
                Thread.sleep(100);

            } catch (Exception e) {
                Log.e(TAG, "Recording loop error", e);
                if (mIsRecording.get()) {
                    notifyError("录制异常: " + e.getMessage());
                }
            }
        }

        Log.i(TAG, "Recording loop ended");
    }

    /**
     * 简单的重采样（48kHz -> 16kHz）
     * 实际上这里做了4:1的整数抽取
     */
    private byte[] resampleAudio(byte[] input) {
        // WebRTC 音频通常是 48kHz, 16bit, mono
        // Whisper 需要 16kHz
        // 这里做简单的4:1降采样（取平均）
        int outputLength = input.length / 4;
        byte[] output = new byte[outputLength];

        ByteBuffer buffer = ByteBuffer.wrap(input);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuffer = ByteBuffer.wrap(output);
        outBuffer.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < outputLength / 2; i++) {
            long sum = 0;
            for (int j = 0; j < 4; j++) {
                short sample = buffer.getShort((i * 4 + j) * 2);
                sum += sample;
            }
            short avg = (short) (sum / 4);
            outBuffer.putShort(i * 2, avg);
        }

        return output;
    }

    /**
     * 分析当前缓冲区中的音频
     */
    private void analyzeBuffer() {
        if (mIsAnalyzing.get()) {
            Log.w(TAG, "Already analyzing, skip");
            return;
        }

        byte[] audioData;
        synchronized (mAudioBuffer) {
            if (mBufferPosition < SAMPLE_RATE) { // 少于1秒音频，跳过
                Log.w(TAG, "Not enough audio data: " + mBufferPosition + " bytes");
                return;
            }

            audioData = new byte[mBufferPosition];
            mAudioBuffer.rewind();
            mAudioBuffer.get(audioData, 0, mBufferPosition);
            mBufferPosition = 0;
            mAudioBuffer.clear();
        }

        mIsAnalyzing.set(true);

        mExecutor.execute(() -> {
            try {
                // 1. 将 PCM 转换为 WAV
                File wavFile = convertPcmToWav(audioData);
                if (wavFile == null) {
                    notifyError("音频格式转换失败");
                    mIsAnalyzing.set(false);
                    return;
                }

                // 2. 调用 Whisper API 进行语音转文字
                String transcription = transcribeAudio(wavFile);
                if (transcription == null || transcription.isEmpty()) {
                    Log.w(TAG, "Transcription empty or failed");
                    mIsAnalyzing.set(false);
                    return;
                }

                final String finalText = transcription;
                mMainHandler.post(() -> {
                    if (mListener != null) {
                        mListener.onTranscription(finalText);
                    }
                });

                // 3. 调用 ChatGPT 进行诈骗分析
                FraudRiskResult result = analyzeFraudRisk(finalText);
                if (result != null) {
                    mMainHandler.post(() -> {
                        if (mListener != null) {
                            mListener.onFraudRiskDetected(result);
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Analysis error", e);
                notifyError("分析异常: " + e.getMessage());
            } finally {
                mIsAnalyzing.set(false);
            }
        });
    }

    /**
     * 将 PCM 数据转换为 WAV 文件
     */
    private File convertPcmToWav(byte[] pcmData) {
        try {
            // 写入 PCM 文件
            java.io.FileOutputStream fos = new java.io.FileOutputStream(mTempPcmFile);
            fos.write(pcmData);
            fos.close();

            // 转换为 WAV
            FileInputStream pis = new FileInputStream(mTempPcmFile);
            FileOutputStream wos = new FileOutputStream(mTempWavFile);

            int totalDataLen = pcmData.length + 36;
            int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;

            // WAV 文件头
            wos.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            wos.write(intToByteArray(totalDataLen), 0, 4);
            wos.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            wos.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            wos.write(intToByteArray(16), 0, 4); // Subchunk1Size for PCM
            wos.write(shortToByteArray((short) 1), 0, 2); // AudioFormat (1 = PCM)
            wos.write(shortToByteArray((short) CHANNELS), 0, 2);
            wos.write(intToByteArray(SAMPLE_RATE), 0, 4);
            wos.write(intToByteArray(byteRate), 0, 4);
            wos.write(shortToByteArray((short) (CHANNELS * BITS_PER_SAMPLE / 8)), 0, 2); // Block align
            wos.write(shortToByteArray((short) BITS_PER_SAMPLE), 0, 2);
            wos.write("data".getBytes(StandardCharsets.US_ASCII));
            wos.write(intToByteArray(pcmData.length), 0, 4);

            // 写入音频数据
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = pis.read(buffer)) != -1) {
                wos.write(buffer, 0, bytesRead);
            }

            pis.close();
            wos.close();

            return mTempWavFile;

        } catch (Exception e) {
            Log.e(TAG, "PCM to WAV conversion failed", e);
            return null;
        }
    }

    private byte[] intToByteArray(int value) {
        return new byte[] {
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff),
            (byte) ((value >> 16) & 0xff),
            (byte) ((value >> 24) & 0xff)
        };
    }

    private byte[] shortToByteArray(short value) {
        return new byte[] {
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff)
        };
    }

    /**
     * 调用 Whisper API 进行语音转文字
     */
    private String transcribeAudio(File audioFile) {
        try {
            String boundary = "----FormBoundary" + System.currentTimeMillis();
            URL url = new URL(WHISPER_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();

            // 构建 multipart 请求体
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
            sb.append("Content-Type: audio/wav\r\n\r\n");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

            // 写入音频文件
            FileInputStream fis = new FileInputStream(audioFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            fis.close();

            // 添加 model 参数
            sb = new StringBuilder();
            sb.append("\r\n--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            sb.append("whisper-1");
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

            // 添加 language 参数（可选）
            sb = new StringBuilder();
            sb.append("\r\n--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
            sb.append("zh"); // 中文
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

            // 结束
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            // 读取响应
            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();

            Log.i(TAG, "Whisper response: " + response);

            // 解析响应
            JSONObject json = new JSONObject(response.toString());
            return json.optString("text", "");

        } catch (Exception e) {
            Log.e(TAG, "Whisper API call failed", e);
            return null;
        }
    }

    /**
     * 调用 ChatGPT 进行诈骗风险分析
     */
    private FraudRiskResult analyzeFraudRisk(String transcription) {
        try {
            URL url = new URL(CHATGPT_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setDoOutput(true);

            // 构建请求体
            String prompt = FRAUD_DETECTION_PROMPT.replace("[CONTENT]", transcription);
            JSONObject requestBody = new JSONObject();
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            messages.put(message);
            requestBody.put("model", GPT_MODEL);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.3); // 低温度以获得更稳定的分析结果

            OutputStream os = conn.getOutputStream();
            os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            // 读取响应
            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();

            Log.i(TAG, "ChatGPT response: " + response);

            // 解析响应
            JSONObject json = new JSONObject(response.toString());
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject assistantMessage = firstChoice.getJSONObject("message");
            String content = assistantMessage.optString("content", "");

            // 解析 JSON 内容
            return parseFraudResult(content, response.toString());

        } catch (Exception e) {
            Log.e(TAG, "ChatGPT API call failed", e);
            return null;
        }
    }

    /**
     * 解析诈骗分析结果
     */
    private FraudRiskResult parseFraudResult(String jsonContent, String rawResponse) {
        FraudRiskResult result = new FraudRiskResult();
        result.rawResponse = rawResponse;

        try {
            // 尝试解析 JSON
            JSONObject json = new JSONObject(jsonContent);
            result.riskLevel = json.optString("risk_level", "unknown");
            result.riskScore = json.optInt("risk_score", 0);

            JSONArray features = json.optJSONArray("detected_features");
            if (features != null) {
                result.detectedFeatures = new String[features.length()];
                for (int i = 0; i < features.length(); i++) {
                    result.detectedFeatures[i] = features.getString(i);
                }
            }

            result.summary = json.optString("summary", "");
            result.advice = json.optString("advice", "");

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse fraud result JSON", e);
            // 如果解析失败，尝试提取关键信息
            result.riskLevel = "unknown";
            result.summary = jsonContent;
        }

        return result;
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();

        mExecutor.shutdown();

        if (mTempPcmFile != null && mTempPcmFile.exists()) {
            mTempPcmFile.delete();
        }
        if (mTempWavFile != null && mTempWavFile.exists()) {
            mTempWavFile.delete();
        }

        Log.i(TAG, "Resources released");
    }

    // ========== 辅助方法 ==========

    private void notifyStatus(String status) {
        mMainHandler.post(() -> {
            if (mListener != null) {
                mListener.onStatusUpdate(status);
            }
        });
    }

    private void notifyError(String error) {
        mMainHandler.post(() -> {
            if (mListener != null) {
                mListener.onError(error);
            }
        });
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return mIsRecording.get();
    }
}

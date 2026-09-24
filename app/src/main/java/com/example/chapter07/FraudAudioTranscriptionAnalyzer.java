package com.example.chapter07;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FraudAudioTranscriptionAnalyzer：
 *   - 实时录制视频会议中的远端音频
 *   - 将音频切片（每 10 秒）调用 OpenAI Whisper API 转成文字
 *   - 把转录文字按时间顺序追加保存到本地 txt 文件（可指定目录）
 *   - 把每一段转录文字发给硬编码 API 的 OpenAI GPT 模型分析金融诈骗风险
 *   - 通过回调接口把分析结果推给 UI 层
 *
 * 与 {@link FraudDetectionAnalyzer} 的区别：
 *   - 本类会把转录文字**持久化保存到本地**（默认 cacheDir/FraudTranscripts/），
 *     便于事后回溯与审计；UI 上能看到原文
 *   - 分析 prompt 偏重"基于文字的诈骗模式"识别（关键词、剧本、特征话术）
 *
 * 使用方式：
 *   FraudAudioTranscriptionAnalyzer a = new FraudAudioTranscriptionAnalyzer(context, "API_KEY");
 *   a.setFraudAudioListener(...);
 *   a.setAudioReflector(mRemoteAudioReflector);
 *   a.start();   // 开始
 *   a.stop();    // 停止
 *   a.release(); // 销毁（onDestroy 调用）
 */
public class FraudAudioTranscriptionAnalyzer {

    private static final String TAG = "FraudAudioTranscription";

    // ========== OpenAI API（硬编码） ==========
    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY_HERE";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String WHISPER_API_URL = OPENAI_BASE_URL + "/audio/transcriptions";
    private static final String CHATGPT_API_URL = OPENAI_BASE_URL + "/chat/completions";
    private static final String WHISPER_MODEL = "whisper-1";
    private static final String GPT_MODEL = "gpt-4o-mini";
    // Whisper 支持把音频转录为带时间戳的段落，便于取证
    private static final String WHISPER_RESPONSE_FORMAT = "verbose_json";
    private static final String WHISPER_LANGUAGE = "zh"; // 中文优先；Whisper 会自动识别英文

    // ========== 录音切片配置 ==========
    private static final int SAMPLE_RATE = 16000;          // Whisper 需要 16kHz
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int SLICE_SECONDS = 10;           // 每 10 秒切一片
    private static final int SLICE_BYTES = SAMPLE_RATE * 2 * SLICE_SECONDS; // 单片 PCM 大小
    // 输入 PCM 假定为 48kHz/16bit/mono（WebRTC 默认输出），做 3:1 重采样到 16kHz
    private static final int WEBRTC_SR = 48000;
    private static final int RESAMPLE_RATIO = WEBRTC_SR / SAMPLE_RATE; // 3

    // ========== 分析 prompt（基于文字识别金融诈骗） ==========
    private static final String FRAUD_TEXT_PROMPT =
        "你是一名金融反诈骗专家。请基于以下视频会议通话的实时转录文字片段，判断该段内容是否存在金融诈骗风险。\n" +
        "重点关注：\n" +
        "1. 冒充公检法、银行、监管机构、领导等身份\n" +
        "2. 提到'安全账户'、'资金核查'、'洗钱'、'账户冻结'、'资产清查'等话术\n" +
        "3. 提到验证码、密码、身份证号、银行卡号、短信链接等敏感信息索取\n" +
        "4. 提到转账、汇款、垫付、保证金、退税、退款等金钱操作\n" +
        "5. 出现'高收益'、'稳赚不赔'、'内幕消息'、'杀猪盘'、'刷流水'、'套现'字样\n" +
        "6. 威胁、恐吓、'不配合就逮捕/起诉'、'影响征信'等高压话术\n" +
        "7. 引导下载未知 APP、开启屏幕共享、远程控制软件（AnyDesk/TeamViewer）\n" +
        "8. 假冒客服、快递理赔、贷款额度调整、退款退货、刷单兼职\n" +
        "9. 任何需要立刻做出金钱决策的紧迫语气\n" +
        "10. 任何明显异常或与通话上下文不符的剧本化话术\nn" +
        "若该片段为寒暄、闲聊或正常业务沟通，请给出无风险结论。\n\n" +
        "转录片段：\n[CONTENT]\n\n" +
        "请严格按照以下 JSON 格式返回（不要任何额外文字）：\n" +
        "{\n" +
        "  \"risk_level\": \"high/medium/low/none\",\n" +
        "  \"risk_score\": 0-100,\n" +
        "  \"detected_features\": [\"特征1\", \"特征2\"],\n" +
        "  \"summary\": \"对该片段的简要分析\",\n" +
        "  \"advice\": \"建议措施\"\n" +
        "}";

    // ========== 状态 ==========
    private final Context mContext;
    private final String mApiKey;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private final AtomicBoolean mIsBusy = new AtomicBoolean(false);

    // 累积 PCM 数据
    private final ByteBuffer mPcmBuffer = ByteBuffer.allocateDirect(SLICE_BYTES * 4).order(ByteOrder.LITTLE_ENDIAN);
    private int mPcmLen = 0;

    // 本地保存
    private final File mTranscriptDir;
    private File mCurrentTranscriptFile;
    private FileWriter mCurrentWriter;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 回调
    private FraudAudioListener mListener;

    // 音频源
    private WebRtcAudioTrackReflector mAudioReflector;

    // 工作线程
    private Thread mRecordThread;
    private final Object mLock = new Object();

    // 累积的所有转录片段（用于整体复审）
    private final List<String> mAllSegments = new ArrayList<>();

    // 统计
    private long mSliceCount = 0;
    private long mTranscribedCount = 0;
    private long mAnalyzedCount = 0;

    /** 文字诈骗检测结果 */
    public static class FraudTextResult {
        public String riskLevel;
        public int riskScore;
        public String[] detectedFeatures;
        public String summary;
        public String advice;
        public String rawResponse;
        public String transcriptText;   // 该片段的转录原文
        public long timestampMs;
        public boolean savedToLocal;   // 是否已写入本地
    }

    /** 回调 */
    public interface FraudAudioListener {
        /** 一段转录文字分析完成（包含文字 + 风险结论） */
        void onTranscriptionAnalyzed(FraudTextResult result);

        /** 仅实时显示转录片段（每 10 秒一次） */
        void onTranscription(String text);

        /** 错误 */
        void onError(String error);

        /** 状态 */
        void onStatusUpdate(String status);

        /** 已写入本地文件 */
        void onTranscriptSaved(File file, String appendedText);
    }

    public FraudAudioTranscriptionAnalyzer(Context context, String apiKey) {
        this.mContext = context.getApplicationContext();
        this.mApiKey = (apiKey == null || apiKey.isEmpty()) ? OPENAI_API_KEY : apiKey;
        // 默认保存到 cacheDir/FraudTranscripts/
        mTranscriptDir = new File(mContext.getCacheDir(), "FraudTranscripts");
        if (!mTranscriptDir.exists()) mTranscriptDir.mkdirs();
        rotateFileIfNeeded();
    }

    /**
     * 是否正在录制（外部用于校验启动是否真正生效）
     */
    public boolean isRunning() {
        return mIsRecording.get();
    }

    public void setFraudAudioListener(FraudAudioListener listener) {
        this.mListener = listener;
    }

    public void setAudioReflector(WebRtcAudioTrackReflector reflector) {
        this.mAudioReflector = reflector;
    }

    /**
     * 指定本地保存目录（可选；不调用则使用 cacheDir/FraudTranscripts）
     */
    public void setTranscriptSaveDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
        // 仅更新下一次轮换的目标
    }

    public boolean isRecording() { return mIsRecording.get(); }
    public long getSliceCount() { return mSliceCount; }
    public long getTranscribedCount() { return mTranscribedCount; }
    public long getAnalyzedCount() { return mAnalyzedCount; }
    public File getTranscriptDir() { return mTranscriptDir; }

    /**
     * 开始
     */
    public void start() {
        if (mIsRecording.compareAndSet(false, true)) {
            rotateFileIfNeeded();
            notifyStatus("开始音频录制 + 转录 + 诈骗检测...");
            mRecordThread = new Thread(this::recordLoop, "FraudAudio-Record");
            mRecordThread.start();
            Log.i(TAG, "FraudAudioTranscriptionAnalyzer started");
        }
    }

    /**
     * 停止（保留最后一次转录）
     */
    public void stop() {
        if (!mIsRecording.compareAndSet(true, false)) return;
        notifyStatus("停止录音，开始收尾分析...");
        try {
            if (mRecordThread != null) mRecordThread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 收尾：把残余 PCM 单独转一次
        mExecutor.submit(this::finalizeRemaining);
        closeWriter();
        Log.i(TAG, "FraudAudioTranscriptionAnalyzer stopped");
    }

    /**
     * 销毁（Activity onDestroy 调用）
     */
    public void release() {
        stop();
        mExecutor.shutdownNow();
        closeWriter();
        Log.i(TAG, "FraudAudioTranscriptionAnalyzer released");
    }

    // ============ 录制循环 ============

    private void recordLoop() {
        Log.i(TAG, "recordLoop begin");
        while (mIsRecording.get()) {
            try {
                if (mAudioReflector != null) {
                    byte[] pcm = mAudioReflector.readLatestAudio();
                    if (pcm != null && pcm.length > 0) {
                        // 重采样 48k -> 16k
                        byte[] pcm16k = resampleTo16kMono(pcm);
                        appendPcm(pcm16k);
                        // 累积够一片就处理
                        if (mPcmLen >= SLICE_BYTES) {
                            flushSlice();
                        }
                    }
                }
                Thread.sleep(80);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Log.e(TAG, "recordLoop err", t);
            }
        }
        Log.i(TAG, "recordLoop end");
    }

    private void appendPcm(byte[] data) {
        synchronized (mLock) {
            int cap = mPcmBuffer.capacity();
            if (mPcmLen + data.length > cap) {
                // 防止 buffer 满了丢旧数据
                int overflow = (mPcmLen + data.length) - cap;
                int keep = mPcmLen - overflow;
                if (keep < 0) keep = 0;
                byte[] tmp = new byte[keep];
                mPcmBuffer.position(0);
                mPcmBuffer.get(tmp, 0, keep);
                mPcmBuffer.clear();
                mPcmBuffer.put(tmp);
                mPcmLen = keep;
            }
            mPcmBuffer.position(mPcmLen);
            mPcmBuffer.put(data);
            mPcmLen += data.length;
        }
    }

    private void flushSlice() {
        final byte[] slice;
        synchronized (mLock) {
            slice = new byte[SLICE_BYTES];
            mPcmBuffer.position(0);
            mPcmBuffer.get(slice, 0, SLICE_BYTES);
            // 前移剩余
            int remain = mPcmLen - SLICE_BYTES;
            if (remain > 0) {
                byte[] tmp = new byte[remain];
                mPcmBuffer.position(SLICE_BYTES);
                mPcmBuffer.get(tmp, 0, remain);
                mPcmBuffer.clear();
                mPcmBuffer.put(tmp);
                mPcmLen = remain;
            } else {
                mPcmLen = 0;
                mPcmBuffer.clear();
            }
        }
        mSliceCount++;
        mExecutor.submit(() -> transcribeAndAnalyze(slice, false));
    }

    private void finalizeRemaining() {
        synchronized (mLock) {
            if (mPcmLen < SAMPLE_RATE * 2 * 2) {
                // 不到 2 秒就不分析了
                return;
            }
            byte[] slice = new byte[mPcmLen];
            mPcmBuffer.position(0);
            mPcmBuffer.get(slice, 0, mPcmLen);
            mPcmLen = 0;
            mPcmBuffer.clear();
            transcribeAndAnalyze(slice, true);
        }
    }

    // ============ 重采样 48k → 16k，简单 3:1 抽取平均 ============

    private byte[] resampleTo16kMono(byte[] input) {
        if (input == null || input.length < 2) return new byte[0];
        // 假定输入为 16-bit little-endian mono 48kHz
        int srcSamples = input.length / 2;
        int dstSamples = srcSamples / RESAMPLE_RATIO;
        byte[] out = new byte[dstSamples * 2];
        ByteBuffer in = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dstSamples; i++) {
            int srcIdx = i * RESAMPLE_RATIO;
            if (srcIdx >= srcSamples) break;
            short v = in.getShort(srcIdx * 2);
            ob.putShort(v);
        }
        return out;
    }

    // ============ WAV 封装 ============

    private byte[] pcmToWav(byte[] pcm) {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int dataLen = pcm.length;
        int totalLen = 36 + dataLen;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(44 + dataLen);
        try {
            // RIFF header
            baos.write("RIFF".getBytes());
            writeIntLE(baos, totalLen);
            baos.write("WAVE".getBytes());
            // fmt
            baos.write("fmt ".getBytes());
            writeIntLE(baos, 16);
            writeShortLE(baos, (short) 1); // PCM
            writeShortLE(baos, (short) CHANNELS);
            writeIntLE(baos, SAMPLE_RATE);
            writeIntLE(baos, byteRate);
            writeShortLE(baos, (short) (CHANNELS * BITS_PER_SAMPLE / 8));
            writeShortLE(baos, (short) BITS_PER_SAMPLE);
            // data
            baos.write("data".getBytes());
            writeIntLE(baos, dataLen);
            baos.write(pcm);
        } catch (IOException ignore) {}
        return baos.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream b, int v) {
        b.write(v & 0xff);
        b.write((v >> 8) & 0xff);
        b.write((v >> 16) & 0xff);
        b.write((v >> 24) & 0xff);
    }

    private static void writeShortLE(ByteArrayOutputStream b, short v) {
        b.write(v & 0xff);
        b.write((v >> 8) & 0xff);
    }

    // ============ 流程：转录 → 本地保存 → 风险分析 ============

    private void transcribeAndAnalyze(byte[] pcm, boolean isFinal) {
        if (mIsBusy.compareAndSet(false, true)) {
            try {
                // 1) PCM → WAV → multipart 上传给 Whisper
                byte[] wav = pcmToWav(pcm);
                JSONObject whisperResp = callWhisper(wav);
                if (whisperResp == null) {
                    notifyError("Whisper 转录失败（返回空）");
                    return;
                }
                String text = extractWhisperText(whisperResp);
                if (text == null) text = "";
                text = text.trim();
                mTranscribedCount++;

                if (text.isEmpty()) {
                    notifyStatus("片段无语音或无可识别内容");
                    return;
                }

                // 2) 推到 UI 显示原文
                final String finalText = text;
                mMainHandler.post(() -> {
                    if (mListener != null) mListener.onTranscription(finalText);
                });

                // 3) 写入本地文件（追加）
                boolean saved = appendTranscriptToFile(finalText, isFinal);
                synchronized (mLock) {
                    mAllSegments.add(finalText);
                }

                // 4) GPT 风险分析
                JSONObject chatResp = callChatCompletion(finalText);
                FraudTextResult r = new FraudTextResult();
                r.transcriptText = finalText;
                r.timestampMs = System.currentTimeMillis();
                r.savedToLocal = saved;
                if (chatResp != null) {
                    parseAndFill(r, chatResp);
                    mAnalyzedCount++;
                } else {
                    r.riskLevel = "unknown";
                    r.summary = "分析失败（API 返回空）";
                }

                final FraudTextResult fr = r;
                mMainHandler.post(() -> {
                    if (mListener != null) mListener.onTranscriptionAnalyzed(fr);
                });
            } catch (Throwable t) {
                Log.e(TAG, "transcribeAndAnalyze err", t);
                notifyError("转录/分析异常: " + t.getMessage());
            } finally {
                mIsBusy.set(false);
            }
        } else {
            // 跳过这一次切片（避免积压）
            Log.d(TAG, "skip slice: previous still running");
        }
    }

    // ============ Whisper API ============

    private JSONObject callWhisper(byte[] wavBytes) {
        String boundary = "----FraudAudioBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(WHISPER_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                // file 字段
                writeFormField(os, boundary, "model", WHISPER_MODEL);
                writeFormField(os, boundary, "language", WHISPER_LANGUAGE);
                writeFormField(os, boundary, "response_format", WHISPER_RESPONSE_FORMAT);
                // file
                os.write(("--" + boundary + "\r\n").getBytes());
                os.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".getBytes());
                os.write("Content-Type: audio/wav\r\n\r\n".getBytes());
                os.write(wavBytes);
                os.write("\r\n".getBytes());
                os.write(("--" + boundary + "--\r\n").getBytes());
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            Log.i(TAG, "whisper code=" + code);
            if (code < 200 || code >= 300) {
                notifyError("Whisper API " + code + ": " + body);
                return null;
            }
            return new JSONObject(body);
        } catch (Exception e) {
            Log.e(TAG, "callWhisper err", e);
            notifyError("Whisper 调用失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        os.write(value.getBytes("UTF-8"));
        os.write("\r\n".getBytes());
    }

    private String extractWhisperText(JSONObject resp) {
        try {
            if (resp.has("text")) return resp.optString("text", "");
            // verbose_json 模式下使用 segments
            JSONArray segs = resp.optJSONArray("segments");
            if (segs != null && segs.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < segs.length(); i++) {
                    JSONObject s = segs.optJSONObject(i);
                    if (s != null) sb.append(s.optString("text", "")).append(' ');
                }
                return sb.toString().trim();
            }
        } catch (Exception ignore) {}
        return "";
    }

    // ============ Chat Completion ============

    private JSONObject callChatCompletion(String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CHATGPT_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);

            JSONObject root = new JSONObject();
            root.put("model", GPT_MODEL);
            root.put("temperature", 0.2);

            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", FRAUD_TEXT_PROMPT.replace("[CONTENT]", text));
            messages.put(user);
            root.put("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(root.toString().getBytes("UTF-8"));
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            if (code < 200 || code >= 300) {
                notifyError("Chat API " + code + ": " + body);
                return null;
            }
            return new JSONObject(body);
        } catch (Exception e) {
            Log.e(TAG, "callChatCompletion err", e);
            notifyError("Chat API 调用失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void parseAndFill(FraudTextResult r, JSONObject resp) {
        try {
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.riskLevel = "unknown";
                r.summary = "API 返回无 choices";
                return;
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) {
                r.riskLevel = "unknown";
                r.summary = "API 返回无 message";
                return;
            }
            Object content = msg.opt("content");
            String contentText = String.valueOf(content);
            String jsonStr = extractJsonBlock(contentText);
            if (jsonStr != null) {
                JSONObject json = new JSONObject(jsonStr);
                r.riskLevel = json.optString("risk_level", "unknown");
                r.riskScore = json.optInt("risk_score", 0);
                JSONArray fa = json.optJSONArray("detected_features");
                if (fa != null) {
                    r.detectedFeatures = new String[fa.length()];
                    for (int i = 0; i < fa.length(); i++) r.detectedFeatures[i] = fa.optString(i);
                }
                r.summary = json.optString("summary", "");
                r.advice = json.optString("advice", "");
            } else {
                r.riskLevel = "unknown";
                r.summary = contentText;
            }
            r.rawResponse = resp.toString();
        } catch (Exception e) {
            Log.e(TAG, "parseAndFill err", e);
            r.riskLevel = "unknown";
            r.summary = "解析失败: " + e.getMessage();
        }
    }

    private static String extractJsonBlock(String text) {
        if (text == null) return null;
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s >= 0 && e > s) return text.substring(s, e + 1);
        return null;
    }

    // ============ 本地文件持久化 ============

    private void rotateFileIfNeeded() {
        try {
            closeWriter();
            String name = "transcript_" + mDateFmt.format(new Date()) + ".txt";
            File f = new File(mTranscriptDir, name);
            if (!f.exists()) {
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write("=== FraudAudioTranscription session " + name + " ===\n");
                }
            }
            mCurrentTranscriptFile = f;
            mCurrentWriter = new FileWriter(f, true);
        } catch (Exception e) {
            Log.e(TAG, "rotateFileIfNeeded err", e);
        }
    }

    private synchronized boolean appendTranscriptToFile(String text, boolean isFinal) {
        if (mCurrentWriter == null) return false;
        try {
            // 跨日自动轮换
            String today = mDateFmt.format(new Date());
            if (mCurrentTranscriptFile != null && !mCurrentTranscriptFile.getName().contains(today)) {
                rotateFileIfNeeded();
            }
            String line = "[" + mTimeFmt.format(new Date()) + (isFinal ? " (final)" : "") + "] " + text + "\n";
            mCurrentWriter.write(line);
            mCurrentWriter.flush();
            final File savedFile = mCurrentTranscriptFile;
            final String appended = text;
            mMainHandler.post(() -> {
                if (mListener != null) mListener.onTranscriptSaved(savedFile, appended);
            });
            return true;
        } catch (IOException e) {
            Log.e(TAG, "appendTranscriptToFile err", e);
            return false;
        }
    }

    private synchronized void closeWriter() {
        try {
            if (mCurrentWriter != null) {
                mCurrentWriter.flush();
                mCurrentWriter.close();
                mCurrentWriter = null;
            }
        } catch (Exception ignore) {}
    }

    // ============ 工具 ============

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private void notifyStatus(String msg) {
        Log.i(TAG, msg);
        mMainHandler.post(() -> { if (mListener != null) mListener.onStatusUpdate(msg); });
    }

    private void notifyError(String err) {
        Log.e(TAG, err);
        mMainHandler.post(() -> { if (mListener != null) mListener.onError(err); });
    }
}

package com.example.chapter07;  // 声明类所在的包名

// ========== Android 音视频相关 import ==========
import android.content.Context;                       // Android 上下文（本类未直接使用）
import android.media.AudioFormat;                    // 音频格式常量
import android.media.AudioRecord;                     // 麦克风录音 API
import android.media.MediaCodec;                     // Android 硬编解码器（用于 AAC 编码）
import android.media.MediaCodecInfo;                 // 编解码器信息
import android.media.MediaFormat;                    // 媒体格式描述
import android.media.MediaMuxer;                     // MP4 封装器
import android.os.Handler;                            // Handler：线程消息投递
import android.os.HandlerThread;                      // 带 Looper 的工作线程
import android.util.Log;                              // 日志工具

// ========== Java 标准库 ==========
import java.io.File;                                  // 文件对象
import java.nio.ByteBuffer;                           // NIO 字节缓冲
import java.util.concurrent.atomic.AtomicBoolean;    // 原子布尔

/**
 * 远端音频录制器。
 *
 * 两种数据源：
 * - 反射源（推荐）：通过 WebRtcAudioTrackReflector 从 WebRTC 内部抓取远端 PCM
 * - 麦克风源（兜底）：通过 AudioRecord 录制麦克风，间接采集扬声器声音
 *
 * 数据流：PCM → 累加（凑齐 AAC 帧大小）→ MediaCodec(AAC) → MediaMuxer(MP4)
 */
public class RemoteAudioRecorder {  // 远端音频录制器

    private static final String TAG = "RemoteAudioRecorder";  // 日志 TAG

    // ========== 音频录制参数 ==========
    private static final int SAMPLE_RATE = 48000;                                          // 采样率 48kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;                // 单声道输入
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;               // 16-bit PCM

    // ========== AAC 编码参数 ==========
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;               // AAC 编码
    private static final int CHANNEL_COUNT = 1;                                            // 单声道
    private static final int BIT_RATE = 128000;                                            // 128 kbps
    private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;  // AAC LC

    private volatile boolean mIsRecording = false;    // 是否正在录制
    private String mOutputPath;                       // 输出 MP4 路径

    private AudioRecord mAudioRecord;                 // 麦克风录音对象（兜底模式）
    private int mBufferSize;                          // AudioRecord 缓冲大小
    private int mActualSampleRate;                    // 实际采样率（探测到的）

    private MediaCodec mMediaCodec;                   // AAC 编码器
    private MediaMuxer mMediaMuxer;                   // MP4 封装器
    private int mAudioTrackIndex = -1;                // muxer 中的音频轨道索引
    private boolean mMuxerStarted = false;            // muxer 是否已 start

    private HandlerThread mRecordThread;              // 录制工作线程
    private Handler mRecordHandler;                   // 录制线程 Handler
    private final AtomicBoolean mProcessRecording = new AtomicBoolean(false);  // 控制主循环

    private final Object mLock = new Object();        // 同步锁

    private long mTotalBytesRecorded = 0;             // 累计录制字节数
    private long mRecordingStartTime = 0;             // 录制开始时间戳

    /**
     * mymymy 反射源（可选）。如果设置，则优先使用反射抓取远端 PCM，不使用麦克风。
     */
    private WebRtcAudioTrackReflector mPlayoutSource;

    /** 默认构造函数 */
    public RemoteAudioRecorder() {
    }

    /**
     * 设置反射源。设置后，录制线程不再打开麦克风，而是从 WebRTC 内部反射抓取远端 PCM。
     */
    public void setPlayoutSource(WebRtcAudioTrackReflector reflector) {
        this.mPlayoutSource = reflector;  // 保存引用
    }

    /**
     * 开始录制
     * @param outputPath 输出文件路径
     * @return 是否成功
     */
    public boolean startRecording(String outputPath) {
        synchronized (mLock) {                          // 加锁保护
            if (mIsRecording) {                         // 已在录制
                Log.w(TAG, "Already recording");
                return false;
            }

            this.mOutputPath = outputPath;              // 保存输出路径
            mTotalBytesRecorded = 0;                    // 清零统计

            try {
                // ===== 1. 根据数据源选择 buffer 策略 =====
                // mymymy 如果使用反射源，按 WebRTC 的 10ms 帧大小分配 buffer，并探测真实采样率
                if (mPlayoutSource != null) {            // 反射源模式
                    int frameSize = mPlayoutSource.getFrameSizeInBytes();   // 一帧字节数
                    int samplesPerFrame = mPlayoutSource.getSamplesPerFrame(); // 一帧采样数
                    // 采样率 = samplesPerFrame * 100 (10ms = 1/100 秒)
                    mActualSampleRate = samplesPerFrame * 100;
                    mBufferSize = frameSize;             // buffer 等于一帧大小
                    mAudioRecord = null;                 // 不使用 AudioRecord
                    Log.i(TAG, "Using reflector source: frameSize=" + frameSize
                            + " samples=" + samplesPerFrame
                            + " sampleRate=" + mActualSampleRate + "Hz (mono16bit assumed)");
                } else {                                  // 麦克风模式
                    mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                    if (mBufferSize == AudioRecord.ERROR || mBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "Invalid buffer size: " + mBufferSize);
                        return false;
                    }
                    mBufferSize = Math.max(mBufferSize, 8192);  // 至少 8192 bytes

                    mAudioRecord = new AudioRecord(
                            android.media.MediaRecorder.AudioSource.VOICE_CALL,  // VOICE_CALL 模式可录制上下行
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            mBufferSize * 4  // 缓冲为最小值的 4 倍
                    );

                    if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        // VOICE_CALL 不支持，回退到 MIC
                        Log.w(TAG, "VOICE_CALL failed (state=" + mAudioRecord.getState() + "), trying MIC");
                        mAudioRecord.release();
                        mAudioRecord = new AudioRecord(
                                android.media.MediaRecorder.AudioSource.MIC,    // MIC 模式（仅本地麦克风）
                                SAMPLE_RATE,
                                CHANNEL_CONFIG,
                                AUDIO_FORMAT,
                                mBufferSize * 4
                        );
                    }

                    if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord not initialized, state: " + mAudioRecord.getState());
                        if (mAudioRecord != null) {
                            mAudioRecord.release();
                            mAudioRecord = null;
                        }
                        return false;
                    }

                    mActualSampleRate = mAudioRecord.getSampleRate();  // 取实际采样率
                    Log.i(TAG, "AudioRecord initialized: sample rate=" + mActualSampleRate + ", buffer size=" + mBufferSize);
                }

                // ===== 2. 配置并启动 AAC 编码器 =====
                MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, mActualSampleRate, CHANNEL_COUNT);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                // mymymy 关键：KEY_MAX_INPUT_SIZE 必须 >= 1024 samples (AAC 一帧)，否则 MediaCodec 会崩
                // 用 16384 给足余量
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
                mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mMediaCodec.start();

                // ===== 3. 准备 MP4 输出 =====
                File outputFile = new File(outputPath);
                File dir = outputFile.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                Log.i(TAG, "MediaMuxer created: " + outputPath);

                // ===== 4. 启动工作线程 =====
                mRecordThread = new HandlerThread("AudioRecorderThread");
                mRecordThread.start();
                mRecordHandler = new Handler(mRecordThread.getLooper());

                mIsRecording = true;                       // 标记录制中
                mProcessRecording.set(true);               // 标记主循环运行
                mRecordingStartTime = System.currentTimeMillis();

                if (mAudioRecord != null) {                // 麦克风模式
                    mAudioRecord.startRecording();
                    Log.i(TAG, "Recording started (mic mode)");
                } else {                                    // 反射模式
                    Log.i(TAG, "Recording started (reflector mode)");
                }

                mRecordHandler.post(this::readAndEncodeAudio);  // post 主循环任务
                return true;

            } catch (Exception e) {                       // 异常
                Log.e(TAG, "Failed to start recording", e);
                releaseRecorderInternal();
                return false;
            }
        }
    }

    /**
     * 录制主循环：从数据源取 PCM → 累加 → AAC 编码 → 写入 MP4
     */
    private void readAndEncodeAudio() {
        Log.i(TAG, "readAndEncodeAudio started, mBufferSize=" + mBufferSize + ", sampleRate=" + mActualSampleRate
                + ", reflector=" + (mPlayoutSource != null));
        byte[] buffer = new byte[mBufferSize];             // 一帧 PCM buffer
        long presentationTimeUs = 0;                       // 当前帧时间戳
        long lastLogTime = System.currentTimeMillis();     // 上次日志时间
        int silentFrameCount = 0;                          // 连续静音帧计数
        int totalFramesRead = 0;                           // 累计读取的帧数
        int zeroByteReads = 0;                             // 累计零字节读取次数
        int errorReads = 0;                                // 累计错误读取次数

        // mymymy 反射模式：AAC 编码器要求每帧至少 1024 samples (2048 bytes mono16bit)。
        // WebRTC 给的每帧只有 10ms = 480 samples (960 bytes)，不够 AAC 编码。
        // 用 accumulator 攒 PCM，攒到至少 AAC_FRAME_BYTES 再送编码。
        final int AAC_FRAME_BYTES = 2048;                  // AAC 一帧字节数
        byte[] accum = new byte[AAC_FRAME_BYTES * 4];     // 累积 buffer（最大 ~80ms PCM）
        int accumLen = 0;                                   // accum 已用长度

        // 反射模式：一帧固定 10ms，按帧步进
        long frameDurationUs = mPlayoutSource != null ? mPlayoutSource.getFrameDurationUs() : 0L;

        // ===== 主循环 =====
        while (mProcessRecording.get()) {                  // 循环条件
            int bytesRead;                                  // 本次读取字节数

            // mymymy 反射源：从 WebRTC 内部抓远端 PCM（一帧 10ms）
            if (mPlayoutSource != null) {
                bytesRead = mPlayoutSource.readPlayoutFrame(buffer);  // 反射读取
            } else if (mAudioRecord != null) {            // 麦克风源
                bytesRead = mAudioRecord.read(buffer, 0, mBufferSize);
            } else {                                       // 都没有
                bytesRead = 0;
            }
            totalFramesRead++;                              // 累计读取次数

            if (bytesRead > 0) {                           // 成功读到数据
                zeroByteReads = 0;                          // 重置零读取计数
                boolean isSilent = isSilentBuffer(buffer, bytesRead); // 判断是否静音

                if (isSilent) {                            // 静音帧
                    silentFrameCount++;                     // 累计静音帧数
                    if (silentFrameCount % 50 == 0) {      // 每 50 帧警告一次
                        Log.w(TAG, "⚠️ Got " + silentFrameCount + " consecutive silent frames. Total frames read: " + totalFramesRead + ", total bytes: " + mTotalBytesRecorded);
                    }
                } else {                                    // 有声音的帧
                    if (silentFrameCount > 0) {            // 之前有静音
                        Log.i(TAG, "✅ Sound detected after " + silentFrameCount + " silent frames");
                    }
                    silentFrameCount = 0;                   // 重置静音计数
                }

                // 累加 PCM 到 accumulator
                if (accumLen + bytesRead <= accum.length) {        // 还有空间
                    System.arraycopy(buffer, 0, accum, accumLen, bytesRead); // 追加
                    accumLen += bytesRead;
                } else {                                            // 空间不够（实际不会发生）
                    Log.w(TAG, "accum overflow, dropping bytes");
                    accumLen = 0;                                    // 重置
                    System.arraycopy(buffer, 0, accum, 0, bytesRead); // 重新写入
                    accumLen = bytesRead;
                }

                // 时间戳按实际累计字节数计算
                int totalSamples = accumLen / 2;                     // 已累积 sample 数
                presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate;

                // 攒够 2 个 AAC 帧（4096 bytes，约 21ms@48k）就送一次编码
                while (accumLen >= AAC_FRAME_BYTES * 2) {            // 至少 2 个 AAC 帧
                    int sendBytes = AAC_FRAME_BYTES * 2;              // 每次送 2 个 AAC 帧
                    encodeData(accum, sendBytes, presentationTimeUs); // 编码
                    // 滑动窗口
                    System.arraycopy(accum, sendBytes, accum, 0, accumLen - sendBytes);
                    accumLen -= sendBytes;
                    mTotalBytesRecorded += sendBytes;
                }

                // 每 3 秒输出统计日志
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 3000) {
                    Log.i(TAG, "Recording... bytes: " + mTotalBytesRecorded + ", duration: " + (presentationTimeUs / 1000) + "ms, frames: " + totalFramesRead + ", silent: " + silentFrameCount + ", accum=" + accumLen);
                    lastLogTime = now;
                }
            } else if (bytesRead == 0) {                    // 读到 0 字节（数据源暂时没数据）
                zeroByteReads++;                            // 累计零读取
                if (zeroByteReads % 100 == 0) {            // 每 100 次警告一次
                    Log.e(TAG, "❌ AudioRecord.read() returned 0 for " + zeroByteReads + " consecutive times. The mic may be occupied by WebRTC or in speaker mode without remote audio.");
                }
            } else if (bytesRead < 0) {                     // 读取错误
                errorReads++;                                // 累计错误
                Log.e(TAG, "AudioRecord read error: " + bytesRead + " (ERROR_INVALID_OPERATION=" + AudioRecord.ERROR_INVALID_OPERATION + ", ERROR_BAD_VALUE=" + AudioRecord.ERROR_BAD_VALUE + ", ERROR_DEAD_OBJECT=" + AudioRecord.ERROR_DEAD_OBJECT + ")");
                if (errorReads > 10) break;                 // 错误超过 10 次退出循环
            }

            // mymymy 反射模式下，sleep 10ms 让出 CPU 和 WebRTC 内部线程同步
            if (mPlayoutSource != null) {
                try {
                    Thread.sleep(10);                       // sleep 10ms
                } catch (InterruptedException e) {
                    break;                                  // 被中断则退出
                }
            }
        }

        // ===== 退出时把剩余的 PCM flush 出去（补零对齐到 AAC_FRAME_BYTES） =====
        if (accumLen > 0) {                                // 还有残余数据
            int pad = (AAC_FRAME_BYTES - (accumLen % AAC_FRAME_BYTES)) % AAC_FRAME_BYTES;  // 需补零字节数
            if (accumLen + pad <= accum.length) {          // 不会越界
                for (int i = 0; i < pad; i++) accum[accumLen + i] = 0;  // 补零
                int totalSamples = (accumLen + pad) / 2;   // 含补零的 sample 数
                presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate;
                encodeData(accum, accumLen + pad, presentationTimeUs); // 编码尾部
                mTotalBytesRecorded += accumLen + pad;
            }
        }

        Log.i(TAG, "readAndEncodeAudio finished. total bytes: " + mTotalBytesRecorded + ", total frames: " + totalFramesRead + ", zero reads: " + zeroByteReads + ", errors: " + errorReads);
    }

    /**
     * 判断一段 PCM 是否为静音（采样值都低于阈值）。
     */
    private boolean isSilentBuffer(byte[] buffer, int length) {
        // 阈值从100降到30，避免漏掉小音量远端语音
        int threshold = 30;                                // 静音阈值（绝对值）
        for (int i = 0; i < length; i += 2) {              // 步长 2（16bit 一个 sample）
            if (i + 1 < length) {
                short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)); // 拼成有符号 short
                if (Math.abs(sample) > threshold) {        // 超过阈值
                    return false;                          // 非静音
                }
            }
        }
        return true;                                       // 是静音
    }

    /**
     * 把 PCM 数据送入 MediaCodec 编码并排空输出。
     */
    private void encodeData(byte[] pcmData, int length, long timestamp) {
        if (mMediaCodec == null) return;                   // 编码器为空

        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);  // 申请输入 buffer（最多等 10ms）
        if (inputBufferIndex >= 0) {                       // 申请成功
            ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();                       // 清空
                inputBuffer.put(pcmData, 0, length);       // 写入 PCM
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length, timestamp, 0);  // 提交
            }
        }

        drainEncoder(false);                               // 排空输出
    }

    /**
     * 排空 MediaCodec 输出 buffer，把 AAC 帧写入 MP4。
     */
    private void drainEncoder(boolean endOfStream) {
        if (mMediaCodec == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int timeout = endOfStream ? 10000 : 0;             // 结束模式等 10ms，否则非阻塞

        while (true) {
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, timeout);

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;                                 // 非结束模式直接退出
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mMuxerStarted) {                       // muxer 未启动
                    MediaFormat newFormat = mMediaCodec.getOutputFormat();
                    Log.i(TAG, "Encoder format changed: " + newFormat);
                    mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
                    mMediaMuxer.start();                    // 启动 muxer
                    mMuxerStarted = true;
                    Log.i(TAG, "MediaMuxer audio track started");
                }
            } else if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);

                if (outputBuffer != null && bufferInfo.size > 0 && mMuxerStarted) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    mMediaMuxer.writeSampleData(mAudioTrackIndex, outputBuffer, bufferInfo);
                }

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;                                  // 流结束，退出
                }
            }
        }
    }

    /**
     * 停止录制。停止 AudioRecord、刷编码器、关闭 muxer、释放资源。
     */
    public void stopRecording() {
        synchronized (mLock) {
            if (!mIsRecording) {
                return;
            }
            mIsRecording = false;
        }

        mProcessRecording.set(false);                       // 标记主循环退出

        try {
            if (mRecordThread != null) {
                mRecordThread.join(3000);                   // 等待线程结束，最多 3 秒
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (mLock) {
            try {
                if (mAudioRecord != null) {                 // 关闭麦克风
                    try {
                        mAudioRecord.stop();
                    } catch (Exception e) {
                        Log.e(TAG, "AudioRecord stop error", e);
                    }
                    mAudioRecord.release();
                    mAudioRecord = null;
                }

                if (mMediaCodec != null) {                  // 关闭编码器
                    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        // 提交一个空 buffer + EOS，通知编码器输入结束
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    drainEncoder(true);                     // 强制排空
                }

                // 输出录制统计
                long duration = System.currentTimeMillis() - mRecordingStartTime;
                Log.i(TAG, "Recording stopped. Total: " + mTotalBytesRecorded + " bytes, " + duration + "ms, "
                        + (duration > 0 ? (mTotalBytesRecorded * 1000 / duration / 1024) : 0) + " KB/s average");

            } catch (Exception e) {
                Log.e(TAG, "Error during stop recording", e);
            } finally {
                releaseRecorderInternal();                  // 释放所有资源
                Log.i(TAG, "Recording saved to: " + mOutputPath);
            }
        }
    }

    /**
     * 是否正在录制。
     */
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * 获取累计录制字节数。
     */
    public long getRecordedBytes() {
        return mTotalBytesRecorded;
    }

    /**
     * 释放所有资源（公开方法）。
     */
    public void release() {
        synchronized (mLock) {
            releaseRecorderInternal();
        }
    }

    /**
     * 内部统一释放方法。
     */
    private void releaseRecorderInternal() {
        if (mAudioRecord != null) {                         // 释放 AudioRecord
            try {
                mAudioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "AudioRecord release error", e);
            }
            mAudioRecord = null;
        }

        if (mRecordThread != null) {                        // 退出工作线程
            HandlerThread thread = mRecordThread;
            mRecordThread = null;
            thread.quitSafely();
        }

        if (mMediaCodec != null) {                          // 释放编码器
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "MediaCodec release error", e);
            }
            mMediaCodec = null;
        }

        if (mMediaMuxer != null) {                          // 释放 muxer
            try {
                if (mMuxerStarted) {
                    mMediaMuxer.stop();
                }
                mMediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "MediaMuxer release error", e);
            }
            mMediaMuxer = null;
            mMuxerStarted = false;
        }

        mAudioTrackIndex = -1;                              // 重置轨道索引
    }
}
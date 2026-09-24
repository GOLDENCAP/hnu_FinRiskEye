package com.example.chapter07;  // 声明类所在包名，与 Java 源文件目录对应

// ========== Android 音视频相关 import ==========
import android.media.AudioFormat;           // 音频格式常量（采样率、声道、编码格式）
import android.media.MediaCodec;            // Android 硬编解码器（用于 AAC 编码）
import android.media.MediaCodecInfo;        // 编解码器信息（profile 等）
import android.media.MediaFormat;           // 媒体格式描述（编码器参数）
import android.media.MediaMuxer;            // 媒体混合器（封装 MP4）
import android.os.Handler;                  // Handler：线程间消息投递
import android.os.HandlerThread;            // 带 Looper 的工作线程
import android.util.Log;                    // 日志工具

// ========== Java 标准库 ==========
import java.io.File;                        // 文件对象，用于创建输出目录
import java.nio.ByteBuffer;                 // NIO 字节缓冲，用于和 MediaCodec 交换数据
import java.util.concurrent.ConcurrentLinkedQueue;  // 线程安全的无界队列（WebRTC 线程 → 录制线程）
import java.util.concurrent.atomic.AtomicBoolean;   // 原子布尔，用于跨线程启停控制

/**
 * 本端音频录制器（myymymy 自定义模块）。
 *
 * 数据源：WebRtcAudioRecordSamplesBridge 提供的 10ms PCM 帧队列
 *        （通过 JavaAudioDeviceModule 的 SamplesReadyCallback 公开 API 注入，不是自己开 AudioRecord）
 *
 * 数据流：PCM 帧队列 → accumulator（攒够 AAC 帧）→ MediaCodec（AAC 编码）→ MediaMuxer（封装 MP4）
 *
 * 与 RemoteAudioRecorder 的区别：
 *   - 数据源不同：本端通过公开回调拿，远端通过反射 WebRTC 内部 AudioTrack 拿
 *   - 不再独立打开 AudioRecord，避免和 WebRTC 的麦克风抢占导致没声音
 */
public class LocalAudioRecorder {  // 本端音频录制器主类

    private static final String TAG = "LocalAudioRecorder";  // 日志 TAG，便于 logcat 过滤

    // ========== AAC 编码器配置常量 ==========
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;  // 编码格式：AAC
    private static final int CHANNEL_COUNT = 1;                              // 单声道（mono）
    private static final int BIT_RATE = 128000;                               // 比特率：128 kbps
    private static final int AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;  // AAC LC（低复杂度）profile

    /** AAC 一帧最小 1024 samples（mono16bit = 2048 bytes），编码器要求至少这么多 */
    private static final int AAC_FRAME_BYTES = 2048;

    // ========== 数据源：从 WebRtcAudioRecordSamplesBridge 注入 ==========
    private final WebRtcAudioRecordSamplesBridge mBridge;  // 本端音频桥接器，提供 PCM 帧入队接口

    /** 从 bridge 流入的 PCM 帧队列（10ms 一帧，约 960 bytes @48kHz mono） */
    private final ConcurrentLinkedQueue<byte[]> mPcmQueue = new ConcurrentLinkedQueue<>();

    // ========== 录制状态 ==========
    private volatile boolean mIsRecording = false;   // 是否正在录制（volatile 保证跨线程可见）
    private String mOutputPath;                       // 输出 MP4 文件路径

    // ========== MediaCodec / MediaMuxer 相关 ==========
    private MediaCodec mMediaCodec;          // AAC 编码器实例
    private MediaMuxer mMediaMuxer;          // MP4 封装器实例
    private int mAudioTrackIndex = -1;       // muxer 中的音频轨道索引（-1 表示尚未添加）
    private boolean mMuxerStarted = false;   // 是否已调用 muxer.start()

    // ========== 工作线程 ==========
    private HandlerThread mRecordThread;     // 录制工作线程（带 Looper）
    private Handler mRecordHandler;          // 录制线程的 Handler（用于投递编码任务）
    private final AtomicBoolean mProcessRecording = new AtomicBoolean(false);  // 控制录制主循环启停

    // ========== 同步与统计 ==========
    private final Object mLock = new Object();  // 保护 MediaCodec/MediaMuxer 的锁

    private int mActualSampleRate = 0;       // 实际采样率（探测到的，例如 48000）
    private long mTotalBytesRecorded = 0;    // 累计写入的字节数（用于统计）
    private long mRecordingStartTime = 0;    // 录制开始时间戳（毫秒）
    private int mFramesEncoded = 0;          // 累计编码的 AAC 帧数（用于统计）

    /**
     * 构造函数
     * @param bridge 本端音频桥接器，PCM 样本从它流入
     */
    public LocalAudioRecorder(WebRtcAudioRecordSamplesBridge bridge) {
        this.mBridge = bridge;  // 保存引用
    }

    /**
     * 开始录制。初始化编码器、封装器、启动采集与编码循环。
     * @param outputPath 输出 MP4 文件路径
     * @return 成功返回 true；失败（如已经在录制/初始化异常）返回 false
     */
    public boolean startRecording(String outputPath) {
        synchronized (mLock) {                  // 加锁保护状态
            if (mIsRecording) {                 // 已经在录制
                Log.w(TAG, "Already recording"); // 警告日志
                return false;                   // 返回失败
            }

            this.mOutputPath = outputPath;      // 保存输出路径
            mTotalBytesRecorded = 0;            // 清零累计字节
            mFramesEncoded = 0;                 // 清零累计帧数

            // 清空旧样本（避免上轮残留数据混入）
            mPcmQueue.clear();                  // 清空 PCM 队列
            mBridge.resetState();               // 重置 bridge 的采样率/声道数等探测状态

            try {
                // ===== 探测采样率：等最多 500ms 等第一次回调到达 =====
                int probe = probeSampleRate(500); // 调用探测方法
                if (probe <= 0) {                 // 探测失败（没等到回调）
                    Log.w(TAG, "No callback received in 500ms, using default 48000Hz"); // 警告日志
                    mActualSampleRate = 48000;   // 使用默认 48000Hz
                } else {
                    mActualSampleRate = probe;   // 使用探测到的采样率
                }

                Log.i(TAG, "Starting local recording: sampleRate=" + mActualSampleRate
                        + "Hz, output=" + outputPath);  // 记录启动信息

                // ===== 配置 AAC 编码器 =====
                MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, mActualSampleRate, CHANNEL_COUNT);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE);   // 设置 AAC profile
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);          // 设置比特率
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);       // 最大输入缓冲（足够容纳 2 帧 AAC）

                mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);       // 通过 MIME 类型创建编码器
                mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // 配置为编码器模式
                mMediaCodec.start();                                            // 启动编码器

                // ===== 准备 MP4 输出文件 =====
                File outputFile = new File(outputPath);    // 包装输出路径为 File
                File dir = outputFile.getParentFile();     // 取父目录
                if (dir != null && !dir.exists()) {        // 父目录不存在
                    dir.mkdirs();                          // 递归创建父目录
                }
                mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); // 创建 MP4 封装器
                Log.i(TAG, "MediaMuxer created: " + outputPath);  // 记录封装器创建成功

                // ===== 启动工作线程 =====
                mRecordThread = new HandlerThread("LocalAudioRecorderThread");  // 创建带 Looper 的线程
                mRecordThread.start();                                          // 启动线程
                mRecordHandler = new Handler(mRecordThread.getLooper());         // 拿到线程的 Handler

                mIsRecording = true;                                            // 标记正在录制
                mProcessRecording.set(true);                                    // 标记主循环运行
                mRecordingStartTime = System.currentTimeMillis();               // 记录开始时间

                // 通知 bridge 开始向队列里灌样本（mPcmQueue 是消费端）
                mBridge.setActiveSink(mPcmQueue);                                // 把队列设为 bridge 的输出目标

                Log.i(TAG, "Recording started (bridge mode)");                   // 记录启动完成
                mRecordHandler.post(this::readAndEncodeAudio);                   // post 主循环任务到录制线程
                return true;                                                    // 返回成功

            } catch (Exception e) {                                             // 任何异常都进入失败分支
                Log.e(TAG, "Failed to start recording", e);                      // 打印错误日志
                mBridge.setActiveSink(null);                                    // 撤销 bridge 输出
                releaseRecorderInternal();                                      // 释放已创建的 MediaCodec/MediaMuxer
                return false;                                                   // 返回失败
            }
        }
    }

    /**
     * 等待第一次样本回调到达，以确定实际采样率。
     * @param timeoutMs 超时时间（毫秒）
     * @return 探测到的采样率；超时返回 -1
     */
    private int probeSampleRate(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;  // 算出截止时间戳
        while (System.currentTimeMillis() < deadline) {          // 在截止时间前循环
            int sr = mBridge.getSampleRate();                    // 读取 bridge 当前已知采样率
            if (sr > 0) {                                        // 已经收到过至少一帧
                return sr;                                       // 返回探测到的采样率
            }
            try {
                Thread.sleep(20);                                 // 休眠 20ms 让出 CPU
            } catch (InterruptedException e) {                   // 被中断
                Thread.currentThread().interrupt();               // 重新设置中断标志
                break;                                           // 跳出循环
            }
        }
        return -1;                                               // 超时返回 -1
    }

    /**
     * 录制主循环：每 10ms 取一帧 PCM，攒够 AAC 帧后送编码器。
     * 运行在 mRecordHandler 所在的工作线程。
     */
    private void readAndEncodeAudio() {
        Log.i(TAG, "readAndEncodeAudio started, sampleRate=" + mActualSampleRate);  // 记录启动
        long presentationTimeUs = 0;   // 当前帧的展示时间戳（微秒），用于封装器写入
        long lastLogTime = System.currentTimeMillis();  // 上次输出统计日志的时间
        int totalFrames = 0;           // 累计入队的 PCM 帧数（10ms/帧）
        int emptyPolls = 0;            // 累计空轮询次数（队列空时）

        byte[] accum = new byte[AAC_FRAME_BYTES * 4];  // PCM 累积缓冲（最大 4 个 AAC 帧）
        int accumLen = 0;                                // accum 当前已用字节数

        // ===== 主循环 =====
        while (mProcessRecording.get()) {               // 循环条件：停止标志为 true 时持续
            byte[] frame = mPcmQueue.poll();            // 从队列取一帧（非阻塞，空时返回 null）
            if (frame == null) {                         // 队列空
                emptyPolls++;                            // 累计空轮询
                try {
                    Thread.sleep(10);                    // 休眠 10ms（WebRTC 一帧 PCM 也是 10ms）
                } catch (InterruptedException e) {
                    break;                              // 被中断则退出循环
                }
                continue;                                // 继续下一轮
            }
            emptyPolls = 0;                              // 取到帧，重置空轮询计数
            totalFrames++;                               // 累计入队帧数

            // ===== 把 PCM 累积到 accumulator =====
            if (accumLen + frame.length <= accum.length) {       // 还有空间
                System.arraycopy(frame, 0, accum, accumLen, frame.length); // 追加到 accumulator
                accumLen += frame.length;                          // 更新长度
            } else {                                              // 空间不够
                Log.w(TAG, "accum overflow, dropping bytes");      // 警告日志（理论上不会发生）
                accumLen = 0;                                      // 清空 accumulator
                System.arraycopy(frame, 0, accum, 0, frame.length); // 重新写入新帧
                accumLen = frame.length;
            }

            int totalSamples = accumLen / 2;                       // 已累积 samples 数（16bit = 2 bytes）
            presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate; // 计算时间戳（微秒）

            // ===== 攒够 2 个 AAC 帧（4096 bytes）就送一次编码 =====
            while (accumLen >= AAC_FRAME_BYTES * 2) {              // 至少 2 个 AAC 帧大小
                int sendBytes = AAC_FRAME_BYTES * 2;                // 每次送 2 个 AAC 帧
                encodeData(accum, sendBytes, presentationTimeUs);  // 送编码
                mFramesEncoded++;                                   // 累计编码次数
                System.arraycopy(accum, sendBytes, accum, 0, accumLen - sendBytes); // 滑动窗口
                accumLen -= sendBytes;                              // 更新长度
                mTotalBytesRecorded += sendBytes;                   // 累计字节数
            }

            // ===== 每 3 秒输出一次统计日志 =====
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 3000) {                        // 距离上次日志超过 3 秒
                Log.i(TAG, "Recording... bytes: " + mTotalBytesRecorded
                        + ", duration: " + (presentationTimeUs / 1000) + "ms"
                        + ", frames in: " + totalFrames
                        + ", frames encoded: " + mFramesEncoded
                        + ", accum=" + accumLen
                        + ", queue=" + mPcmQueue.size());         // 输出累计统计
                lastLogTime = now;                                  // 更新上次日志时间
            }
        }

        // ===== 退出时把剩余 PCM flush 出去（补零对齐到 AAC 帧） =====
        if (accumLen > 0) {                                        // 还有残余数据
            int pad = (AAC_FRAME_BYTES - (accumLen % AAC_FRAME_BYTES)) % AAC_FRAME_BYTES; // 计算需补零的字节数
            if (accumLen + pad <= accum.length) {                  // 不会越界
                for (int i = 0; i < pad; i++) accum[accumLen + i] = 0;  // 补零
                int totalSamples = (accumLen + pad) / 2;           // 含补零的 sample 数
                presentationTimeUs = (totalSamples * 1000000L) / mActualSampleRate; // 时间戳
                encodeData(accum, accumLen + pad, presentationTimeUs); // 编码尾部数据
                mFramesEncoded++;
                mTotalBytesRecorded += accumLen + pad;
            }
        }

        Log.i(TAG, "readAndEncodeAudio finished. total bytes: " + mTotalBytesRecorded
                + ", frames in: " + totalFrames
                + ", frames encoded: " + mFramesEncoded
                + ", empty polls: " + emptyPolls);                 // 退出日志
    }

    /**
     * 把 PCM 数据送入 MediaCodec 编码，并尝试排空编码输出。
     * @param pcmData PCM 数据缓冲
     * @param length 要编码的字节数
     * @param timestamp 该帧的展示时间戳（微秒）
     */
    private void encodeData(byte[] pcmData, int length, long timestamp) {
        if (mMediaCodec == null) return;                           // 编码器已释放则直接返回

        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000); // 申请一个可用的输入 buffer（最多等 10ms）
        if (inputBufferIndex >= 0) {                               // 申请成功
            ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex); // 拿到 buffer
            if (inputBuffer != null) {                             // buffer 非空
                inputBuffer.clear();                               // 清空旧数据
                inputBuffer.put(pcmData, 0, length);               // 写入 PCM 数据
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length, timestamp, 0); // 提交给编码器（flags=0）
            }
        }

        drainEncoder(false);                                      // 尝试排空编码输出（非结束模式）
    }

    /**
     * 排空 MediaCodec 输出 buffer，把编码后的 AAC 帧写入 MP4。
     * @param endOfStream 是否编码结束（true 时阻塞排空直到 EOS）
     */
    private void drainEncoder(boolean endOfStream) {
        if (mMediaCodec == null) return;                           // 编码器为空

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo(); // 编码输出帧信息
        int timeout = endOfStream ? 10000 : 0;                     // 结束模式下等待 10ms，否则非阻塞

        while (true) {                                             // 循环直到没有更多输出
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, timeout); // 取一个输出 buffer

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {  // 当前没有输出
                if (!endOfStream) break;                           // 非结束模式直接退出
                // 结束模式继续循环等待
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { // 输出格式变化（首次必触发）
                if (!mMuxerStarted) {                              // muxer 还未启动
                    MediaFormat newFormat = mMediaCodec.getOutputFormat(); // 获取编码器的输出格式
                    Log.i(TAG, "Encoder format changed: " + newFormat);
                    mAudioTrackIndex = mMediaMuxer.addTrack(newFormat); // 添加音频轨道到 muxer
                    mMediaMuxer.start();                           // 启动 muxer
                    mMuxerStarted = true;                          // 标记已启动
                    Log.i(TAG, "MediaMuxer audio track started");
                }
            } else if (outputBufferIndex >= 0) {                   // 正常的输出 buffer
                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex); // 拿到 buffer

                if (outputBuffer != null && bufferInfo.size > 0 && mMuxerStarted) { // 有效数据 + muxer 已启动
                    outputBuffer.position(bufferInfo.offset);      // 设置读取起点
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size); // 设置读取终点
                    mMediaMuxer.writeSampleData(mAudioTrackIndex, outputBuffer, bufferInfo); // 写入 muxer
                }

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false); // 释放 buffer（不渲染）

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { // 收到流结束标志
                    break;                                          // 退出循环
                }
            }
        }
    }

    /**
     * 停止录制。等待线程结束、发送 EOS、刷新 muxer、释放资源。
     */
    public void stopRecording() {
        synchronized (mLock) {                                      // 加锁保护
            if (!mIsRecording) {                                    // 没有在录制
                return;                                             // 直接返回
            }
            mIsRecording = false;                                   // 标记未在录制
        }

        // 关闭 sink 让 callback 停止入队
        mBridge.setActiveSink(null);                                // 撤销 bridge 输出目标
        mProcessRecording.set(false);                               // 标记主循环退出

        try {
            if (mRecordThread != null) {                            // 录制线程存在
                mRecordThread.join(3000);                           // 等待线程结束，最多 3 秒
            }
        } catch (InterruptedException e) {                          // 被中断
            Thread.currentThread().interrupt();                     // 保留中断标志
        }

        synchronized (mLock) {                                      // 再次加锁保护资源释放
            try {
                if (mMediaCodec != null) {                          // 编码器存在
                    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000); // 申请输入 buffer
                    if (inputBufferIndex >= 0) {                   // 申请成功
                        // 提交一个空 buffer + EOS 标志，通知编码器输入结束
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    drainEncoder(true);                             // 强制排空编码输出（结束模式）

                    mMediaCodec.stop();                             // 停止编码器
                    mMediaCodec.release();                          // 释放编码器
                    mMediaCodec = null;
                }

                if (mMediaMuxer != null) {                          // muxer 存在
                    if (mMuxerStarted) {                            // 已启动
                        mMediaMuxer.stop();                         // 停止 muxer（关闭 MP4 文件）
                    }
                    mMediaMuxer.release();                          // 释放 muxer
                    mMediaMuxer = null;
                    mMuxerStarted = false;                          // 重置标志
                    Log.i(TAG, "MediaMuxer stopped and released");  // 日志
                }
            } catch (Exception e) {                                 // 释放异常
                Log.e(TAG, "Error during stopRecording", e);        // 记录错误
            }
        }

        // 清空残余队列，避免下次录制混进旧样本
        mPcmQueue.clear();                                          // 清空 PCM 队列

        Log.i(TAG, "Recording stopped. total bytes: " + mTotalBytesRecorded
                + ", total frames encoded: " + mFramesEncoded);     // 输出录制总结
    }

    /**
     * 获取累计写入字节数。
     */
    public long getRecordedBytes() {
        return mTotalBytesRecorded;
    }

    /**
     * 内部统一释放资源方法（出错或停止时调用）。
     */
    private void releaseRecorderInternal() {
        try {
            if (mMediaCodec != null) {                              // 编码器存在
                try { mMediaCodec.stop(); } catch (Exception ignored) {} // 尝试停止
                mMediaCodec.release();                              // 释放
                mMediaCodec = null;
            }
            if (mMediaMuxer != null) {                              // muxer 存在
                if (mMuxerStarted) {                                // 已启动
                    try { mMediaMuxer.stop(); } catch (Exception ignored) {} // 尝试停止
                }
                mMediaMuxer.release();                              // 释放
                mMediaMuxer = null;
                mMuxerStarted = false;
            }
            if (mRecordThread != null) {                            // 录制线程存在
                try { mRecordThread.quitSafely(); } catch (Exception ignored) {} // 安全退出
                mRecordThread = null;
            }
        } catch (Exception e) {                                     // 释放异常
            Log.e(TAG, "releaseRecorderInternal error", e);         // 记录错误
        }
    }
}
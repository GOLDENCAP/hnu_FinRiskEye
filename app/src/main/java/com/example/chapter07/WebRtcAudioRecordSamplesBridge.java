package com.example.chapter07;  // 声明类所在的包名，与 Android 项目目录结构对应

import android.util.Log;  // Android 日志工具类，用于输出调试日志

import org.webrtc.audio.JavaAudioDeviceModule;  // WebRTC 的 Java 音频设备模块，包含 SamplesReadyCallback 接口

import java.util.concurrent.ConcurrentLinkedQueue;  // 线程安全的无界队列，基于链表实现
import java.util.concurrent.atomic.AtomicLong;      // Java 原子长整型，支持无锁的原子操作
import java.util.concurrent.atomic.AtomicReference; // 原子引用类型，保证引用的赋值/读取是原子的

/**
 * mymymy 本端音频样本桥接器（基于 JavaAudioDeviceModule 的公开 SamplesReadyCallback API）。
 *
 * 工作原理：
 *   - 在 JavaAudioDeviceModule.Builder 创建时通过 setSamplesReadyCallback 注入本类实例
 *   - WebRTC 内部 AudioRecordThread 每 10ms 调一次 onWebRtcAudioRecordSamplesReady，
 *     把 10ms PCM 副本传过来
 *   - 本类把样本放到 ConcurrentLinkedQueue，LocalAudioRecorder 的录音线程异步消费
 *
 * 优点（与反射 BB 方案相比）：
 *   - 完全使用公开 API，不反射任何包私有字段
 *   - 回调里的 byte[] 已经是副本，不需要与 AudioRecordThread 并发同步
 *   - 仅当 setActive(true) 时才入队，零开销非录制期
 */
public class WebRtcAudioRecordSamplesBridge
        implements JavaAudioDeviceModule.SamplesReadyCallback {  // 实现 WebRTC 的 SamplesReadyCallback 接口

    // 日志 TAG，便于在 logcat 中筛选本类的日志
    private static final String TAG = "WebRtcAudioRecordSamplesBridge";

    /**
     * 录音通道：非 null 时表示把样本入队；null 时丢弃（无录音）
     * 使用 AtomicReference 保证 setActiveSink() 和 onWebRtcAudioRecordSamplesReady()
     * 在不同线程执行时的内存可见性和原子性。
     */
    private final AtomicReference<ConcurrentLinkedQueue<byte[]>> mActiveSink = new AtomicReference<>(null);

    /** 当前帧的采样率（由第一次 callback 确定，例如 48000），volatile 保证多线程可见性 */
    private volatile int mSampleRate = 0;
    /** 当前帧的声道数（由第一次 callback 确定，例如 1 表示单声道） */
    private volatile int mChannelCount = 0;
    /** 上一次回调的样本字节数（10ms PCM mono16bit @ 48kHz = 960 bytes） */
    private volatile int mLastFrameBytes = 0;

    // 原子计数器：累计 WebRTC 回调过来的总帧数（不管是否入队）
    private final AtomicLong mTotalFramesObserved = new AtomicLong(0);
    // 原子计数器：累计因为未开启录音而丢弃的帧数
    private final AtomicLong mTotalFramesDropped = new AtomicLong(0);

    /** 在 builder 时调用一次，建立回调连接（空实现，逻辑都在回调中） */
    public WebRtcAudioRecordSamplesBridge() {
    }

    /**
     * 把样本流转给指定的目标队列。
     * 传入 null 表示关闭录音（队列不再接收新样本）。
     *
     * @param sink 目标队列；null 表示停止录音
     */
    public void setActiveSink(ConcurrentLinkedQueue<byte[]> sink) {
        // 原子地设置新的 sink，可能在 WebRTC 回调线程并发执行，必须用原子操作
        mActiveSink.set(sink);
        if (sink != null) {
            // 开启录音：打印日志提示
            Log.i(TAG, "Active sink set, will enqueue PCM samples");
        } else {
            // 关闭录音：打印日志提示
            Log.i(TAG, "Active sink cleared, samples will be dropped");
        }
    }

    /** 录制器启动时清空上一轮残留样本和参数，避免开头混入旧数据 */
    public void resetState() {
        mSampleRate = 0;                  // 重置采样率
        mChannelCount = 0;                // 重置声道数
        mLastFrameBytes = 0;              // 重置上次帧字节数
        mTotalFramesObserved.set(0);      // 原子地重置观测计数
        mTotalFramesDropped.set(0);       // 原子地重置丢弃计数
    }

    /**
     * WebRTC AudioRecordThread 的回调：每 10ms 调用一次，传入当前麦克风的 PCM 样本。
     * 该方法运行在 WebRTC 内部的 AudioRecordThread 线程上，必须快速返回避免阻塞采集。
     *
     * @param samples 音频样本数据，包含采样率、声道数、PCM 数据等
     */
    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
        // 原子地递增观测计数器，记录 WebRTC 总共回调了多少帧
        mTotalFramesObserved.incrementAndGet();

        // 第一次回调时记录采样率和声道数（用于录制时 wav header）
        // mSampleRate == 0 表示还没初始化过；samples.getSampleRate() > 0 防御性检查
        if (mSampleRate == 0 && samples.getSampleRate() > 0) {
            mSampleRate = samples.getSampleRate();     // 例如 48000
            mChannelCount = samples.getChannelCount(); // 例如 1（单声道）
            Log.i(TAG, "First callback: sampleRate=" + mSampleRate + " channels=" + mChannelCount);
        }

        // 原子地读取当前活跃的 sink（可能在其他线程被改为 null）
        ConcurrentLinkedQueue<byte[]> sink = mActiveSink.get();
        if (sink == null) {
            // 未开启本地录音：原子地递增丢弃计数并直接返回
            mTotalFramesDropped.incrementAndGet();
            return;
        }

        // 从 samples 中取出 PCM 数据
        byte[] data = samples.getData();
        // 防御性检查：数据为空或长度为 0 时直接返回（异常情况）
        if (data == null || data.length == 0) {
            return;
        }
        // 记录本次帧的字节数（用于监控）
        mLastFrameBytes = data.length;

        // 入队副本：samples.data 可能是 WebRTC 内部复用的 buffer，
        // 若直接入队引用，consumer 可能在 WebRTC 改写数据前/后读到不一致内容
        byte[] copy = new byte[data.length];                       // 分配新数组
        System.arraycopy(data, 0, copy, 0, data.length);           // 数组拷贝（比循环赋值快）
        // offer() 是非阻塞入队；返回 false 表示入队失败（理论上不会发生，因为队列无界）
        sink.offer(copy);
    }

    /** 获取采样率，第一次回调后才会有有效值 */
    public int getSampleRate() {
        return mSampleRate;
    }

    /** 获取声道数，第一次回调后才会有有效值 */
    public int getChannelCount() {
        return mChannelCount;
    }

    /** 获取上一次回调的样本字节数 */
    public int getLastFrameBytes() {
        return mLastFrameBytes;
    }

    /** 获取累计观测的帧数（WebRTC 总回调次数） */
    public long getTotalFramesObserved() {
        return mTotalFramesObserved.get();
    }

    /** 获取累计丢弃的帧数（因为未开启录音而丢弃） */
    public long getTotalFramesDropped() {
        return mTotalFramesDropped.get();
    }
}
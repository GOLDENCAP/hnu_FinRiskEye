package com.example.chapter07;  // 声明该类所在的包路径，与目录结构一致

import android.util.Log;  // Android 日志工具类，用于输出调试日志

import org.webrtc.audio.JavaAudioDeviceModule;  // WebRTC 的 Java 音频设备模块，管理音频输入输出

import java.lang.reflect.Field;  // 反射中的 Field 类，用于访问类的成员变量
import java.lang.reflect.Method;  // 反射中的 Method 类，用于访问类的方法
import java.nio.ByteBuffer;  // Java NIO 的字节缓冲区类，用于高效的字节操作

/**
 * 通过反射从 WebRTC 的 WebRtcAudioTrack 中读取远端音频 PCM 数据。
 *
 * 背景：WebRTC 内部的 WebRtcAudioTrack 类是包私有的（package-private），
 *       没有公开的 API 可以读取正在播放的音频数据。
 *       本类通过 Java 反射机制 "撬开" 这个类的内部状态，
 *       把正在送往 Android AudioTrack 播放的 PCM 数据 "截胡" 拷贝出来，
 *       用于远端音频录制（例如存为 wav 文件、做音频分析等）。
 *
 * WebRtcAudioTrack 的内部 byteBuffer 大小固定 = bytesPerFrame * (sampleRate / 100)
 * 即 10ms 的 PCM 数据量（48kHz mono16bit 下为 960 字节）。
 *
 * nativeGetPlayoutData(nativeAudioTrack, sizeInBytes) 把 playout ring buffer 尾部
 * 最新 sizeInBytes 数据拷贝到 byteBuffer 中。
 *
 * 为了避免破坏 WebRTC 自己的播放，必须严格按 size <= byteBuffer.capacity() 调用。
 */
public class WebRtcAudioTrackReflector {

    // 日志 TAG，便于在 logcat 中筛选本类的日志输出
    private static final String TAG = "WebRtcAudioTrackReflector";

    // 外部传入的 JavaAudioDeviceModule 引用（保留备用，可用于生命周期管理）
    private final JavaAudioDeviceModule mAdm;
    // WebRtcAudioTrack 实例，类型用 Object 是因为它是包私有类无法直接引用
    private final Object mAudioOutput;
    // WebRtcAudioTrack 内部 nativeAudioTrack 字段的值（C++ 层 AudioTrack 对象的句柄/指针）
    private final long mNativeAudioTrackHandle;

    // 反射获取的私有 native 方法 Method 对象（缓存避免每次重新查找）
    private final Method mNativeGetPlayoutData;
    // 反射获取的私有 byteBuffer 字段 Field 对象（缓存避免每次重新查找）
    private final Field mByteBufferField;

    /** 10ms PCM 帧的字节数（由 WebRtcAudioTrack 内部 byteBuffer capacity 决定） */
    private final int mFrameSizeInBytes;
    /** 当前帧大小对应的采样数（mono16bit 时 = mFrameSize / 2，因为每个采样占 2 字节） */
    private final int mSamplesPerFrame;
    /** 10ms 帧的时长，单位微秒（1ms = 1000us，10ms = 10000us） */
    private static final long FRAME_DURATION_US = 10000L;

    // 上一次 readPlayoutFrame 实际读取到的字节数（调试/监控用）
    private int mLastReadSize = 0;
    // 累计总共读取到的字节数（用于统计录制时长等）
    private long mTotalBytesRead = 0;

    /**
     * 构造函数：通过反射完成所有初始化工作
     * @param adm WebRTC 的 JavaAudioDeviceModule 实例
     * @throws Exception 反射过程中任何错误都抛出异常
     */
    public WebRtcAudioTrackReflector(JavaAudioDeviceModule adm) throws Exception {
        this.mAdm = adm;  // 保存外部传入的音频设备模块引用

        // ===== 第 1 步：反射读取 JavaAudioDeviceModule.audioOutput 字段（private final） =====
        // 通过 JavaAudioDeviceModule.class 获取该类的 Class 对象
        // getDeclaredField 可以获取任意访问修饰符的字段（包括 private）
        Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
        // 关闭 Java 语言访问检查，使得可以读取 private 字段（否则会抛 IllegalAccessException）
        audioOutputField.setAccessible(true);
        // 读取 adm 实例的 audioOutput 字段值，返回的是 Object 类型
        Object audioOutput = audioOutputField.get(adm);
        // 安全检查：若 audioOutput 为 null，说明 WebRTC 还没初始化好音频输出
        if (audioOutput == null) {
            throw new IllegalStateException("JavaAudioDeviceModule.audioOutput is null");
        }
        // 保存到成员变量，供后续方法使用
        this.mAudioOutput = audioOutput;

        // ===== 第 2 步：通过类名字符串获取 WebRtcAudioTrack 的 Class 对象 =====
        // 因为 WebRtcAudioTrack 是包私有类（package-private），无法直接 import，
        // 所以只能用 Class.forName() 这种字符串方式拿到它的 Class 对象
        Class<?> webRtcAudioTrackClass = Class.forName("org.webrtc.audio.WebRtcAudioTrack");
        // 验证 audioOutput 确实是一个 WebRtcAudioTrack 实例，防止类型不匹配
        // isInstance 相当于 instanceof，但不需要 import 类
        if (!webRtcAudioTrackClass.isInstance(audioOutput)) {
            throw new IllegalStateException("audioOutput is not a WebRtcAudioTrack: " + audioOutput.getClass().getName());
        }

        // ===== 第 3 步：反射读取 nativeAudioTrack 字段（private long） =====
        // nativeAudioTrack 是 WebRtcAudioTrack 内部的一个 long 型字段，
        // 实际上是 C++ 层 AudioTrack 对象的指针/句柄
        Field nativeAudioTrackField = webRtcAudioTrackClass.getDeclaredField("nativeAudioTrack");
        // 同样关闭访问检查
        nativeAudioTrackField.setAccessible(true);
        // 因为字段是 long 类型，用 getLong() 高效读取（避免自动装箱）
        long handle = nativeAudioTrackField.getLong(audioOutput);
        // 若句柄为 0，说明底层 C++ AudioTrack 还未创建（WebRTC 还没启动播放）
        if (handle == 0L) {
            throw new IllegalStateException("WebRtcAudioTrack.nativeAudioTrack is 0 (audio track not yet created)");
        }
        // 保存 native 句柄，后续 native 方法调用需要传入
        this.mNativeAudioTrackHandle = handle;

        // ===== 第 4 步：拿到私有 native 方法的 Method 对象 =====
        // nativeGetPlayoutData 是 WebRtcAudioTrack 内部的一个 private native 方法，
        // 签名：nativeGetPlayoutData(long nativeAudioTrack, int sizeInBytes)
        // 作用：把 playout ring buffer 中最新 sizeInBytes 字节的 PCM 数据
        //       拷贝到 WebRtcAudioTrack 的内部 byteBuffer 中
        mNativeGetPlayoutData = webRtcAudioTrackClass.getDeclaredMethod(
                "nativeGetPlayoutData", long.class, int.class);  // long.class 表示 long 类型参数
        // 关闭访问检查，否则调用会抛 IllegalAccessException
        mNativeGetPlayoutData.setAccessible(true);

        // ===== 第 5 步：反射拿到内部 byteBuffer 字段 =====
        // byteBuffer 是 WebRtcAudioTrack 内部的 ByteBuffer 字段，
        // 用于临时存放从 native ring buffer 中拷贝出来的 PCM 数据
        mByteBufferField = webRtcAudioTrackClass.getDeclaredField("byteBuffer");
        mByteBufferField.setAccessible(true);  // 同样关闭访问检查

        // ===== 第 6 步：计算一帧的字节数（必须是 10ms PCM 的实际大小） =====
        // 读取 byteBuffer 字段的实际值
        ByteBuffer bb = (ByteBuffer) mByteBufferField.get(audioOutput);
        // 安全检查：若 byteBuffer 为 null，说明 initPlayout 还没被调用过
        if (bb == null) {
            throw new IllegalStateException("WebRtcAudioTrack.byteBuffer is null (initPlayout not yet called)");
        }
        // capacity() 返回 ByteBuffer 的总容量，即一帧的字节数
        // 例如 48kHz mono16bit：48000 * 0.01 * 2 = 960 字节
        this.mFrameSizeInBytes = bb.capacity();
        // 假设是单声道 16bit（每个采样占 2 字节），所以采样数 = 字节数 / 2
        this.mSamplesPerFrame = mFrameSizeInBytes / 2;
        // 输出初始化日志，方便调试确认参数
        Log.i(TAG, "WebRtcAudioTrackReflector ready, nativeAudioTrack=" + mNativeAudioTrackHandle
                + ", frameSizeBytes=" + mFrameSizeInBytes
                + ", samplesPerFrame=" + mSamplesPerFrame);
    }

    /**
     * 抓取一帧（10ms）的最新 PCM 数据。
     * @param buffer 输出缓冲区，长度必须 >= getFrameSizeInBytes()
     * @return 实际写入的字节数；返回 0 表示 native 侧暂时没数据
     */
    public int readPlayoutFrame(byte[] buffer) {
        // 前置检查：如果 audioOutput 或 native 句柄无效，直接返回 0
        if (mAudioOutput == null || mNativeAudioTrackHandle == 0L) {
            return 0;
        }

        try {
            // ===== 关键：用 duplicate() 创建 ByteBuffer 视图，绝对不修改原 buffer 的 position =====
            // 重新读取 byteBuffer 字段（WebRTC 可能会重新分配）
            ByteBuffer bb = (ByteBuffer) mByteBufferField.get(mAudioOutput);
            // 若 byteBuffer 还没准备好，返回 0
            if (bb == null) return 0;

            // 要读取的字节数等于一帧大小（即 byteBuffer 的 capacity）
            int sizeToRead = mFrameSizeInBytes;
            // 安全检查：调用方传入的 buffer 必须足够大，否则返回 0
            if (sizeToRead > buffer.length) {
                return 0;
            }

            // 调用 nativeGetPlayoutData 让 native 把最新 playout PCM 写入 bb
            // 第一个参数 null：因为这是 native 静态方法（虽然定义在实例上但调用时不依赖 this）
            // 第二个参数：native 句柄
            // 第三个参数：要拷贝的字节数
            mNativeGetPlayoutData.invoke(null, mNativeAudioTrackHandle, sizeToRead);

            // 用 duplicate() 创建视图，从视图读取不会破坏原 buffer 的 position
            // 这非常重要！WebRTC 的 AudioTrackJavaThread 也使用同一个 byteBuffer，
            // 它期望 byteBuffer 的 position 保持在 0，若我们改变了 position 会破坏播放
            ByteBuffer view = bb.duplicate();  // 创建共享底层数据但独立指针的视图
            view.position(0);  // 把视图的读指针重置到 0
            // 取要读取的字节数和视图剩余可读字节数的较小值（防御性编程）
            int got = Math.min(sizeToRead, view.remaining());
            // 从视图读取数据到传入的 byte[] 缓冲区
            view.get(buffer, 0, got);

            // 更新统计信息
            mLastReadSize = got;        // 记录本次读取的字节数
            mTotalBytesRead += got;      // 累计总字节数
            return got;                  // 返回实际读取的字节数

        } catch (Exception e) {
            // 任何反射调用出错都不应崩溃，只记录日志并返回 0
            Log.e(TAG, "readPlayoutFrame failed", e);
            return 0;
        }
    }

    /**
     * 返回一帧（10ms）的字节数。RemoteAudioRecorder 必须按此大小分配 buffer。
     */
    public int getFrameSizeInBytes() {
        return mFrameSizeInBytes;  // 返回一帧的字节数（例如 960）
    }

    /**
     * 返回一帧（10ms）的采样数。
     */
    public int getSamplesPerFrame() {
        return mSamplesPerFrame;  // 返回一帧的采样数（例如 480）
    }

    /**
     * 返回一帧的时长（微秒）。固定为 10000us（即 10ms）。
     */
    public long getFrameDurationUs() {
        return FRAME_DURATION_US;
    }

    /**
     * 返回上一次 readPlayoutFrame 实际写入的字节数。可用于监控是否有丢帧。
     */
    public int getLastReadSize() {
        return mLastReadSize;
    }

    /**
     * 返回累计抓取字节数。可用于统计已录制的音频数据总量。
     */
    public long getTotalBytesRead() {
        return mTotalBytesRead;
    }

    /**
     * 读取最新的音频数据。
     * 内部维护一个缓冲区，持续从 WebRTC 获取最新音频数据。
     * @return 最新的音频数据字节数组，如果暂无数据返回 null
     */
    public byte[] readLatestAudio() {
        if (mAudioOutput == null || mNativeAudioTrackHandle == 0L) {
            return null;
        }

        try {
            // 重新读取 byteBuffer 字段
            ByteBuffer bb = (ByteBuffer) mByteBufferField.get(mAudioOutput);
            if (bb == null) return null;

            int sizeToRead = mFrameSizeInBytes;

            // 调用 nativeGetPlayoutData 让 native 把最新 playout PCM 写入 bb
            mNativeGetPlayoutData.invoke(null, mNativeAudioTrackHandle, sizeToRead);

            // 用 duplicate() 创建视图，从视图读取不会破坏原 buffer 的 position
            ByteBuffer view = bb.duplicate();
            view.position(0);

            // 读取数据到字节数组
            byte[] buffer = new byte[sizeToRead];
            view.get(buffer, 0, sizeToRead);

            return buffer;

        } catch (Exception e) {
            Log.e(TAG, "readLatestAudio failed", e);
            return null;
        }
    }
}

package com.example.chapter07;  // 声明类所在的包路径

import android.util.Log;  // Android 日志工具类

// 导入 WebRTC 视频相关接口
import org.webrtc.VideoFrame;  // 视频帧对象
import org.webrtc.VideoSink;    // 视频接收器接口

import java.nio.ByteBuffer;  // NIO 字节缓冲区

/**
 * 本地视频特效处理器：拦截 WebRTC 视频帧，应用灰度或暖色调效果后传递给下游。
 *
 * 支持的特效：
 *   - 灰度效果：将 UV 分量固定为 128（中性色），保留 Y（亮度），实现黑白效果
 *   - 暖色调效果：通过调整 UV 分量让画面偏黄/偏红
 *   - 饱和度调整：通过控制 UV 偏离中性值的程度
 *
 * 工作流程：
 *   1. WebRTC VideoTrack 回调 onFrame()
 *   2. 提取 I420 数据到 byte[]
 *   3. 应用已启用的特效
 *   4. 若有 mCallback，再调用一次（业务层可以叠加自定义效果）
 *   5. 把处理后的帧传递给下游 sink
 */
public class YuvFrameProcessorWithEffect implements VideoSink {  // 实现 WebRTC 视频接收接口

    // 日志 TAG
    private static final String TAG = "YuvFrameProcessorWithEffect";
    // 调试模式开关
    private static final boolean DEBUG = true;

    // 装饰器模式：下游的真实 sink
    private final VideoSink mTargetSink;
    // 业务层回调（可为 null）
    private YuvFrameCallback mCallback;

    // ===== 复用缓冲区（避免每帧分配造成 GC 压力） =====
    private byte[] mYData;  // Y 平面数据
    private byte[] mUData;  // U 平面数据
    private byte[] mVData;  // V 平面数据
    // 当前分辨率（用于判断是否需要重新分配缓冲区）
    private int mCurrentWidth = 0;
    private int mCurrentHeight = 0;

    // ===== 特效参数（volatile 保证跨线程可见性，可在任意线程修改） =====
    /** 是否启用暖色调效果 */
    private volatile boolean mEffectEnabled = false;
    /** 暖色调强度：0.0~1.0，0 表示无效果，1 表示最强暖色 */
    private volatile float mWarmth = 0.3f;
    /** 饱和度：1.0 为原始饱和度，>1 更鲜艳，<1 更灰 */
    private volatile float mSaturation = 1.1f;
    /** 是否启用灰度效果 */
    private volatile boolean mGrayscaleEnabled = false;

    /**
     * 构造函数
     * @param targetSink 下游 VideoSink
     */
    public YuvFrameProcessorWithEffect(VideoSink targetSink) {
        this.mTargetSink = targetSink;  // 保存下游引用
    }

    /**
     * 注册 YUV 帧回调（可叠加在特效处理之后）
     */
    public void setYuvFrameCallback(YuvFrameCallback callback) {
        this.mCallback = callback;
    }

    /**
     * 启用/禁用特效（暖色调）
     */
    public void setEffectEnabled(boolean enabled) {
        this.mEffectEnabled = enabled;
    }

    /**
     * 设置暖色调强度
     * @param warmth 范围 0.0~1.0
     */
    public void setWarmth(float warmth) {
        this.mWarmth = warmth;
    }

    /**
     * 设置饱和度
     * @param saturation 1.0 为原始饱和度，>1 更鲜艳，<1 更灰
     */
    public void setSaturation(float saturation) {
        this.mSaturation = saturation;
    }

    /**
     * 启用/禁用灰度效果（黑白）
     */
    public void setGrayscaleEnabled(boolean enabled) {
        this.mGrayscaleEnabled = enabled;
    }

    /**
     * WebRTC 每帧回调入口
     * @param frame WebRTC 传入的视频帧
     */
    @Override
    public void onFrame(VideoFrame frame) {
        // 快速路径：没有回调、特效未启用、灰度未启用时，直接透传（零开销）
        if (mCallback == null && !mEffectEnabled && !mGrayscaleEnabled) {
            mTargetSink.onFrame(frame);  // 直接传给下游
            return;
        }

        // 转换为 I420 格式（YUV420 planar）
        VideoFrame.I420Buffer i420Buffer = null;
        try {
            i420Buffer = frame.getBuffer().toI420();  // 获取 I420 buffer
        } catch (Exception e) {
            // 异常时打印日志并透传原帧（不中断业务）
            Log.e(TAG, "Failed to get I420 buffer", e);
            mTargetSink.onFrame(frame);
            return;
        }

        // 防御性检查：i420Buffer 为 null 时透传
        if (i420Buffer == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        try {  // try-finally 确保 i420Buffer 一定被释放
            // ===== 1. 提取分辨率和步长信息 =====
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int yStride = i420Buffer.getStrideY();
            int uvStride = i420Buffer.getStrideU();
            // U/V 平面的高度 = Y 平面高度的一半（向上取整）
            int uvHeight = (height + 1) / 2;

            // 计算 stride 大小的 buffer 容量
            int strideYSize = yStride * height;
            int strideUVSize = uvStride * uvHeight;

            // ===== 2. 分配或复用缓冲区（避免每帧分配造成 GC 压力） =====
            if (mYData == null || mYData.length < strideYSize) {
                mYData = new byte[strideYSize];
                mUData = new byte[strideUVSize];
                mVData = new byte[strideUVSize];
                mCurrentWidth = width;
                mCurrentHeight = height;
            }

            // ===== 3. 读取数据到 byte[] =====
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();

            // 把 position 重置到 0，准备从头读
            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();

            // 用 min(remaining, stride) 防御性读取
            int actualReadYSize = Math.min(yBuffer.remaining(), strideYSize);
            yBuffer.get(mYData, 0, actualReadYSize);

            int actualReadUSize = Math.min(uBuffer.remaining(), strideUVSize);
            uBuffer.get(mUData, 0, actualReadUSize);

            int actualReadVSize = Math.min(vBuffer.remaining(), strideUVSize);
            vBuffer.get(mVData, 0, actualReadVSize);

            // ===== 4. 应用特效 =====
            boolean modified = false;  // 标记帧是否被修改

            // 灰度效果：将 UV 分量设为中性值 128（YUV 中 128 表示无色偏）
            if (mGrayscaleEnabled) {
                for (int i = 0; i < strideUVSize; i++) {
                    mUData[i] = (byte) 128;  // U = 128（无蓝色偏移）
                    mVData[i] = (byte) 128;  // V = 128（无红色偏移）
                }
                modified = true;  // 标记已修改
            }

            // 暖色调效果：调整 UV 分量使画面偏黄/偏红
            if (mEffectEnabled) {
                applyWarmEffect(mUData, mVData, strideUVSize, mWarmth, mSaturation);
                modified = true;  // 标记已修改
            }

            // ===== 5. 业务层回调（可以叠加自定义效果） =====
            if (mCallback != null) {
                // 回调返回是否修改了数据
                boolean callbackModified = mCallback.onYuvFrame(mYData, mUData, mVData, width, height, yStride, uvStride);
                if (callbackModified) {
                    modified = true;  // 业务层修改了，标记已修改
                }
            }

            // ===== 6. 把处理后的帧传递给下游 =====
            if (modified) {
                // 帧被修改：用修改后的数据构造新帧
                SimpleI420Buffer modifiedBuffer = new SimpleI420Buffer(
                        mYData, mUData, mVData, width, height, yStride, uvStride);
                VideoFrame modifiedFrame = new VideoFrame(modifiedBuffer, frame.getRotation(), frame.getTimestampNs());
                mTargetSink.onFrame(modifiedFrame);
                modifiedFrame.release();  // 释放引用
            } else {
                // 帧未被修改：直接透传原帧（先 retain 增加引用计数）
                frame.retain();
                mTargetSink.onFrame(frame);
            }

        } finally {
            // 释放 i420Buffer（native 内存）
            i420Buffer.release();
        }
    }

    /**
     * 应用暖色调效果。
     *
     * 原理：
     *   - YUV 色彩空间中，V 表示红色色度，U 表示蓝色色度
     *   - 暖色调 = 增加 V（更红）、减少 U（更黄）
     *   - 饱和度调整 UV 偏离中性值 128 的程度
     *
     * @param uData U 平面数据（会被修改）
     * @param vData V 平面数据（会被修改）
     * @param size 要处理的字节数
     * @param warmth 暖色调强度 0.0~1.0
     * @param saturation 饱和度（1.0 为原始）
     */
    private void applyWarmEffect(byte[] uData, byte[] vData, int size, float warmth, float saturation) {
        // 遍历每个 UV 采样点
        for (int i = 0; i < size; i++) {
            // 把 byte 转成无符号 int（& 0xFF）进行计算
            int u = uData[i] & 0xFF;
            int v = vData[i] & 0xFF;

            // ===== V（红色）调整 =====
            // (v - 128) 是当前 V 偏离中性值的程度
            // (1.0f - saturation) 减小偏离（降低饱和度）
            // warmth 直接增加偏离（增强暖色）
            float delta = (v - 128) * (1.0f - saturation) + (v - 128) * warmth;
            int newV = (int) (v + delta);
            // 把结果限制在 0~255 范围内（clamp）
            newV = Math.max(0, Math.min(255, newV));

            // ===== U（蓝色）调整 =====
            // 暖色调下，U 应该减小（去掉蓝色，让画面偏黄）
            float uDelta = (u - 128) * (1.0f - saturation) - (128 - u) * warmth * 0.3f;
            int newU = (int) (u + uDelta);
            newU = Math.max(0, Math.min(255, newU));

            // 把计算结果写回 byte 数组
            uData[i] = (byte) newU;
            vData[i] = (byte) newV;
        }
    }

    /**
     * 简单的 I420Buffer 实现（同 YuvFrameProcessor 内的版本）。
     */
    private static class SimpleI420Buffer implements VideoFrame.I420Buffer {
        private final byte[] yData;       // Y 数据
        private final byte[] uData;       // U 数据
        private final byte[] vData;       // V 数据
        private final int width;          // 宽度
        private final int height;         // 高度
        private final int yStride;        // Y 行步长
        private final int uvStride;       // U/V 行步长

        SimpleI420Buffer(byte[] y, byte[] u, byte[] v, int width, int height, int yStride, int uvStride) {
            this.yData = y;
            this.uData = u;
            this.vData = v;
            this.width = width;
            this.height = height;
            this.yStride = yStride;
            this.uvStride = uvStride;
        }

        @Override
        public int getWidth() { return width; }

        @Override
        public int getHeight() { return height; }

        @Override
        public ByteBuffer getDataY() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(yData.length);
            buffer.put(yData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public ByteBuffer getDataU() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(uData.length);
            buffer.put(uData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public ByteBuffer getDataV() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(vData.length);
            buffer.put(vData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public int getStrideY() { return yStride; }

        @Override
        public int getStrideU() { return uvStride; }

        @Override
        public int getStrideV() { return uvStride; }

        @Override
        public VideoFrame.I420Buffer toI420() { return this; }

        @Override
        public void retain() {}

        @Override
        public void release() {}

        @Override
        public VideoFrame.Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
                                              int scaleWidth, int scaleHeight) {
            return null;
        }
    }
}
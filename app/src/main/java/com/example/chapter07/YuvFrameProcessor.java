package com.example.chapter07;  // 声明类所在的包路径

// 导入 WebRTC 的视频相关接口
import org.webrtc.VideoFrame;  // WebRTC 视频帧对象，包含 I420 buffer 和旋转角度
import org.webrtc.VideoSink;    // WebRTC 视频接收器接口，addSink 后会收到 onFrame 回调

import java.nio.ByteBuffer;  // Java NIO 字节缓冲区

/**
 * YUV 帧处理器：拦截 WebRTC 的视频帧，提取 I420 数据暴露给业务层，
 * 然后继续传递给下游的 VideoSink（形成责任链）。
 *
 * 工作流程：
 *   1. WebRTC VideoTrack 回调 onFrame() 传入视频帧
 *   2. 若有注册的回调（mCallback），提取 I420 数据并调用 mCallback.onYuvFrame()
 *   3. 若回调返回 true（表示已修改），用修改后的数据构造新帧传给下游
 *   4. 若返回 false（未修改），则直接把原帧 retain() 后传给下游
 *   5. 整个过程形成"装饰器"模式：在不破坏原有链路的情况下插入处理逻辑
 */
public class YuvFrameProcessor implements VideoSink {

    // 装饰器模式：下游的真实 sink，最终的帧会传递给它
    private final VideoSink mTargetSink;
    // 业务层注册的回调（可为 null），用于让外部消费/修改 YUV 数据
    private YuvFrameCallback mCallback;

    // ===== 复用缓冲区（避免每帧都 new byte[]，提升性能并减少 GC 压力） =====
    private byte[] mYData;  // Y 平面数据缓冲区
    private byte[] mUData;  // U 平面数据缓冲区（色度蓝色分量）
    private byte[] mVData;  // V 平面数据缓冲区（色度红色分量）
    // 当前缓冲区分辨率（用于判断是否需要重新分配）
    private int mCurrentWidth = 0;
    private int mCurrentHeight = 0;

    /**
     * 构造函数：传入下游 sink
     * @param targetSink 最终接收处理后视频帧的 VideoSink
     */
    public YuvFrameProcessor(VideoSink targetSink) {
        // 保存下游 sink，处理完的帧最终都交给它
        mTargetSink = targetSink;
    }

    /**
     * 注册 YUV 帧回调。传入 null 表示禁用回调（直接透传）
     * @param callback 实现了 YuvFrameCallback 接口的对象
     */
    public void setYuvFrameCallback(YuvFrameCallback callback) {
        mCallback = callback;
    }

    /**
     * WebRTC 每帧回调入口。运行在 WebRTC 的工作线程上，必须快速处理。
     * @param frame WebRTC 传入的视频帧
     */
    @Override
    public void onFrame(VideoFrame frame) {
        // 快速路径：若没有回调，直接透传给下游 sink
        if (mCallback == null) {
            mTargetSink.onFrame(frame);
            return;
        }

        // 把任意 buffer 转换为 I420 格式（YUV420 planar）
        VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();

        try {  // try-finally 确保 i420Buffer 一定被释放，防止 native 内存泄漏
            // ===== 1. 提取分辨率和步长（stride）信息 =====
            int width = i420Buffer.getWidth();      // 帧宽度（像素）
            int height = i420Buffer.getHeight();    // 帧高度（像素）
            int yStride = i420Buffer.getStrideY();  // Y 平面的行步长（>= width）
            int uvStride = i420Buffer.getStrideU(); // U/V 平面的行步长
            int uvHeight = (height + 1) / 2;       // U/V 平面的高度（YUV420 是高度一半，+1 是向上取整）

            // 实际数据大小（按分辨率算）
            int actualYSize = width * height;
            int actualUVSize = ((width + 1) / 2) * uvHeight;

            // 但读取原始数据时必须用 stride 大小的 buffer（因为可能有 padding）
            int strideYSize = yStride * height;
            int strideUVSize = uvStride * uvHeight;

            // ===== 2. 分配或复用缓冲区 =====
            // 如果当前缓冲区不够大（或未分配），按新的 stride 大小重新分配
            if (mYData == null || mYData.length < strideYSize) {
                mYData = new byte[strideYSize];  // 分配 Y 缓冲区
                mUData = new byte[strideUVSize]; // 分配 U 缓冲区
                mVData = new byte[strideUVSize]; // 分配 V 缓冲区
                // 记录当前分辨率
                mCurrentWidth = width;
                mCurrentHeight = height;
            }

            // ===== 3. 读取数据：把 native ByteBuffer 数据拷贝到 Java byte[] =====
            ByteBuffer yBuffer = i420Buffer.getDataY();  // Y 平面 ByteBuffer
            ByteBuffer uBuffer = i420Buffer.getDataU();  // U 平面 ByteBuffer
            ByteBuffer vBuffer = i420Buffer.getDataV();  // V 平面 ByteBuffer

            // 把 ByteBuffer 的 position 重置到 0，准备从头读取
            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();

            // ===== Y 平面：读取 stride 大小的数据（防御性：用 remaining() 兜底） =====
            if (yBuffer.remaining() < strideYSize) {
                // 若 buffer 实际剩余数据不足（异常情况），读取 min(remaining, stride)
                int actualReadYSize = Math.min(yBuffer.remaining(), strideYSize);
                yBuffer.get(mYData, 0, actualReadYSize);
            } else {
                // 正常情况：读取 stride*height 字节
                yBuffer.get(mYData, 0, strideYSize);
            }

            // ===== U 平面：同上 =====
            if (uBuffer.remaining() < strideUVSize) {
                int actualReadUSize = Math.min(uBuffer.remaining(), strideUVSize);
                uBuffer.get(mUData, 0, actualReadUSize);
            } else {
                uBuffer.get(mUData, 0, strideUVSize);
            }

            // ===== V 平面：同上 =====
            if (vBuffer.remaining() < strideUVSize) {
                int actualReadVSize = Math.min(vBuffer.remaining(), strideUVSize);
                vBuffer.get(mVData, 0, actualReadVSize);
            } else {
                vBuffer.get(mVData, 0, strideUVSize);
            }

            // ===== 4. 回调业务层处理 YUV 数据 =====
            // 回调返回 boolean：true 表示已修改，false 表示未修改
            boolean modified = mCallback.onYuvFrame(mYData, mUData, mVData, width, height, yStride, uvStride);

            if (modified) {
                // ===== 5a. 帧被修改：用修改后的数据构造新帧 =====
                // 创建自定义的 SimpleI420Buffer（包装 byte[] 数据）
                SimpleI420Buffer modifiedBuffer = new SimpleI420Buffer(
                        mYData, mUData, mVData, width, height, yStride, uvStride);
                // 用新 buffer 构造新 VideoFrame（保留旋转和时间戳）
                VideoFrame modifiedFrame = new VideoFrame(modifiedBuffer, frame.getRotation(), frame.getTimestampNs());
                // 把新帧传递给下游
                mTargetSink.onFrame(modifiedFrame);
                // 立即释放（onFrame 调用完后下游通常会 retain 自己的引用）
                modifiedFrame.release();
            } else {
                // ===== 5b. 帧未修改：直接透传原帧 =====
                // retain() 增加引用计数，避免下游消费时原帧被回收
                frame.retain();
                mTargetSink.onFrame(frame);
            }

        } finally {
            // 无论成功失败，都要释放 i420Buffer（native 内存）
            i420Buffer.release();
        }
    }

    /**
     * 简单的 I420Buffer 实现：把 byte[] 包装成 WebRTC 期望的 I420Buffer 接口。
     * 每次 getDataY/U/V 都新建一个 ByteBuffer（拷贝数据），
     * 这是为了避免 WebRTC 持有我们的内部 byte[] 引用导致并发问题。
     */
    private static class SimpleI420Buffer implements VideoFrame.I420Buffer {
        // 保存 Y/U/V 平面数据的 byte 数组
        private final byte[] yData;
        private final byte[] uData;
        private final byte[] vData;
        // 分辨率和步长
        private final int width;
        private final int height;
        private final int yStride;
        private final int uvStride;

        /**
         * 构造函数
         * @param y Y 平面数据
         * @param u U 平面数据
         * @param v V 平面数据
         * @param width 实际宽度
         * @param height 实际高度
         * @param yStride Y 平面行步长
         * @param uvStride U/V 平面行步长
         */
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
        public int getWidth() { return width; }  // 返回宽度

        @Override
        public int getHeight() { return height; }  // 返回高度

        @Override
        public ByteBuffer getDataY() {
            // 每次都新建一个直接 ByteBuffer 并拷贝数据（避免引用泄露）
            ByteBuffer buffer = ByteBuffer.allocateDirect(yData.length);
            buffer.put(yData);  // 拷贝数据
            buffer.rewind();    // 重置 position 到 0
            return buffer;
        }

        @Override
        public ByteBuffer getDataU() {
            // 同上，但用于 U 平面
            ByteBuffer buffer = ByteBuffer.allocateDirect(uData.length);
            buffer.put(uData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public ByteBuffer getDataV() {
            // 同上，但用于 V 平面
            ByteBuffer buffer = ByteBuffer.allocateDirect(vData.length);
            buffer.put(vData);
            buffer.rewind();
            return buffer;
        }

        @Override
        public int getStrideY() { return yStride; }  // 返回 Y 步长

        @Override
        public int getStrideU() { return uvStride; }  // 返回 U 步长

        @Override
        public int getStrideV() { return uvStride; }  // 返回 V 步长（通常与 U 相同）

        @Override
        public VideoFrame.I420Buffer toI420() { return this; }  // 已经是 I420 格式，返回自身

        @Override
        public void retain() {}  // 引用计数：暂不实现（byte[] 由 GC 管理）

        @Override
        public void release() {}  // 引用计数：同上

        @Override
        public VideoFrame.Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
                                              int scaleWidth, int scaleHeight) {
            // 裁剪缩放：暂不实现，返回 null
            return null;
        }
    }
}

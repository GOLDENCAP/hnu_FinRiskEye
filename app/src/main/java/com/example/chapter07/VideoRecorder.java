package com.example.chapter07;  // 声明类所在的包路径

// Android 音视频相关
import android.content.Context;            // Android 上下文
import android.media.MediaCodec;           // Android 硬编解码器（用于编码 H.264）
import android.media.MediaCodecInfo;       // 编解码器信息（包含 color format 等常量）
import android.media.MediaFormat;          // 媒体格式描述（用于配置编码器）
import android.media.MediaMuxer;           // 媒体混合器（把编码后的帧封装到 MP4）
// OpenGL ES 相关（EGL 是 GL 与窗口系统之间的接口层）
import android.opengl.EGL14;               // EGL 1.4 API（Android 提供的较新 EGL 接口）
import android.opengl.EGLConfig;           // EGL 配置（如 RGBA 8888 + ES2）
import android.opengl.EGLDisplay;          // EGL 显示连接（代表一个显示器）
import android.opengl.EGLSurface;          // EGL 绘制表面（绑定到编码器的输入 Surface）
import android.opengl.GLES20;              // OpenGL ES 2.0 API（用于 YUV→RGB shader）
import android.os.Handler;                 // Android Handler（线程间消息投递）
import android.os.HandlerThread;           // 专用 Looper 线程（用于渲染循环）
import android.util.Log;                   // 日志工具
import android.view.Surface;                // Android Surface（MediaCodec 输入 surface）

// WebRTC 相关
import org.webrtc.EglBase;                 // WebRTC 的 EGL 封装（用于跨线程共享 EGLContext）
import org.webrtc.VideoFrame;              // WebRTC 视频帧

import java.io.File;                       // Java 文件类（用于创建输出目录）
import java.nio.ByteBuffer;                // NIO 字节缓冲区（用于 GPU 纹理上传）
import java.nio.ByteOrder;                 // 字节序
import java.nio.FloatBuffer;               // 浮点缓冲区（顶点/纹理坐标）
import java.util.concurrent.ConcurrentLinkedQueue;  // 线程安全的无界队列（WebRTC 线程 → 渲染线程）
import java.util.concurrent.CountDownLatch;        // 倒计数锁（用于同步等待 GL 线程初始化）
import java.util.concurrent.atomic.AtomicBoolean;  // 原子布尔（控制渲染循环启停）

/**
 * 视频录制器：把 WebRTC 视频流录制为 H.264 MP4 文件。
 *
 * 工作流程：
 *   1. 外部调用 addSink() 把本类注册为 VideoSink
 *   2. startRecording() 创建 MediaCodec H.264 编码器 + MediaMuxer MP4 封装器
 *   3. WebRTC 每帧回调 onFrame() 把帧入队
 *   4. 渲染线程从队列取帧 → 用 OpenGL YUV→RGB shader 渲染到 MediaCodec 的输入 Surface
 *   5. MediaCodec 编码后 → MediaMuxer 写入 MP4 文件
 *
 * 线程模型：
 *   - WebRTC 线程：onFrame() 入队
 *   - 渲染线程（HandlerThread）：processFrames() 出队 + renderFrame() + OpenGL 渲染
 *   - MediaCodec：在自己的异步线程上编码，通过 drainEncoder 排空输出
 */
public class VideoRecorder implements org.webrtc.VideoSink {

    private static final String TAG = "VideoRecorder";  // 日志 TAG
    // 编码格式：H.264 (AVC)
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    // 帧率：30 FPS
    private static final int FRAME_RATE = 30;
    // I 帧间隔：1 秒（每秒一个关键帧）
    private static final int I_FRAME_INTERVAL = 1;
    // 码率：2 Mbps
    private static final int BIT_RATE = 2_000_000;

    // ===== EGL 相关资源 =====
    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;          // EGL 显示连接
    private android.opengl.EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;  // EGL 渲染上下文
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;          // EGL 绘制表面（绑定 MediaCodec 输入 Surface）
    // MediaCodec 的输入 Surface（OpenGL 渲染的目标）
    private Surface mEncoderSurface;

    // ===== MediaCodec / MediaMuxer 相关 =====
    private MediaCodec mMediaCodec;    // H.264 编码器
    private MediaMuxer mMediaMuxer;    // MP4 封装器
    private int mVideoTrackIndex = -1; // MP4 中的视频轨道索引
    private boolean mMuxerStarted = false;  // 是否已调用 muxer.start()

    private volatile boolean mIsRecording = false;  // 是否正在录制（volatile 保证跨线程可见）
    private String mOutputPath;  // 输出文件路径

    // ===== OpenGL 资源 =====
    private int[] mYTexIds = new int[1];  // Y 平面纹理 ID
    private int[] mUTexIds = new int[1];  // U 平面纹理 ID
    private int[] mVTexIds = new int[1];  // V 平面纹理 ID
    private int mProgramId = 0;           // GL Shader Program ID
    private int mPositionHandle = -1;     // 顶点坐标属性句柄
    private int mTexCoordHandle = -1;     // 纹理坐标属性句柄
    private int mYTexHandle = -1;         // Y 纹理 uniform 句柄
    private int mUTexHandle = -1;         // U 纹理 uniform 句柄
    private int mVTexHandle = -1;         // V 纹理 uniform 句柄

    // 顶点/纹理坐标缓冲区
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    // ===== 顶点坐标：定义一个全屏四边形 =====
    private static final float[] VERTEX_COORDS = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f   // 右上
    };

    // ===== 纹理坐标：UV 映射（注意 YUV 原点在左上，t 方向需要翻转） =====
    private static final float[] TEXTURE_COORDS = {
            0.0f, 1.0f,  // 左下
            1.0f, 1.0f,  // 右下
            0.0f, 0.0f,  // 左上
            1.0f, 0.0f   // 右上
    };

    // 构造函数参数（外部传入）
    private final Context mContext;                  // Android 上下文
    private final EglBase.Context mSharedContext;   // WebRTC 的共享 EGL Context（可共享纹理资源）
    // 视频分辨率
    private int mVideoWidth = 1280;
    private int mVideoHeight = 720;

    // ===== 渲染线程 =====
    private HandlerThread mRenderThread;             // 渲染线程（专用 Looper）
    private Handler mRenderHandler;                  // 渲染线程 Handler
    private final ConcurrentLinkedQueue<VideoFrame> mFrameQueue = new ConcurrentLinkedQueue<>();  // 帧队列
    private final AtomicBoolean mProcessFrames = new AtomicBoolean(true);  // 渲染循环控制标志

    // 同步锁：保护 MediaCodec 和 MediaMuxer 的访问（这两个不是线程安全的）
    private final Object mLock = new Object();

    /**
     * 构造函数
     * @param context Android 上下文
     * @param sharedContext WebRTC 共享的 EGL Context（用于跨线程共享纹理）
     */
    public VideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }

    /**
     * 开始录制。初始化编码器、封装器、GL 资源，并启动渲染循环。
     * @param outputPath 输出 MP4 文件路径
     * @return 成功返回 true，失败返回 false
     */
    public boolean startRecording(String outputPath) {
        synchronized (mLock) {  // 加锁保护 MediaCodec/MediaMuxer
            if (mIsRecording) {
                Log.w(TAG, "Already recording");
                return false;
            }

            this.mOutputPath = outputPath;

            try {
                // ===== 1. 配置 H.264 编码器 =====
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
                // 输入格式：Surface（直接从 OpenGL 渲染，无需 CPU 拷贝）
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                // 码率
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                // 帧率
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                // I 帧间隔
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

                // ===== 2. 创建并启动编码器 =====
                mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);  // 创建编码器
                mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);  // 配置为编码模式
                // 获取输入 Surface（OpenGL 渲染目标）
                mEncoderSurface = mMediaCodec.createInputSurface();
                mMediaCodec.start();  // 启动编码器

                // ===== 3. 创建渲染线程 =====
                mRenderThread = new HandlerThread("VideoRecorderThread");
                mRenderThread.start();  // 启动线程
                mRenderHandler = new Handler(mRenderThread.getLooper());  // 创建 Handler

                // ===== 4. 在渲染线程上初始化 EGL 和 GL 资源 =====
                // 用 CountDownLatch 同步等待初始化完成
                final CountDownLatch initLatch = new CountDownLatch(1);
                mRenderHandler.post(() -> {
                    try {
                        initEGL();        // 初始化 EGL
                        initGLResources(); // 初始化 GL shader 和纹理
                    } catch (Exception e) {
                        Log.e(TAG, "Init error", e);
                    } finally {
                        initLatch.countDown();  // 通知主线程初始化完成
                    }
                });
                initLatch.await();  // 阻塞等待

                // ===== 5. 创建 MediaMuxer MP4 封装器 =====
                // 确保输出目录存在
                File outputFile = new File(outputPath);
                File dir = outputFile.getParentFile();  // 获取父目录
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();  // 递归创建目录
                }
                // 创建 MP4 输出（MUXER_OUTPUT_MPEG_4 = MP4 容器）
                mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                // ===== 6. 设置状态标志，启动渲染循环 =====
                mIsRecording = true;
                mProcessFrames.set(true);
                // post 一个处理循环到渲染线程
                mRenderHandler.post(this::processFrames);

                Log.i(TAG, "Recording started: " + outputPath);
                return true;

            } catch (Exception e) {
                // 任何异常都清理资源
                Log.e(TAG, "Failed to start recording", e);
                releaseRecorderInternal();
                return false;
            }
        }
    }

    /**
     * 渲染线程循环：从帧队列取帧并渲染
     */
    private void processFrames() {
        // 循环条件：mProcessFrames 为 true 时持续运行
        while (mProcessFrames.get()) {
            // 从队列取一帧（poll 非阻塞，无帧时返回 null）
            VideoFrame frame = mFrameQueue.poll();
            if (frame != null) {
                try {
                    renderFrame(frame);  // 渲染单帧
                } catch (Exception e) {
                    Log.e(TAG, "Render error", e);
                }
                // 注意：VideoSink 通常不负责释放 VideoFrame
                // WebRTC 会管理帧的生命周期
            } else {
                // 队列为空，sleep 5ms 避免空转消耗 CPU
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  // 保留中断标志
                }
            }
        }
    }

    /**
     * 停止录制。完成编码、刷新封装器、释放资源。
     */
    public void stopRecording() {
        synchronized (mLock) {
            if (!mIsRecording) {
                return;  // 没有在录制
            }
            mIsRecording = false;
        }

        // 通知渲染循环退出
        mProcessFrames.set(false);

        // 等待渲染线程结束（最多 2 秒）
        try {
            if (mRenderThread != null) {
                mRenderThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (mLock) {
            try {
                if (mMediaCodec != null) {
                    // signalEndOfInputStream 通知编码器输入结束
                    mMediaCodec.signalEndOfInputStream();
                    // 排空编码器剩余数据（endOfStream=true 强制排空）
                    drainEncoderInternal(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during stop recording", e);
            } finally {
                releaseRecorderInternal();  // 释放所有资源
                Log.i(TAG, "Recording saved to: " + mOutputPath);
            }
        }
    }

    /**
     * VideoSink 接口实现：WebRTC 每帧回调入口
     * @param frame WebRTC 视频帧
     */
    @Override
    public void onFrame(VideoFrame frame) {
        // 快速路径：未在录制或帧为空时直接返回
        if (!mIsRecording || frame == null) {
            return;
        }
        // 把帧加入队列（WebRTC 线程 → 渲染线程）
        mFrameQueue.add(frame);
    }

    /**
     * 渲染单帧到 MediaCodec 输入 Surface
     * @param frame WebRTC 视频帧
     */
    private void renderFrame(VideoFrame frame) {
        // 防御性检查
        if (frame == null || frame.getBuffer() == null) {
            Log.w(TAG, "Frame or buffer is null");
            return;
        }

        // 把任意 buffer 转换为 I420（YUV420 planar）
        VideoFrame.I420Buffer i420 = null;
        try {
            i420 = frame.getBuffer().toI420();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get I420 buffer", e);
            return;
        }

        if (i420 == null) {
            Log.w(TAG, "I420 buffer is null");
            return;
        }

        // 提取分辨率
        int width = i420.getWidth();
        int height = i420.getHeight();

        // 如果分辨率变化，更新内部状态（编码器已创建，后续分辨率变化无法生效，仅记录日志）
        synchronized (mLock) {
            if (width != mVideoWidth || height != mVideoHeight) {
                mVideoWidth = width;
                mVideoHeight = height;
                Log.i(TAG, "Video resolution updated to: " + width + "x" + height);
            }
        }

        // 计算 YUV 各平面大小
        int ySize = width * height;             // Y 平面字节数
        int uvSize = ((width + 1) / 2) * ((height + 1) / 2);  // U/V 平面字节数（向上取整）

        // 取出 YUV 数据
        ByteBuffer yBuf = i420.getDataY();
        ByteBuffer uBuf = i420.getDataU();
        ByteBuffer vBuf = i420.getDataV();

        // 检查缓冲区是否有效
        if (yBuf == null || uBuf == null || vBuf == null) {
            Log.w(TAG, "YUV buffers are null");
            i420.release();
            return;
        }

        // 确保有足够的数据
        if (yBuf.capacity() < ySize || uBuf.capacity() < uvSize || vBuf.capacity() < uvSize) {
            Log.w(TAG, "Buffer capacity too small");
            i420.release();
            return;
        }

        // ===== 把 ByteBuffer 数据拷贝到 byte[]（GPU 上传需要） =====
        byte[] yData = new byte[ySize];
        byte[] uData = new byte[uvSize];
        byte[] vData = new byte[uvSize];

        // 重置 position 准备读取
        yBuf.rewind();
        uBuf.rewind();
        vBuf.rewind();

        // 从 ByteBuffer 读数据到 byte[]
        yBuf.get(yData, 0, ySize);
        uBuf.get(uData, 0, uvSize);
        vBuf.get(vData, 0, uvSize);

        // 释放 I420 buffer（native 内存）
        i420.release();

        // 把 YUV 渲染到 MediaCodec 输入 Surface
        drawFrame(yData, uData, vData, width, height);
    }

    /**
     * 初始化 EGL：创建 Display、Config、Context、Surface
     */
    private void initEGL() {
        // 1. 获取默认显示
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        // 2. 初始化 EGL（获取版本号）
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("Unable to initialize EGL14");
        }

        // 3. 选择 EGL Config（RGBA 8888 + ES2 + PBuffer）
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,            // R 通道 8 位
                EGL14.EGL_GREEN_SIZE, 8,          // G 通道 8 位
                EGL14.EGL_BLUE_SIZE, 8,           // B 通道 8 位
                EGL14.EGL_ALPHA_SIZE, 8,          // A 通道 8 位
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,  // 支持 GLES 2.0
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,        // 支持 PBuffer（不显示到屏幕）
                EGL14.EGL_NONE                                          // 列表结束
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGLConfig");
        }

        // 4. 创建 EGL Context（GLES 2.0）
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,  // GLES 版本 2
                EGL14.EGL_NONE
        };
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        if (mEglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGLContext");
        }

        // 5. 创建绑定到 MediaCodec 输入 Surface 的 EGLSurface
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], mEncoderSurface, surfaceAttribs, 0);

        if (mEglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGLSurface");
        }

        // 6. 把 Context 绑定为当前线程的渲染目标
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw new RuntimeException("Unable to make EGL context current");
        }
    }

    /**
     * 初始化 GL 资源：编译 shader、生成纹理、准备顶点/纹理坐标 Buffer
     */
    private void initGLResources() {
        // ===== 顶点着色器：把顶点坐标和纹理坐标传递到片元着色器 =====
        String vertexShaderCode =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n";

        // ===== 片元着色器：YUV → RGB 转换 =====
        String fragmentShaderCode =
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uYTexture;\n" +
                "uniform sampler2D uUTexture;\n" +
                "uniform sampler2D uVTexture;\n" +
                "void main() {\n" +
                // 采样 YUV，U/V 要 -0.5 偏移到 [-0.5, 0.5]
                "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
                "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
                "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
                // BT.601 YUV→RGB 转换公式
                "    float r = y + 1.402 * v;\n" +
                "    float g = y - 0.344 * u - 0.714 * v;\n" +
                "    float b = y + 1.772 * u;\n" +
                // 输出颜色（clamp 到 [0,1]）
                "    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);\n" +
                "}\n";

        // 编译两个 shader
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        // 创建并链接 Program
        mProgramId = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgramId, vertexShader);
        GLES20.glAttachShader(mProgramId, fragmentShader);
        GLES20.glLinkProgram(mProgramId);

        // 获取 attribute 和 uniform 的句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
        mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
        mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
        mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");

        // 准备顶点坐标 Buffer
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(VERTEX_COORDS);
        mVertexBuffer.rewind();

        // 准备纹理坐标 Buffer
        mTexCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_COORDS);
        mTexCoordBuffer.rewind();

        // 生成三个 YUV 纹理
        GLES20.glGenTextures(1, mYTexIds, 0);
        GLES20.glGenTextures(1, mUTexIds, 0);
        GLES20.glGenTextures(1, mVTexIds, 0);
    }

    /**
     * 把 YUV 数据渲染到 MediaCodec 输入 Surface
     * @param yData Y 平面数据
     * @param uData U 平面数据
     * @param vData V 平面数据
     * @param width 视频宽度
     * @param height 视频高度
     */
    private void drawFrame(byte[] yData, byte[] uData, byte[] vData, int width, int height) {
        // 检查 EGL 资源是否初始化
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY || mEglSurface == EGL14.EGL_NO_SURFACE || mEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "EGL resources are not initialized");
            return;
        }

        // 把 EGL Context 绑定为当前
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            Log.e(TAG, "Failed to make EGL context current, error: " + EGL14.eglGetError());
            return;
        }

        // 设置视口（GL 渲染区域）和清屏色
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 启用 Shader Program
        GLES20.glUseProgram(mProgramId);

        // UV 平面的高度（向上取整）
        int uvHeight = (height + 1) / 2;

        // ===== 上传 Y 平面到纹理 0 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);            // 激活纹理单元 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);  // 绑定 Y 纹理
        // 把 byte[] 数据上传到纹理（LUMINANCE 格式，单通道灰度）
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));
        // 设置纹理参数（线性采样 + 截断到边缘）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // 告诉 shader：uYTexture 使用纹理单元 0
        GLES20.glUniform1i(mYTexHandle, 0);

        // UV 平面宽度（向上取整）
        int uvWidth = (width + 1) / 2;

        // ===== 上传 U 平面到纹理 1 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(uData));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(mUTexHandle, 1);  // uUTexture 使用纹理单元 1

        // ===== 上传 V 平面到纹理 2 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(vData));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(mVTexHandle, 2);  // uVTexture 使用纹理单元 2

        // ===== 绑定顶点坐标 attribute =====
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

        // ===== 绑定纹理坐标 attribute =====
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

        // ===== 绘制全屏四边形（4 顶点 → 2 三角形 → 1 个矩形） =====
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 把渲染结果交换到 MediaCodec 输入 Surface（触发编码）
        EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);

        // 排空编码器输出（把已编码的帧写入 MP4）
        synchronized (mLock) {
            drainEncoderInternal(false);
        }
    }

    /**
     * 排空 MediaCodec 输出 buffer，把编码后的帧写入 MP4
     * @param endOfStream 是否编码结束（true 时阻塞排空直到 BUFFER_FLAG_END_OF_STREAM）
     */
    private void drainEncoderInternal(boolean endOfStream) {
        if (mMediaCodec == null) return;

        // BufferInfo 用于描述编码后的帧（offset、size、flags、时间戳）
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            // 从编码器取输出 buffer（最多等 10000us）
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 没有输出：非结束模式下退出循环，结束模式下继续等待
                if (!endOfStream) {
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式变化（编码器刚启动时会触发一次）：必须先 addTrack 才能 start muxer
                if (mMuxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }
                MediaFormat newFormat = mMediaCodec.getOutputFormat();  // 获取编码器输出格式
                Log.i(TAG, "Encoder output format changed: " + newFormat);
                mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);   // 添加视频轨道
                mMediaMuxer.start();                                    // 启动 muxer
                mMuxerStarted = true;
            } else if (outputBufferIndex >= 0) {
                // 正常的编码输出 buffer
                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);

                // Codec Config 数据（SPS/PPS）不写入 muxer
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                // 写入 muxer（必须 mMuxerStarted 后才能写入）
                if (bufferInfo.size != 0 && mMuxerStarted) {
                    // 设置 buffer 的 position 和 limit
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    // 写入视频帧到 MP4
                    mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, bufferInfo);
                }

                // 释放 output buffer（render=false 表示不渲染到 surface）
                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);

                // 收到流结束标志，退出循环
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    /**
     * 编译单个 Shader
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * 公开的释放方法（外部调用）
     */
    public void release() {
        synchronized (mLock) {
            releaseRecorderInternal();
        }
    }

    /**
     * 内部释放方法：清理所有资源
     */
    private void releaseRecorderInternal() {
        // ===== 1. 退出渲染线程 =====
        if (mRenderThread != null) {
            HandlerThread thread = mRenderThread;
            mRenderThread = null;
            thread.quitSafely();  // 安全退出（处理完当前消息）
        }

        // ===== 2. 清理 EGL 资源 =====
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            // 解绑当前 context
            EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            // 销毁 Surface
            if (mEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
            }
            // 销毁 Context
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            }
            // 终止 EGL
            EGL14.eglTerminate(mEglDisplay);
        }

        // 重置 EGL 资源为"无"状态
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mEglContext = EGL14.EGL_NO_CONTEXT;
        mEglSurface = EGL14.EGL_NO_SURFACE;

        // ===== 3. 释放 MediaCodec =====
        if (mMediaCodec != null) {
            try {
                mMediaCodec.stop();   // 停止编码
                mMediaCodec.release(); // 释放资源
            } catch (Exception e) {
                Log.e(TAG, "MediaCodec release error", e);
            }
            mMediaCodec = null;
        }

        // ===== 4. 释放 MediaMuxer =====
        if (mMediaMuxer != null) {
            try {
                if (mMuxerStarted) {
                    mMediaMuxer.stop();  // 停止 muxer（必须在 start 后调用）
                }
                mMediaMuxer.release();  // 释放资源
            } catch (Exception e) {
                Log.e(TAG, "MediaMuxer release error", e);
            }
            mMediaMuxer = null;
            mMuxerStarted = false;
        }

        mVideoTrackIndex = -1;  // 重置轨道索引
        Log.i(TAG, "Recorder released");
    }
}
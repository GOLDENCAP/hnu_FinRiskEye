package com.example.chapter07;  // 声明类所在的包名

// ========== Android 音视频相关 ==========
import android.content.Context;               // Android 上下文
import android.media.MediaCodec;             // H.264 编码器
import android.media.MediaCodecInfo;         // 编解码器信息
import android.media.MediaFormat;            // 媒体格式描述
import android.media.MediaMuxer;             // MP4 封装器
import android.opengl.EGL14;                 // EGL 1.4 API
import android.opengl.EGLConfig;             // EGL 配置
import android.opengl.EGLDisplay;            // EGL 显示连接
import android.opengl.EGLSurface;            // EGL 绘制表面
import android.opengl.GLES20;                // OpenGL ES 2.0
import android.os.Handler;                   // Handler
import android.os.HandlerThread;             // 工作线程
import android.util.Log;                     // 日志
import android.view.Surface;                  // Android Surface（MediaCodec 输入）

// ========== WebRTC ==========
import org.webrtc.EglBase;                   // WebRTC EGL 封装
import org.webrtc.VideoFrame;                // WebRTC 视频帧

// ========== Java 标准库 ==========
import java.io.File;                         // 文件对象
import java.nio.ByteBuffer;                  // NIO 字节缓冲
import java.nio.ByteOrder;                   // 字节序
import java.nio.FloatBuffer;                 // 浮点缓冲
import java.util.concurrent.ConcurrentLinkedQueue;  // 线程安全队列
import java.util.concurrent.CountDownLatch;  // 倒计数锁
import java.util.concurrent.atomic.AtomicBoolean;  // 原子布尔

/**
 * 远端视频录制器。
 * 通过实现 VideoSink 接口接收 WebRTC 远端视频帧，并编码保存为 MP4 文件。
 */
public class RemoteVideoRecorder implements org.webrtc.VideoSink {  // 实现 WebRTC 视频接收接口

    private static final String TAG = "RemoteVideoRecorder";      // 日志 TAG
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;  // H.264 编码
    private static final int FRAME_RATE = 30;                     // 帧率 30fps
    private static final int I_FRAME_INTERVAL = 1;                // I 帧间隔 1 秒
    private static final int BIT_RATE = 2_000_000;                // 码率 2Mbps

    // ===== EGL 相关变量 =====
    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;          // EGL 显示
    private android.opengl.EGLContext mEglContext = EGL14.EGL_NO_CONTEXT; // EGL 上下文
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;          // EGL 表面（绑定 MediaCodec）
    private Surface mEncoderSurface;                                 // MediaCodec 输入 Surface

    // ===== 编码相关变量 =====
    private MediaCodec mMediaCodec;          // H.264 编码器
    private MediaMuxer mMediaMuxer;          // MP4 封装器
    private int mVideoTrackIndex = -1;       // 视频轨道索引
    private boolean mMuxerStarted = false;   // muxer 是否已 start

    // ===== 录制状态 =====
    private volatile boolean mIsRecording = false;  // 是否正在录制
    private String mOutputPath;                     // 输出路径

    // ===== OpenGL 相关变量 =====
    private int[] mYTexIds = new int[1];    // Y 平面纹理 ID
    private int[] mUTexIds = new int[1];    // U 平面纹理 ID
    private int[] mVTexIds = new int[1];    // V 平面纹理 ID
    private int mProgramId = 0;             // Shader Program ID
    private int mPositionHandle = -1;       // aPosition 句柄
    private int mTexCoordHandle = -1;       // aTexCoord 句柄
    private int mYTexHandle = -1;           // uYTexture 句柄
    private int mUTexHandle = -1;           // uUTexture 句柄
    private int mVTexHandle = -1;           // uVTexture 句柄

    private FloatBuffer mVertexBuffer;      // 顶点坐标 Buffer
    private FloatBuffer mTexCoordBuffer;    // 纹理坐标 Buffer

    // ===== 顶点坐标和纹理坐标 =====
    private static final float[] VERTEX_COORDS = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f   // 右上
    };

    private static final float[] TEXTURE_COORDS = {
            0.0f, 1.0f,  // 左下 (YUV 原始数据)
            1.0f, 1.0f,  // 右下
            0.0f, 0.0f,  // 左上
            1.0f, 0.0f   // 右上
    };

    // ===== 上下文 =====
    private final Context mContext;             // Android 上下文
    private final EglBase.Context mSharedContext; // WebRTC 共享 EGL 上下文
    private int mVideoWidth = 1280;            // 视频宽度
    private int mVideoHeight = 720;            // 视频高度

    // ===== 线程相关 =====
    private HandlerThread mRenderThread;       // 渲染线程
    private Handler mRenderHandler;            // 渲染 Handler
    private final ConcurrentLinkedQueue<VideoFrame> mFrameQueue = new ConcurrentLinkedQueue<>();  // 帧队列
    private final AtomicBoolean mProcessFrames = new AtomicBoolean(true);  // 帧处理循环标志

    // 同步锁，保护 MediaCodec 和 MediaMuxer 的访问
    private final Object mLock = new Object();

    /**
     * 构造函数
     * @param context Android 上下文
     * @param sharedContext WebRTC 共享 EGL 上下文
     */
    public RemoteVideoRecorder(Context context, EglBase.Context sharedContext) {
        this.mContext = context;
        this.mSharedContext = sharedContext;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * 开始录制
     * @param outputPath 输出文件路径
     * @return 是否成功开始录制
     */
    public boolean startRecording(String outputPath) {
        synchronized (mLock) {                  // 加锁保护
            if (mIsRecording) {                 // 已在录制
                Log.w(TAG, "Already recording");
                return false;
            }

            this.mOutputPath = outputPath;      // 保存输出路径

            try {
                // ===== 1. 配置 MediaCodec 编码器 =====
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

                mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
                mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                mEncoderSurface = mMediaCodec.createInputSurface();  // 获取编码器输入 Surface
                mMediaCodec.start();

                // ===== 2. 初始化渲染线程 =====
                mRenderThread = new HandlerThread("RemoteRecorderThread");
                mRenderThread.start();
                mRenderHandler = new Handler(mRenderThread.getLooper());

                // 在渲染线程上初始化 EGL 和 GL 资源（用 CountDownLatch 同步等待）
                CountDownLatch initLatch = new CountDownLatch(1);
                mRenderHandler.post(() -> {
                    try {
                        initEGL();           // 初始化 EGL
                        initGLResources();   // 初始化 GL 资源
                    } catch (Exception e) {
                        Log.e(TAG, "Init error", e);
                    } finally {
                        initLatch.countDown();  // 通知初始化完成
                    }
                });
                initLatch.await();  // 阻塞等待初始化完成

                // ===== 3. 初始化 MediaMuxer =====
                File outputFile = new File(outputPath);
                File dir = outputFile.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                mIsRecording = true;             // 标记录制中
                mProcessFrames.set(true);        // 标记帧处理循环运行

                // ===== 4. 启动帧处理循环 =====
                mRenderHandler.post(this::processFrames);

                Log.i(TAG, "Recording started: " + outputPath);
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                releaseRecorderInternal();
                return false;
            }
        }
    }

    /**
     * 处理帧队列（运行在渲染线程）
     */
    private void processFrames() {
        while (mProcessFrames.get()) {          // 循环
            VideoFrame frame = mFrameQueue.poll();  // 从队列取帧
            if (frame != null) {                 // 有帧
                try {
                    renderFrame(frame);          // 渲染该帧
                } catch (Exception e) {
                    Log.e(TAG, "Render error", e);
                }
            } else {                              // 无帧，休眠 5ms
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        synchronized (mLock) {
            if (!mIsRecording) {                 // 未在录制
                return;
            }

            mIsRecording = false;
        }

        mProcessFrames.set(false);               // 标记帧处理循环退出

        try {
            // 确保渲染线程完成
            if (mRenderThread != null) {
                mRenderThread.join(2000);        // 等待最多 2 秒
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (mLock) {
            try {
                if (mMediaCodec != null) {
                    // 通知编码器输入结束
                    mMediaCodec.signalEndOfInputStream();
                    // 排空编码器剩余输出（结束模式）
                    drainEncoderInternal(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during stop recording", e);
            } finally {
                releaseRecorderInternal();        // 释放所有资源
                Log.i(TAG, "Recording saved to: " + mOutputPath);
            }
        }
    }

    /**
     * VideoSink 接口：当收到新的视频帧时调用（WebRTC 线程）
     */
    @Override
    public void onFrame(VideoFrame frame) {
        if (!mIsRecording || frame == null) {    // 未在录制或帧为空
            return;
        }
        // 将帧添加到队列（由渲染线程异步处理）
        mFrameQueue.add(frame);
    }

    /**
     * 渲染帧到编码器 Surface（运行在渲染线程）
     */
    private void renderFrame(VideoFrame frame) {
        if (frame == null || frame.getBuffer() == null) {
            Log.w(TAG, "Frame or buffer is null");
            return;
        }

        // 转换为 I420（YUV420 planar）
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

        int width = i420.getWidth();             // 帧宽度
        int height = i420.getHeight();           // 帧高度

        // 同步更新视频分辨率
        synchronized (mLock) {
            if (width != mVideoWidth || height != mVideoHeight) {
                mVideoWidth = width;
                mVideoHeight = height;
                Log.i(TAG, "Video resolution updated to: " + width + "x" + height);
            }
        }

        // 计算各平面大小
        int ySize = width * height;                       // Y 平面字节数
        int uvSize = ((width + 1) / 2) * ((height + 1) / 2);  // U/V 平面字节数

        ByteBuffer yBuf = i420.getDataY();                // Y 数据
        ByteBuffer uBuf = i420.getDataU();                // U 数据
        ByteBuffer vBuf = i420.getDataV();                // V 数据

        // 检查缓冲区有效性
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

        // 拷贝数据到 byte[]
        byte[] yData = new byte[ySize];
        byte[] uData = new byte[uvSize];
        byte[] vData = new byte[uvSize];

        yBuf.rewind();   // 重置 position
        uBuf.rewind();
        vBuf.rewind();

        yBuf.get(yData, 0, ySize);     // 读取 Y
        uBuf.get(uData, 0, uvSize);    // 读取 U
        vBuf.get(vData, 0, uvSize);    // 读取 V

        // 释放 I420 buffer（native 内存）
        i420.release();

        // 调用 drawFrame 渲染到编码器 Surface
        drawFrame(yData, uData, vData, width, height);
    }

    /**
     * 初始化 EGL 环境（Display、Config、Context、Surface）
     */
    private void initEGL() {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("Unable to initialize EGL14");
        }

        // EGL Config 属性（RGBA8888 + ES2 + PBuffer）
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGLConfig");
        }

        // EGL Context 属性（ES 2.0）
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };

        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        if (mEglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGLContext");
        }

        // 创建绑定到 MediaCodec 输入 Surface 的 EGLSurface
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], mEncoderSurface, surfaceAttribs, 0);

        if (mEglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGLSurface");
        }

        // 把 Context 设为当前
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw new RuntimeException("Unable to make EGL context current");
        }
    }

    /**
     * 初始化 OpenGL 资源：编译 Shader、生成纹理、准备 Buffer
     */
    private void initGLResources() {
        // ===== 顶点着色器 =====
        String vertexShaderCode =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n";

        // ===== 片元着色器：YUV 转 RGB =====
        String fragmentShaderCode =
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uYTexture;\n" +
                "uniform sampler2D uUTexture;\n" +
                "uniform sampler2D uVTexture;\n" +
                "void main() {\n" +
                "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
                "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
                "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
                "    float r = y + 1.402 * v;\n" +
                "    float g = y - 0.344 * u - 0.714 * v;\n" +
                "    float b = y + 1.772 * u;\n" +
                "    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);\n" +
                "}\n";

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        mProgramId = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgramId, vertexShader);
        GLES20.glAttachShader(mProgramId, fragmentShader);
        GLES20.glLinkProgram(mProgramId);

        mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
        mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
        mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
        mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");

        // 顶点坐标 Buffer
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(VERTEX_COORDS);
        mVertexBuffer.rewind();

        // 纹理坐标 Buffer
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
     * 绘制帧到编码器 Surface
     */
    private void drawFrame(byte[] yData, byte[] uData, byte[] vData, int width, int height) {
        // 检查 EGL 资源
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY || mEglSurface == EGL14.EGL_NO_SURFACE || mEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "EGL resources are not initialized");
            return;
        }

        // 绑定 EGL Context
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            Log.e(TAG, "Failed to make EGL context current, error: " + EGL14.eglGetError());
            return;
        }

        // 设置视口并清屏
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 启用 Shader Program
        GLES20.glUseProgram(mProgramId);

        int uvHeight = (height + 1) / 2;     // UV 高度

        // ===== 上传 Y 平面 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(mYTexHandle, 0);

        int uvWidth = (width + 1) / 2;        // UV 宽度

        // ===== 上传 U 平面 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(uData));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(mUTexHandle, 1);

        // ===== 上传 V 平面 =====
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvWidth, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(vData));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUniform1i(mVTexHandle, 2);

        // ===== 绑定顶点属性 =====
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

        // ===== 绘制 =====
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 交换缓冲区（提交到 MediaCodec 输入 Surface，触发编码）
        EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);

        // 排空编码器输出（把编码后的帧写入 MP4）
        synchronized (mLock) {
            drainEncoderInternal(false);
        }
    }

    /**
     * 刷新编码器缓冲区
     */
    private void drainEncoderInternal(boolean endOfStream) {
        if (mMediaCodec == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {                 // 非结束模式退出
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mMuxerStarted) {                // 已启动，重复触发是异常
                    throw new RuntimeException("Format changed twice");
                }
                MediaFormat newFormat = mMediaCodec.getOutputFormat();
                Log.i(TAG, "Encoder output format changed: " + newFormat);
                mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);  // 添加视频轨道
                mMediaMuxer.start();                                  // 启动 muxer
                mMuxerStarted = true;
            } else if (outputBufferIndex >= 0) {    // 正常输出
                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);

                // Codec Config 数据（SPS/PPS）不写入 muxer
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0 && mMuxerStarted) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, bufferInfo);  // 写入 MP4
                }

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {  // 流结束
                    break;
                }
            }
        }
    }

    /**
     * 加载着色器
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * 释放录制器资源（公开方法）
     */
    public void release() {
        synchronized (mLock) {
            releaseRecorderInternal();
        }
    }

    /**
     * 内部释放资源方法
     */
    private void releaseRecorderInternal() {
        // ===== 1. 停止渲染线程 =====
        if (mRenderThread != null) {
            HandlerThread thread = mRenderThread;
            mRenderThread = null;
            thread.quitSafely();
        }

        // ===== 2. 释放 EGL 资源 =====
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            // 解绑 Context
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

        // 重置 EGL 资源
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mEglContext = EGL14.EGL_NO_CONTEXT;
        mEglSurface = EGL14.EGL_NO_SURFACE;

        // ===== 3. 释放 MediaCodec =====
        if (mMediaCodec != null) {
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "MediaCodec release error", e);
            }
            mMediaCodec = null;
        }

        // ===== 4. 释放 MediaMuxer =====
        if (mMediaMuxer != null) {
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

        mVideoTrackIndex = -1;
        Log.i(TAG, "Recorder released");
    }
}
package com.example.chapter07;  // 声明类所在的包名为 com.example.chapter07

// ========== Android 框架相关 import ==========
import android.content.Context;            // Android 上下文，用于获取系统资源
import android.opengl.EGL14;               // EGL 1.4 API，用于 OpenGL 与窗口系统对接
import android.opengl.EGLConfig;           // EGL 配置（如 RGBA8888 + ES3.0）
import android.opengl.EGLDisplay;          // EGL 显示连接，代表一个显示器
import android.opengl.EGLSurface;          // EGL 绘制表面（这里用 PBuffer 离屏渲染）
import android.opengl.GLES30;              // OpenGL ES 3.0 API（着色器使用新版 GLSL 语法）
import android.util.Log;                   // 日志工具

// ========== WebRTC 相关 ==========
import org.webrtc.VideoFrame;              // WebRTC 视频帧（封装 I420 数据）
import org.webrtc.VideoSink;               // WebRTC 视频接收器接口（实现 onFrame 回调）

// ========== Java NIO ==========
import java.nio.ByteBuffer;                // NIO 字节缓冲（GPU 上传用）
import java.nio.ByteOrder;                 // 字节序
import java.nio.FloatBuffer;               // 浮点缓冲（顶点/纹理坐标）

/**
 * 本地视频暖色调特效渲染器（基于 OpenGL ES 3.0 Shader）。
 *
 * 实现 VideoSink 接口，可作为 sink 添加到 WebRTC VideoTrack。
 * 接收本地视频帧，通过 OpenGL ES 3.0 Shader 应用暖色调效果，
 * 然后将处理后的帧转发到目标渲染器（本地 SurfaceView）。
 *
 * 注意：当前实现实际上把原始帧也透传给 mTargetSink，GL 处理结果仅用于 FBO 离屏纹理，
 *       因此"暖色调效果"主要靠该类作为占位/示例，便于扩展。
 */
public class LocalWarmEffectRenderer implements VideoSink {  // 实现 WebRTC 视频接收接口

    private static final String TAG = "LocalWarmEffectRenderer";  // 日志 TAG
    private static final boolean DEBUG = true;                    // 调试日志开关

    // ========== OpenGL ES / EGL 上下文资源 ==========
    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;          // EGL 显示连接（默认显示器）
    private android.opengl.EGLContext mEglContext = EGL14.EGL_NO_CONTEXT; // EGL 渲染上下文（ES 3.0）
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;          // EGL 离屏 PBuffer 表面
    private int mTextureId = -1;                                    // 通用纹理 ID（暂未使用）

    // ========== Shader 相关句柄 ==========
    private int mProgramId = 0;            // GL Shader Program ID
    private int mPositionHandle = -1;      // "aPosition" attribute 句柄
    private int mTexCoordHandle = -1;      // "aTexCoord" attribute 句柄
    private int mYTexHandle = -1;          // "uYTexture" uniform 句柄
    private int mUTexHandle = -1;          // "uUTexture" uniform 句柄
    private int mVTexHandle = -1;          // "uVTexture" uniform 句柄

    // ========== 纹理 ID 数组（GLES 必须用数组传入） ==========
    private int[] mYTexIds = new int[1];   // Y 平面纹理 ID
    private int[] mUTexIds = new int[1];   // U 平面纹理 ID
    private int[] mVTexIds = new int[1];   // V 平面纹理 ID
    private int[] mFBOTexIds = new int[1]; // FBO 关联纹理 ID
    private int[] mFBOIds = new int[1];    // Framebuffer Object ID

    // ========== 顶点/纹理坐标 NIO 缓冲 ==========
    private FloatBuffer mVertexBuffer;     // 顶点坐标 Buffer
    private FloatBuffer mTexCoordBuffer;   // 纹理坐标 Buffer

    // ========== 全屏四边形顶点坐标（归一化到 [-1, 1]） ==========
    private static final float[] VERTEX_COORDS = {
            -1.0f, -1.0f,  // 左下角顶点
             1.0f, -1.0f,  // 右下角顶点
            -1.0f,  1.0f,  // 左上角顶点
             1.0f,  1.0f   // 右上角顶点
    };

    // ========== 纹理坐标（UV 映射） ==========
    // 注：YUV 原点在左上，所以 t=0 对应纹理顶部，左下顶点对应 t=1
    private static final float[] TEXTURE_COORDS = {
            0.0f, 1.0f,  // 左下 → 纹理左下（t=1 表示纹理底部）
            1.0f, 1.0f,  // 右下 → 纹理右下
            0.0f, 0.0f,  // 左上 → 纹理左上（t=0 表示纹理顶部）
            1.0f, 0.0f   // 右上 → 纹理右上
    };

    // ========== 目标渲染器（被装饰的下游 sink） ==========
    private final VideoSink mTargetSink;  // 处理完（或透传）的帧会送到这里

    // ========== 状态标志 ==========
    private volatile boolean mIsEffectEnabled = false;  // 是否启用暖色调特效（volatile 跨线程可见）
    private volatile int mVideoWidth = 1280;             // 当前视频宽度（默认 1280）
    private volatile int mVideoHeight = 720;             // 当前视频高度（默认 720）

    // ========== 暖色调参数（volatile 跨线程可改） ==========
    private volatile float mWarmth = 0.3f;       // 暖色调强度：0~1
    private volatile float mSaturation = 1.1f;   // 饱和度：1.0 原始，>1 更鲜艳

    private boolean mInitialized = false;        // 是否已初始化 EGL/GL 资源

    /**
     * 构造函数
     * @param targetSink 下游 sink，处理后的帧转发到这里
     */
    public LocalWarmEffectRenderer(VideoSink targetSink) {
        this.mTargetSink = targetSink;  // 保存下游引用
    }

    /**
     * 启用 / 禁用暖色调特效。
     */
    public void setEffectEnabled(boolean enabled) {
        mIsEffectEnabled = enabled;
    }

    /**
     * 查询特效是否启用。
     */
    public boolean isEffectEnabled() {
        return mIsEffectEnabled;
    }

    /**
     * 设置暖色调强度。
     * @param warmth 范围 0.0~1.0
     */
    public void setWarmth(float warmth) {
        this.mWarmth = warmth;
    }

    /**
     * 设置饱和度。
     * @param saturation 1.0 原始，>1 更鲜艳，<1 更灰
     */
    public void setSaturation(float saturation) {
        this.mSaturation = saturation;
    }

    /**
     * 初始化 OpenGL ES 3.0 环境（EGL + Shader + 纹理 + FBO）。
     * 只在第一次调用时真正执行，后续调用直接返回。
     * @param context Android 上下文（本实现未使用，预留）
     */
    public void initGL(Context context) {
        if (mInitialized) {  // 已经初始化过
            return;            // 直接返回，避免重复初始化
        }

        try {
            // ===== 1. 获取 EGL Display =====
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); // 取默认显示
            if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {                   // 获取失败
                throw new RuntimeException("Unable to get EGL14 display");
            }

            // ===== 2. 初始化 EGL，获取版本号 =====
            int[] version = new int[2];                                    // 存放 major/minor 版本号
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) { // 初始化 EGL
                mEglDisplay = EGL14.EGL_NO_DISPLAY;                       // 重置
                throw new RuntimeException("Unable to initialize EGL14");  // 抛异常
            }

            // ===== 3. 配置 EGL Config 属性（RGBA8888 + ES3.0 + PBuffer） =====
            int[] configAttribs = {
                    EGL14.EGL_RED_SIZE, 8,                            // R 通道 8 位
                    EGL14.EGL_GREEN_SIZE, 8,                          // G 通道 8 位
                    EGL14.EGL_BLUE_SIZE, 8,                           // B 通道 8 位
                    EGL14.EGL_ALPHA_SIZE, 8,                          // A 通道 8 位
                    EGL14.EGL_RENDERABLE_TYPE, 0x0040,                // 0x0040 = EGL_OPENGL_ES3_BIT，要求 ES 3.0
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,    // 支持 PBuffer 离屏渲染
                    EGL14.EGL_NONE                                    // 属性列表结束
            };

            EGLConfig[] configs = new EGLConfig[1];                   // 最多取 1 个匹配配置
            int[] numConfigs = new int[1];                            // 实际匹配到的数量
            if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                throw new RuntimeException("Unable to find suitable EGLConfig"); // 找不到配置则抛异常
            }

            // ===== 4. 创建 EGL Context（OpenGL ES 3.0） =====
            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,              // 指定 ES 主版本号 3
                    EGL14.EGL_NONE                                    // 列表结束
            };
            mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (mEglContext == EGL14.EGL_NO_CONTEXT) {                // 创建失败
                throw new RuntimeException("Unable to create EGLContext");
            }

            // ===== 5. 创建 PBuffer 离屏 Surface =====
            int[] surfaceAttribs = {
                    EGL14.EGL_WIDTH, mVideoWidth,                     // PBuffer 宽度 = 视频宽度
                    EGL14.EGL_HEIGHT, mVideoHeight,                   // PBuffer 高度 = 视频高度
                    EGL14.EGL_NONE                                    // 列表结束
            };
            mEglSurface = EGL14.eglCreatePbufferSurface(mEglDisplay, configs[0], surfaceAttribs, 0); // 创建 PBuffer
            if (mEglSurface == EGL14.EGL_NO_SURFACE) {                // 创建失败
                throw new RuntimeException("Unable to create EGLSurface");
            }

            // ===== 6. 把 Context 绑定到当前线程 =====
            if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("Unable to make EGL context current"); // 绑定失败
            }

            // ===== 7. 检查 OpenGL ES 版本 =====
            String versionString = GLES30.glGetString(GLES30.GL_VERSION); // 取版本字符串
            Log.i(TAG, "OpenGL Version: " + versionString);          // 记录

            // ===== 8. 初始化 Shader / 纹理 / 顶点缓冲 / FBO =====
            initShaders();                                             // 编译并链接 Shader
            initTextures();                                            // 创建三个 YUV 纹理
            initBuffers();                                             // 准备顶点/纹理坐标缓冲
            initFBO();                                                 // 创建离屏 FBO

            mInitialized = true;                                       // 标记初始化完成
            Log.i(TAG, "OpenGL ES 3.0 initialized successfully");     // 记录成功
        } catch (Exception e) {                                        // 任何异常
            Log.e(TAG, "Failed to initialize OpenGL ES 3.0", e);      // 记录失败
        }
    }

    /**
     * 初始化 FBO（Framebuffer Object）用于离屏渲染。
     * 流程：创建 FBO → 创建关联纹理 → 把纹理挂到 FBO 的颜色附件。
     */
    private void initFBO() {
        GLES30.glGenFramebuffers(1, mFBOIds, 0);                       // 生成 1 个 FBO 对象
        GLES30.glGenTextures(1, mFBOTexIds, 0);                        // 生成 1 个纹理对象

        // 配置 FBO 关联纹理（RGBA + 视频分辨率）
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFBOTexIds[0]);     // 绑定纹理
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,    // 分配存储（RGBA8888）
                mVideoWidth, mVideoHeight, 0,                           // 宽高，无 border
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);        // 格式、像素类型、数据为 null
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); // 缩小过滤
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR); // 放大过滤
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); // S 方向截断
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE); // T 方向截断

        // 把纹理挂到 FBO 的颜色附件 0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFBOIds[0]);   // 绑定 FBO
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, // 颜色附件 0
                GLES30.GL_TEXTURE_2D, mFBOTexIds[0], 0);                // 挂载纹理（level=0）

        // 解绑，恢复默认 framebuffer 和纹理
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);            // 绑定默认 framebuffer（屏幕）
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);                 // 解绑纹理

        Log.i(TAG, "FBO initialized");                                  // 日志
    }

    /**
     * 初始化 OpenGL ES 3.0 着色器（顶点 + 片元）。
     * 片元着色器完成 YUV→RGB 转换 + 暖色调 + 饱和度调节。
     */
    private void initShaders() {
        // ===== Vertex Shader（GLSL ES 3.0） =====
        String vertexShaderCode =
                "#version 300 es\n" +                                  // GLSL ES 3.0
                "in vec4 aPosition;\n" +                               // 顶点坐标（in 关键字）
                "in vec2 aTexCoord;\n" +                               // 纹理坐标
                "out vec2 vTexCoord;\n" +                              // 传给片元的 varying
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +                     // 设置顶点位置
                "    vTexCoord = aTexCoord;\n" +                       // 传递纹理坐标
                "}\n";

        // ===== Fragment Shader：YUV(I420) → RGB + 暖色调 + 饱和度（GLSL ES 3.0） =====
        String fragmentShaderCode =
                "#version 300 es\n" +
                "precision mediump float;\n" +                         // 中等精度 float
                "in vec2 vTexCoord;\n" +                               // 从顶点着色器接收
                "uniform sampler2D uYTexture;\n" +                     // Y 纹理采样器
                "uniform sampler2D uUTexture;\n" +                     // U 纹理采样器
                "uniform sampler2D uVTexture;\n" +                     // V 纹理采样器
                "uniform float uWarmth;\n" +                           // 暖色调强度
                "uniform float uSaturation;\n" +                       // 饱和度
                "out vec4 fragColor;\n" +                              // 输出（out 关键字）
                "void main() {\n" +
                // 采样 YUV，并把 U/V 减去 0.5（偏移到 [-0.5, 0.5]）
                "    float y = texture(uYTexture, vTexCoord).r;\n" +
                "    float u = texture(uUTexture, vTexCoord).r - 0.5;\n" +
                "    float v = texture(uVTexture, vTexCoord).r - 0.5;\n" +
                // BT.601 YUV→RGB 转换公式
                "    float r = y + 1.402 * v;\n" +
                "    float g = y - 0.344 * u - 0.714 * v;\n" +
                "    float b = y + 1.772 * u;\n" +
                "    vec3 color = vec3(r, g, b);\n" +
                // 计算亮度（用于饱和度调整）
                "    float luminance = dot(color, vec3(0.299, 0.587, 0.114));\n" +
                // mix() 调整饱和度：luminance 是灰度，uSaturation 越大越鲜艳
                "    color = mix(vec3(luminance), color, uSaturation);\n" +
                // 暖色调：增加 R 和 G（弱），减少 B
                "    color.r = color.r + uWarmth * (1.0 - color.r);\n" +
                "    color.g = color.g + uWarmth * 0.3 * (1.0 - color.g);\n" +
                "    color.b = color.b - uWarmth * 0.7 * color.b;\n" +
                // 输出颜色并 clamp 到 [0,1]
                "    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
                "}\n";

        // ===== 编译 Shader =====
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);     // 编译顶点着色器
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode); // 编译片元着色器

        // ===== 创建 Program 并链接 =====
        mProgramId = GLES30.glCreateProgram();                          // 创建 program
        GLES30.glAttachShader(mProgramId, vertexShader);               // 附加顶点着色器
        GLES30.glAttachShader(mProgramId, fragmentShader);             // 附加片元着色器
        GLES30.glLinkProgram(mProgramId);                              // 链接

        // ===== 获取 attribute 和 uniform 的句柄 =====
        mPositionHandle = GLES30.glGetAttribLocation(mProgramId, "aPosition"); // 顶点坐标句柄
        mTexCoordHandle = GLES30.glGetAttribLocation(mProgramId, "aTexCoord"); // 纹理坐标句柄
        mYTexHandle = GLES30.glGetUniformLocation(mProgramId, "uYTexture");    // Y 纹理句柄
        mUTexHandle = GLES30.glGetUniformLocation(mProgramId, "uUTexture");    // U 纹理句柄
        mVTexHandle = GLES30.glGetUniformLocation(mProgramId, "uVTexture");    // V 纹理句柄

        Log.i(TAG, "Shaders initialized");                             // 记录初始化完成
    }

    /**
     * 初始化三个 YUV 纹理（设置过滤和包裹方式，暂不分配存储）。
     */
    private void initTextures() {
        GLES30.glGenTextures(1, mYTexIds, 0);                          // 生成 Y 纹理
        GLES30.glGenTextures(1, mUTexIds, 0);                          // 生成 U 纹理
        GLES30.glGenTextures(1, mVTexIds, 0);                          // 生成 V 纹理

        // 配置 Y 纹理参数
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mYTexIds[0]);       // 绑定 Y 纹理
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); // 缩小线性
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR); // 放大线性
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); // S 截断
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE); // T 截断

        // 配置 U 纹理参数
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mUTexIds[0]);       // 绑定 U 纹理
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        // 配置 V 纹理参数
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mVTexIds[0]);       // 绑定 V 纹理
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        Log.i(TAG, "Textures initialized");                            // 记录
    }

    /**
     * 初始化顶点 / 纹理坐标 NIO Buffer（GPU 可直接访问的 native 内存）。
     */
    private void initBuffers() {
        // 顶点坐标 Buffer（4 个 float × 2 = 8 个 float = 32 bytes）
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4) // 分配 direct 内存
                .order(ByteOrder.nativeOrder())                              // 本地字节序
                .asFloatBuffer()                                             // 转 FloatBuffer
                .put(VERTEX_COORDS);                                         // 写入顶点数据
        mVertexBuffer.rewind();                                              // 重置 position 到 0

        // 纹理坐标 Buffer（同样大小）
        mTexCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_COORDS);
        mTexCoordBuffer.rewind();
    }

    /**
     * 加载（编译）单个 Shader。
     * @param type GL_VERTEX_SHADER 或 GL_FRAGMENT_SHADER
     * @param shaderCode GLSL 源码
     * @return Shader 对象 ID（编译失败时返回 0）
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES30.glCreateShader(type);      // 创建 shader 对象
        GLES30.glShaderSource(shader, shaderCode);     // 设置源码
        GLES30.glCompileShader(shader);                // 编译
        return shader;                                 // 返回 ID（实际未检查编译状态）
    }

    /**
     * VideoSink 接口实现：WebRTC 传入视频帧时回调。
     * @param frame WebRTC 视频帧
     */
    @Override
    public void onFrame(VideoFrame frame) {
        if (frame == null || frame.getBuffer() == null) {  // 帧为空（防御性检查）
            mTargetSink.onFrame(frame);                   // 直接透传
            return;
        }

        // 转换为 I420 格式（YUV planar）
        VideoFrame.I420Buffer i420 = null;
        try {
            i420 = frame.getBuffer().toI420();             // 获取 I420 buffer
        } catch (Exception e) {                            // 转换异常
            Log.e(TAG, "Failed to get I420 buffer", e);    // 记录
            mTargetSink.onFrame(frame);                    // 透传原帧
            return;
        }

        if (i420 == null) {                                // I420 为 null
            mTargetSink.onFrame(frame);                    // 透传
            return;
        }

        int width = i420.getWidth();                       // 帧宽度
        int height = i420.getHeight();                     // 帧高度

        try {
            // 调用 processFrame 应用特效（实际渲染到 FBO）
            processFrame(i420, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);       // 记录
        } finally {
            i420.release();                                // 释放 native buffer
        }

        // 透传原始帧给下游 sink（注意：当前实现让下游看到原始帧，而非 GL 处理后的帧）
        mTargetSink.onFrame(frame);                        // 转发给下游
    }

    /**
     * 把 I420 帧上传为 YUV 纹理，绑定 Shader，绘制到 FBO。
     * @param i420 WebRTC I420 buffer
     * @param width 帧宽度
     * @param height 帧高度
     */
    private void processFrame(VideoFrame.I420Buffer i420, int width, int height) {
        if (!mInitialized || !mIsEffectEnabled) {          // 未初始化或特效未启用
            return;                                        // 直接返回，不渲染
        }

        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {         // EGL 未初始化
            return;
        }

        // 确保 EGL 上下文是当前上下文
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            Log.e(TAG, "Failed to make EGL context current"); // 绑定失败
            return;
        }

        // 设置视口和清屏
        GLES30.glViewport(0, 0, width, height);            // GL 渲染区域
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);       // 清屏色：黑不透
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);        // 清屏

        // 上传 YUV 数据到纹理
        int yStride = i420.getStrideY();                    // Y 平面行步长
        int uvStride = i420.getStrideU();                   // U/V 平面行步长
        int uvHeight = (height + 1) / 2;                    // UV 高度（YUV420）

        // Y 纹理上传（完整分辨率）
        ByteBuffer yBuffer = i420.getDataY();               // Y 数据 buffer
        yBuffer.rewind();                                   // 重置 position
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);         // 激活纹理单元 0
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mYTexIds[0]); // 绑定 Y 纹理
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, // LUMINANCE 格式
                yStride, height, 0,                            // 宽高
                GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, yBuffer); // 格式、类型、数据

        // U 纹理上传（1/2 分辨率）
        int uvWidth = (width + 1) / 2;                      // UV 宽度
        ByteBuffer uBuffer = i420.getDataU();               // U 数据
        uBuffer.rewind();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);         // 激活纹理单元 1
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mUTexIds[0]); // 绑定 U 纹理
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE,
                uvStride, uvHeight, 0,
                GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, uBuffer);

        // V 纹理上传（1/2 分辨率）
        ByteBuffer vBuffer = i420.getDataV();               // V 数据
        vBuffer.rewind();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);         // 激活纹理单元 2
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mVTexIds[0]); // 绑定 V 纹理
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE,
                uvStride, uvHeight, 0,
                GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, vBuffer);

        // 启用 Shader Program
        GLES30.glUseProgram(mProgramId);                    // 激活 program

        // 设置纹理单元 uniform
        GLES30.glUniform1i(mYTexHandle, 0);                 // uYTexture = 纹理单元 0
        GLES30.glUniform1i(mUTexHandle, 1);                 // uUTexture = 纹理单元 1
        GLES30.glUniform1i(mVTexHandle, 2);                 // uVTexture = 纹理单元 2

        // 设置暖色调和饱和度 uniform
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uWarmth"), mWarmth);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uSaturation"), mSaturation);

        // 绑定顶点属性
        GLES30.glEnableVertexAttribArray(mPositionHandle);  // 启用 aPosition
        GLES30.glVertexAttribPointer(mPositionHandle, 2, GLES30.GL_FLOAT, false, 0, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(mTexCoordHandle);  // 启用 aTexCoord
        GLES30.glVertexAttribPointer(mTexCoordHandle, 2, GLES30.GL_FLOAT, false, 0, mTexCoordBuffer);

        // 绘制到 FBO（4 顶点 → 2 三角形 → 1 矩形）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFBOIds[0]); // 绑定 FBO
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);          // 绘制

        // 交换缓冲区（PBuffer 上无显示，但 EGL 需要 swap）
        EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);                // 提交渲染结果

        // 解绑 FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);           // 恢复默认
    }

    /**
     * 释放 FBO / EGL 资源。多次调用安全。
     */
    public void release() {
        // 释放 FBO
        if (mFBOIds[0] != 0) {                                          // FBO 存在
            GLES30.glDeleteFramebuffers(1, mFBOIds, 0);                // 删除 FBO
            mFBOIds[0] = 0;                                             // 重置
        }
        if (mFBOTexIds[0] != 0) {                                       // FBO 纹理存在
            GLES30.glDeleteTextures(1, mFBOTexIds, 0);                 // 删除纹理
            mFBOTexIds[0] = 0;                                          // 重置
        }

        // 释放 EGL 资源
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {                     // EGL 已初始化
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

        // 重置为"无"状态
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mEglContext = EGL14.EGL_NO_CONTEXT;
        mEglSurface = EGL14.EGL_NO_SURFACE;
        mInitialized = false;                                          // 标记未初始化

        Log.i(TAG, "Released");                                        // 日志
    }
}
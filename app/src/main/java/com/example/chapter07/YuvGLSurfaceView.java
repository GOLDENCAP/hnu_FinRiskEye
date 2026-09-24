package com.example.chapter07;  // 声明类所在的包路径

// 导入 Android 相关类
import android.content.Context;               // Android 上下文，用于获取系统资源
import android.graphics.SurfaceTexture;      // 纹理 surface，可绑定为 OpenGL 输出目标
import android.opengl.GLES20;                // OpenGL ES 2.0 API
import android.opengl.GLES30;                // OpenGL ES 3.0 API（兼容 2.0）
import android.opengl.GLSurfaceView;         // Android 提供的 GLSurfaceView，自动管理 GL 线程
import android.util.AttributeSet;            // XML 属性集合
import android.util.Log;                     // 日志工具

import androidx.annotation.Nullable;         // AndroidX 的可空注解

// 导入 WebRTC 相关类
import org.webrtc.VideoFrame;               // WebRTC 视频帧

// NIO 相关
import java.nio.ByteBuffer;                  // 直接字节缓冲区（用于 GPU 上传）
import java.nio.ByteOrder;                   // 字节序
import java.nio.FloatBuffer;                 // 浮点缓冲区（用于顶点/纹理坐标）

// 并发工具
import java.util.concurrent.CountDownLatch;  // 倒计数锁，用于等待 GL 线程初始化
import java.util.concurrent.atomic.AtomicBoolean;  // 原子布尔，用于跨线程标志

// EGL 和 GL 旧版接口（GLSurfaceView.Renderer 需要的类型）
import javax.microedition.khronos.egl.EGL10;          // 旧版 EGL 接口（本类未直接使用，仅用于实现接口）
import javax.microedition.khronos.egl.EGLConfig;      // 旧版 EGLConfig 类型
import javax.microedition.khronos.egl.EGLDisplay;     // 旧版 EGLDisplay 类型
import javax.microedition.khronos.opengles.GL10;      // 旧版 GL 接口（本类未直接使用）

/**
 * 自定义 GLSurfaceView，支持接收 YUV 数据并通过 OpenGL ES Shader 渲染。
 * 实现 VideoSink 接口，可直接 addSink() 到 WebRTC VideoTrack。
 *
 * 核心功能：
 *   1. 接收 WebRTC 的 YUV 视频帧
 *   2. 把 Y/U/V 三个平面分别上传为 GL 纹理
 *   3. 通过片元着色器把 YUV 转 RGB 并渲染到 Surface
 *   4. 支持暖色调和饱和度的 GPU 实时调节
 *
 * 线程模型：
 *   - UI 线程：调用 setWarmth() 等参数
 *   - WebRTC 线程：调用 onFrame() 推入视频帧
 *   - GL 线程：onSurfaceCreated/onDrawFrame 等回调
 *   - 跨线程操作通过 queueEvent() 切到 GL 线程
 */
public class YuvGLSurfaceView extends GLSurfaceView                                          // 继承 GLSurfaceView（自动管理 EGL/GL 线程）
        implements GLSurfaceView.Renderer,                                                  // 实现 GL Renderer 接口
                   SurfaceTexture.OnFrameAvailableListener,                                 // 实现 SurfaceTexture 帧可用回调（本类未真正使用）
                   org.webrtc.VideoSink {                                                   // 实现 WebRTC 视频接收接口

    private static final String TAG = "YuvGLSurfaceView";  // 日志 TAG
    private static final boolean DEBUG = true;             // 调试日志开关

    // ===== 顶点坐标：(x, y) 归一化到 [-1, 1]，定义一个全屏四边形 =====
    private static final float[] VERTEX_COORDS = {
            -1.0f, -1.0f,  // 左下角
             1.0f, -1.0f,  // 右下角
            -1.0f,  1.0f,  // 左上角
             1.0f,  1.0f   // 右上角
    };

    // ===== 纹理坐标：(s, t) 归一化到 [0, 1]，定义纹理如何映射到顶点 =====
    // 注意：YUV 数据通常原点在左上，所以 t 方向是反的（左下角对应 t=0）
    private static final float[] TEXTURE_COORDS = {
            0.0f, 0.0f,  // 左下 → 纹理左下
            1.0f, 0.0f,  // 右下 → 纹理右下
            0.0f, 1.0f,  // 左上 → 纹理左上
            1.0f, 1.0f   // 右上 → 纹理右上
    };

    // 跨线程标志：是否有新的帧待渲染
    private final AtomicBoolean mHasFrame = new AtomicBoolean(false);
    // 倒计数锁：用于让外部线程等待 GL 线程初始化完成
    private final CountDownLatch mInitLatch = new CountDownLatch(1);

    // 当前纹理分辨率（用于检测分辨率变化）
    private int mCurrentWidth = 0;
    private int mCurrentHeight = 0;

    // YUV 三个平面的 GL 纹理 ID 数组
    private int[] mYTexIds = new int[1];  // Y 平面纹理 ID
    private int[] mUTexIds = new int[1];  // U 平面纹理 ID
    private int[] mVTexIds = new int[1];  // V 平面纹理 ID

    // 顶点/纹理坐标缓冲区
    private FloatBuffer mVertexBuffer;    // 顶点坐标 Buffer
    private FloatBuffer mTexCoordBuffer;  // 纹理坐标 Buffer
    private int mProgramId = 0;           // GL Program 对象 ID
    private int mPositionHandle = -1;     // "aPosition" 属性句柄
    private int mTexCoordHandle = -1;     // "aTexCoord" 属性句柄
    private int mYTexHandle = -1;         // "uYTexture" uniform 句柄
    private int mUTexHandle = -1;         // "uUTexture" uniform 句柄
    private int mVTexHandle = -1;         // "uVTexture" uniform 句柄

    // ===== 暖色调参数（volatile 保证跨线程可见性） =====
    private volatile float mWarmth = 0.15f;          // 暖色调强度
    private volatile float mSaturation = 1.1f;       // 饱和度
    private volatile int mWarmthLocation = -1;        // shader 中 uWarmth uniform 的位置
    private volatile int mSaturationLocation = -1;   // shader 中 uSaturation uniform 的位置

    private SurfaceTexture mSurfaceTexture;  // 外部纹理（本类未实际使用）
    private boolean mGLES30Supported = false; // 是否支持 GLES 3.0

    /** 构造函数（代码中创建） */
    public YuvGLSurfaceView(Context context) {
        this(context, null);
    }

    /** 构造函数（XML 中创建） */
    public YuvGLSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);  // 调用父类构造函数

        // 让 GLSurfaceView 默认处理 EGL 初始化
        // 请求 GLES 3.0（如果不支持会自动回退到 2.0）
        setEGLContextClientVersion(3);
        // 设置 Renderer（本类实现了 Renderer 接口）
        setRenderer(this);
        // 设置渲染模式：连续渲染（默认每帧都重新绘制）
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** 设置暖色调强度 */
    public void setWarmth(float warmth) {
        this.mWarmth = warmth;
    }

    /** 设置饱和度 */
    public void setSaturation(float saturation) {
        this.mSaturation = saturation;
    }

    /**
     * GL 线程：Surface 创建时回调，只执行一次。
     * 作用：初始化 Shader、纹理等 GL 资源。
     */
    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        if (DEBUG) Log.d(TAG, "onSurfaceCreated");

        // ===== 1. 检测 GLES 版本（用于决定使用 GLSL 1.0 还是 3.0） =====
        String versionString = GLES20.glGetString(GLES20.GL_VERSION);
        if (DEBUG) Log.i(TAG, "GL Version: " + versionString);
        // 通过版本字符串判断是否支持 GLES 3.0
        mGLES30Supported = versionString != null && versionString.contains("OpenGL ES 3.");

        if (DEBUG) Log.i(TAG, "Using GLES " + (mGLES30Supported ? "3.0" : "2.0"));

        // ===== 2. 设置 GL 全局状态 =====
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);  // 清屏颜色：黑色不透明
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);       // 禁用深度测试（2D 渲染不需要）
        GLES20.glDisable(GLES20.GL_BLEND);            // 禁用混合

        // ===== 3. 准备顶点/纹理坐标 Buffer（分配直接内存） =====
        // allocateDirect 分配 native 内存（GPU 可直接访问）
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4)  // float 占 4 字节
                .order(ByteOrder.nativeOrder())  // 设置为本地字节序
                .asFloatBuffer()                // 转换为 FloatBuffer
                .put(VERTEX_COORDS);            // 写入数据
        mVertexBuffer.rewind();  // 重置 position 到 0

        mTexCoordBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_COORDS);
        mTexCoordBuffer.rewind();

        // ===== 4. 初始化 Shader 和纹理 =====
        initShaders();   // 编译并链接 shader program
        initTextures();  // 生成 Y/U/V 三个纹理

        // 释放倒计数锁，通知外部线程 GL 初始化完成
        mInitLatch.countDown();
    }

    /**
     * 初始化 Shader：根据是否支持 GLES 3.0 选择不同的 GLSL 版本
     */
    private void initShaders() {
        if (DEBUG) Log.d(TAG, "initShaders, GLES30: " + mGLES30Supported);

        String vertexShaderCode;    // 顶点着色器源码
        String fragmentShaderCode;  // 片元着色器源码

        if (mGLES30Supported) {
            // ===== GLES 3.0 版本（使用新版 GLSL 语法） =====

            // Vertex Shader：把顶点坐标和纹理坐标传递给片元着色器
            vertexShaderCode =
                    "#version 300 es\n" +                  // GLSL ES 3.0
                    "in vec4 aPosition;\n" +               // 顶点坐标（GLES3 用 in/out）
                    "in vec2 aTexCoord;\n" +               // 纹理坐标
                    "out vec2 vTexCoord;\n" +              // 传递给片元的变量
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +     // 设置顶点位置
                    "    vTexCoord = aTexCoord;\n" +       // 传递纹理坐标
                    "}\n";

            // Fragment Shader：YUV → RGB 转换 + 暖色调处理
            fragmentShaderCode =
                    "#version 300 es\n" +
                    "precision mediump float;\n" +         // 中等精度（性能更好）
                    "in vec2 vTexCoord;\n" +
                    "uniform sampler2D uYTexture;\n" +     // Y 纹理采样器
                    "uniform sampler2D uUTexture;\n" +     // U 纹理采样器
                    "uniform sampler2D uVTexture;\n" +     // V 纹理采样器
                    "uniform float uWarmth;\n" +           // 暖色调强度
                    "uniform float uSaturation;\n" +       // 饱和度
                    "out vec4 fragColor;\n" +              // 输出颜色（GLES3 用 out）
                    "void main() {\n" +
                    // 采样 YUV（注意 U/V 要 -0.5 偏移到 [-0.5, 0.5]）
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
                    // 用 mix() 调整饱和度（mix 返回 luminance 和 color 的插值）
                    "    color = mix(vec3(luminance), color, uSaturation);\n" +
                    // 暖色调：增加 R 和 G，减少 B
                    "    color.r = color.r + uWarmth * (1.0 - color.r);\n" +
                    "    color.g = color.g + uWarmth * 0.3 * (1.0 - color.g);\n" +
                    "    color.b = color.b - uWarmth * 0.7 * color.b;\n" +
                    // 输出颜色（clamp 到 [0,1]）
                    "    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
                    "}\n";
        } else {
            // ===== GLES 2.0 版本（兼容旧设备，使用 attribute/varying 语法） =====

            vertexShaderCode =
                    "attribute vec4 aPosition;\n" +        // GLES2 用 attribute/varying
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}\n";

            // 几乎与 GLES3 版本相同，但用 texture2D 和 gl_FragColor
            fragmentShaderCode =
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D uYTexture;\n" +
                    "uniform sampler2D uUTexture;\n" +
                    "uniform sampler2D uVTexture;\n" +
                    "uniform float uWarmth;\n" +
                    "uniform float uSaturation;\n" +
                    "void main() {\n" +
                    "    float y = texture2D(uYTexture, vTexCoord).r;\n" +
                    "    float u = texture2D(uUTexture, vTexCoord).r - 0.5;\n" +
                    "    float v = texture2D(uVTexture, vTexCoord).r - 0.5;\n" +
                    "    float r = y + 1.402 * v;\n" +
                    "    float g = y - 0.344 * u - 0.714 * v;\n" +
                    "    float b = y + 1.772 * u;\n" +
                    "    vec3 color = vec3(r, g, b);\n" +
                    "    float luminance = dot(color, vec3(0.299, 0.587, 0.114));\n" +
                    "    color = mix(vec3(luminance), color, uSaturation);\n" +
                    "    color.r = color.r + uWarmth * (1.0 - color.r);\n" +
                    "    color.g = color.g + uWarmth * 0.3 * (1.0 - color.g);\n" +
                    "    color.b = color.b - uWarmth * 0.7 * color.b;\n" +
                    "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
                    "}\n";
        }

        // ===== 编译 Shader =====
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        // ===== 创建并链接 Program =====
        mProgramId = GLES20.glCreateProgram();   // 创建 Program
        GLES20.glAttachShader(mProgramId, vertexShader);   // 附加顶点 shader
        GLES20.glAttachShader(mProgramId, fragmentShader); // 附加片元 shader
        GLES20.glLinkProgram(mProgramId);        // 链接

        // 检查链接结果
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgramId, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            // 链接失败：打印日志（program 仍可使用，但渲染可能异常）
            Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(mProgramId));
            return;
        }

        // ===== 获取 attribute 和 uniform 的句柄（后续绑定时用） =====
        mPositionHandle = GLES20.glGetAttribLocation(mProgramId, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgramId, "aTexCoord");
        mYTexHandle = GLES20.glGetUniformLocation(mProgramId, "uYTexture");
        mUTexHandle = GLES20.glGetUniformLocation(mProgramId, "uUTexture");
        mVTexHandle = GLES20.glGetUniformLocation(mProgramId, "uVTexture");
        mWarmthLocation = GLES20.glGetUniformLocation(mProgramId, "uWarmth");
        mSaturationLocation = GLES20.glGetUniformLocation(mProgramId, "uSaturation");

        if (DEBUG) Log.d(TAG, "Shader initialized, programId: " + mProgramId);
    }

    /**
     * 编译单个 Shader，并检查编译错误
     * @param type GL_VERTEX_SHADER 或 GL_FRAGMENT_SHADER
     * @param shaderCode GLSL 源码
     * @return Shader ID，编译失败返回 0
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);          // 创建 Shader 对象
        GLES20.glShaderSource(shader, shaderCode);          // 设置源码
        GLES20.glCompileShader(shader);                     // 编译
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            // 编译失败：打印错误日志并删除 shader
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;  // 返回 0 表示失败
        }
        return shader;
    }

    /**
     * 初始化三个 YUV 纹理（1x1 placeholder，实际数据由 uploadYuvData 上传）
     */
    private void initTextures() {
        // 生成三个纹理对象
        GLES20.glGenTextures(1, mYTexIds, 0);
        GLES20.glGenTextures(1, mUTexIds, 0);
        GLES20.glGenTextures(1, mVTexIds, 0);

        if (DEBUG) Log.d(TAG, "Textures generated: Y=" + mYTexIds[0] + ", U=" + mUTexIds[0] + ", V=" + mVTexIds[0]);

        // 初始化 Y 纹理（先创建一个 1x1 的占位纹理，后续会重新分配大小）
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        setTextureParams(GLES20.GL_TEXTURE_2D);  // 设置纹理参数

        // 初始化 U 纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        setTextureParams(GLES20.GL_TEXTURE_2D);

        // 初始化 V 纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        setTextureParams(GLES20.GL_TEXTURE_2D);
    }

    /**
     * 设置纹理采样参数
     * @param target 纹理目标（通常是 GL_TEXTURE_2D）
     */
    private void setTextureParams(int target) {
        // 缩小时使用线性插值
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        // 放大时使用线性插值
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // S 方向超出纹理坐标时截断到边缘（避免重复采样导致接缝）
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        // T 方向同上
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /**
     * GL 线程：Surface 大小变化时回调（如旋转屏幕、窗口大小改变）
     */
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (DEBUG) Log.d(TAG, "onSurfaceChanged: " + width + "x" + height);
        // 设置视口大小（GL 渲染区域）
        GLES20.glViewport(0, 0, width, height);
    }

    /**
     * GL 线程：每帧渲染回调（连续渲染模式下会被反复调用）
     */
    @Override
    public void onDrawFrame(GL10 gl) {
        // 1. 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 2. 没有新帧或未初始化时直接返回（黑屏）
        if (!mHasFrame.get() || mCurrentWidth == 0 || mCurrentHeight == 0) {
            return;
        }

        if (mProgramId == 0) {
            // Shader 初始化失败时跳过
            if (DEBUG) Log.w(TAG, "Skipping draw - program not initialized");
            return;
        }

        // 3. 启用 Shader Program
        GLES20.glUseProgram(mProgramId);

        // 4. 更新 uniform：暖色调和饱和度（可能跨线程被修改）
        if (mWarmthLocation != -1) {
            GLES20.glUniform1f(mWarmthLocation, mWarmth);
        }
        if (mSaturationLocation != -1) {
            GLES20.glUniform1f(mSaturationLocation, mSaturation);
        }

        // 5. 绑定 Y 纹理到纹理单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);  // 激活纹理单元 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);  // 绑定 Y 纹理
        GLES20.glUniform1i(mYTexHandle, 0);  // 告诉 shader：uYTexture 使用纹理单元 0

        // 6. 绑定 U 纹理到纹理单元 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
        GLES20.glUniform1i(mUTexHandle, 1);

        // 7. 绑定 V 纹理到纹理单元 2
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
        GLES20.glUniform1i(mVTexHandle, 2);

        // 8. 绑定顶点坐标 attribute
        GLES20.glEnableVertexAttribArray(mPositionHandle);  // 启用 attribute
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);  // 2 个 float 组成一个顶点

        // 9. 绑定纹理坐标 attribute
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

        // 10. 绘制（4 个顶点，组成 2 个三角形）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 11. 关闭 attribute（节省资源）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);

        // 12. 重置帧标志（下一帧渲染前需要重新上传纹理）
        mHasFrame.set(false);
    }

    /**
     * VideoSink 接口实现：接收 WebRTC VideoFrame。
     * 该方法运行在 WebRTC 工作线程，需要切到 GL 线程操作纹理。
     */
    @Override
    public void onFrame(VideoFrame frame) {
        // 转换为 I420 格式（YUV420 planar）
        VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
        // 提取分辨率和步长
        int width = i420.getWidth();
        int height = i420.getHeight();
        int yStride = i420.getStrideY();
        int uvStride = i420.getStrideU();
        int uvHeight = (height + 1) / 2;  // UV 平面高度（向上取整）

        // 取出 YUV 三个平面的 ByteBuffer
        ByteBuffer yBuf = i420.getDataY();
        ByteBuffer uBuf = i420.getDataU();
        ByteBuffer vBuf = i420.getDataV();

        // 把 ByteBuffer 数据读出到 byte[]（分配新的数组）
        byte[] yData = new byte[yStride * height];
        byte[] uData = new byte[uvStride * uvHeight];
        byte[] vData = new byte[uvStride * uvHeight];

        // 重置 position 准备读取
        yBuf.rewind();
        uBuf.rewind();
        vBuf.rewind();

        // 读取所有数据
        yBuf.get(yData);
        uBuf.get(uData);
        vBuf.get(vData);

        // 释放 I420 buffer（native 内存）
        i420.release();

        // 上传到 GPU（内部会用 queueEvent 切到 GL 线程）
        uploadYuvData(yData, uData, vData, width, height, yStride, uvStride);
    }

    /**
     * 将 YUV 数据上传到 GPU 纹理。可从任意线程调用（内部 queueEvent 切到 GL 线程）。
     *
     * @param yData Y 平面数据
     * @param uData U 平面数据
     * @param vData V 平面数据
     * @param width 视频宽度
     * @param height 视频高度
     * @param yStride Y 平面行步长
     * @param uvStride U/V 平面行步长
     */
    public void uploadYuvData(final byte[] yData, final byte[] uData, final byte[] vData,
                              final int width, final int height,
                              final int yStride, final int uvStride) {
        // UV 平面的高度（向上取整，因为 YUV420 是高度一半，奇数高度需要 +1）
        final int uvHeight = (height + 1) / 2;

        // queueEvent 把任务切到 GL 线程执行（GL 操作必须在 GL 线程！）
        queueEvent(() -> {
            // 检查分辨率是否变化
            boolean sizeChanged = (width != mCurrentWidth || height != mCurrentHeight);
            if (sizeChanged) {
                // 更新当前分辨率
                mCurrentWidth = width;
                mCurrentHeight = height;

                if (DEBUG) Log.d(TAG, "Texture size changed: " + width + "x" + height);

                // ===== 重新分配三个纹理的 GPU 存储空间 =====
                // Y 纹理（完整分辨率）
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, yStride, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);

                // U 纹理（1/4 分辨率）
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvStride, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);

                // V 纹理（1/4 分辨率）
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, uvStride, uvHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
            }

            // ===== 上传 Y 平面数据（用 TexSubImage 增量更新） =====
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTexIds[0]);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, yStride, height, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(yData));

            // ===== 上传 U 平面数据 =====
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUTexIds[0]);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, uvStride, uvHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(uData));

            // ===== 上传 V 平面数据 =====
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mVTexIds[0]);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, uvStride, uvHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(vData));

            // 标记有帧需要渲染
            mHasFrame.set(true);
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // SurfaceTexture 帧可用回调（本类未使用 SurfaceTexture，但需要实现接口）
        requestRender();  // 请求 GL 线程重新渲染
    }

    /**
     * 释放 GL 资源（在 GL 线程上执行）
     */
    public void release() {
        try {
            // queueEvent 切到 GL 线程删除资源
            queueEvent(() -> {
                if (mProgramId != 0) {
                    GLES20.glDeleteProgram(mProgramId);  // 删除 Shader Program
                    mProgramId = 0;
                }
                // 删除三个纹理
                GLES20.glDeleteTextures(1, mYTexIds, 0);
                GLES20.glDeleteTextures(1, mUTexIds, 0);
                GLES20.glDeleteTextures(1, mVTexIds, 0);
                // 释放 SurfaceTexture
                if (mSurfaceTexture != null) {
                    mSurfaceTexture.release();
                    mSurfaceTexture = null;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "release error", e);
        }
    }

    /**
     * 等待 GL 线程初始化完成（外部线程调用，避免在 GL 资源未就绪时使用）
     */
    public void waitForSurfaceReady() {
        try {
            // 阻塞直到 onSurfaceCreated 中的 countDownLatch.countDown() 被调用
            mInitLatch.await();
        } catch (InterruptedException e) {
            // 被中断时设置中断标志
            Thread.currentThread().interrupt();
        }
    }
}
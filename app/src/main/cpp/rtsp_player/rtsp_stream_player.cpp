/**
 * RTSP Stream Player - C++层RTSP流媒体播放器实现
 */

#include "rtsp_stream_player.h"
#include "rtsp_player_callback.h"
#include <android/log.h>

#define LOG_TAG "RtspStreamPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

RtspStreamPlayer::RtspStreamPlayer()
    : mIsPlaying(false)
    , mIsPaused(false)
    , mShouldStop(false)
    , mFormatContext(nullptr)
    , mCodecContext(nullptr)
    , mFrame(nullptr)
    , mRgbaFrame(nullptr)
    , mSwsContext(nullptr)
    , mPacket(nullptr)
    , mDecodeThread(nullptr)
    , mRenderThread(nullptr)
    , mEglDisplay(EGL_NO_DISPLAY)
    , mEglSurface(EGL_NO_SURFACE)
    , mEglContext(EGL_NO_CONTEXT)
    , mShaderProgram(0)
    , mTextureId(0)
    , mVboId(0)
    , mVideoWidth(0)
    , mVideoHeight(0)
    , mSurfaceWidth(0)
    , mSurfaceHeight(0)
{
}

RtspStreamPlayer::~RtspStreamPlayer()
{
    stopPlay();
}

bool RtspStreamPlayer::initEGL(ANativeWindow* window)
{
    // 获取Surface的实际尺寸
    mSurfaceWidth = ANativeWindow_getWidth(window);
    mSurfaceHeight = ANativeWindow_getHeight(window);
    LOGI("Surface actual size: %dx%d", mSurfaceWidth, mSurfaceHeight);

    // 设置窗口缓冲区格式和尺寸为Surface实际尺寸
    int ret = ANativeWindow_setBuffersGeometry(window, mSurfaceWidth, mSurfaceHeight, WINDOW_FORMAT_RGBA_8888);
    if (ret < 0) {
        LOGE("ANativeWindow_setBuffersGeometry failed: %d", ret);
    }

    // 获取默认显示
    mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    // 初始化EGL
    EGLint major, minor;
    if (!eglInitialize(mEglDisplay, &major, &minor)) {
        LOGE("eglInitialize failed");
        mEglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // 配置EGL
    EGLConfig config;
    EGLint numConfigs;
    EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES_BIT,
        EGL_NONE
    };

    if (!eglChooseConfig(mEglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    // 创建EGL Surface
    mEglSurface = eglCreateWindowSurface(mEglDisplay, config, window, nullptr);
    if (mEglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }

    // 创建EGL Context
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    mEglContext = eglCreateContext(mEglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (mEglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    // 激活Context
    if (!eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }

    LOGI("EGL initialized: %d.%d", major, minor);
    return true;
}

void RtspStreamPlayer::destroyEGL()
{
    if (mEglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (mEglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = EGL_NO_CONTEXT;
        }

        if (mEglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(mEglDisplay, mEglSurface);
            mEglSurface = EGL_NO_SURFACE;
        }

        eglTerminate(mEglDisplay);
        mEglDisplay = EGL_NO_DISPLAY;
    }
}

bool RtspStreamPlayer::initGL(int width, int height)
{
    // 顶点着色器 - 使用视频坐标 [-1,1]
    const char* vertexShader =
        "attribute vec4 aPosition;\n"
        "attribute vec2 aTexCoord;\n"
        "varying vec2 vTexCoord;\n"
        "void main() {\n"
        "    gl_Position = aPosition;\n"
        "    vTexCoord = aTexCoord;\n"
        "}\n";

    // 片段着色器
    const char* fragmentShader =
        "precision mediump float;\n"
        "varying vec2 vTexCoord;\n"
        "uniform sampler2D uTexture;\n"
        "void main() {\n"
        "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
        "}\n";

    // 编译顶点着色器
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &vertexShader, nullptr);
    glCompileShader(vs);

    GLint compiled;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGE("Vertex shader compile failed");
        return false;
    }

    // 编译片段着色器
    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &fragmentShader, nullptr);
    glCompileShader(fs);

    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGE("Fragment shader compile failed");
        glDeleteShader(vs);
        return false;
    }

    // 创建Program
    mShaderProgram = glCreateProgram();
    glAttachShader(mShaderProgram, vs);
    glAttachShader(mShaderProgram, fs);
    glLinkProgram(mShaderProgram);

    GLint linked;
    glGetProgramiv(mShaderProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Program link failed");
        glDeleteShader(vs);
        glDeleteShader(fs);
        return false;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);

    // 创建四边形顶点数据 - 填满整个屏幕
    float vertices[] = {
        // positions   // texCoords
        -1.0f, -1.0f,  0.0f, 1.0f,
         1.0f, -1.0f,  1.0f, 1.0f,
        -1.0f,  1.0f,  0.0f, 0.0f,
         1.0f,  1.0f,  1.0f, 0.0f
    };

    glGenBuffers(1, &mVboId);
    glBindBuffer(GL_ARRAY_BUFFER, mVboId);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // 创建纹理 - 使用视频尺寸
    glGenTextures(1, &mTextureId);
    glBindTexture(GL_TEXTURE_2D, mTextureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    // 设置视口为Surface的实际尺寸
    glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);

    LOGI("OpenGL initialized: viewport=%dx%d, texture=%dx%d", mSurfaceWidth, mSurfaceHeight, width, height);
    return true;
}

void RtspStreamPlayer::destroyGL()
{
    if (mShaderProgram) {
        glDeleteProgram(mShaderProgram);
        mShaderProgram = 0;
    }

    if (mTextureId) {
        glDeleteTextures(1, &mTextureId);
        mTextureId = 0;
    }

    if (mVboId) {
        glDeleteBuffers(1, &mVboId);
        mVboId = 0;
    }
}

void RtspStreamPlayer::renderFrame(uint8_t* rgba, int width, int height)
{
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(mShaderProgram);

    // 设置顶点属性
    GLint positionLoc = glGetAttribLocation(mShaderProgram, "aPosition");
    GLint texCoordLoc = glGetAttribLocation(mShaderProgram, "aTexCoord");
    GLint textureLoc = glGetUniformLocation(mShaderProgram, "uTexture");

    glBindBuffer(GL_ARRAY_BUFFER, mVboId);

    glEnableVertexAttribArray(positionLoc);
    glVertexAttribPointer(positionLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    glEnableVertexAttribArray(texCoordLoc);
    glVertexAttribPointer(texCoordLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    // 更新纹理 - 使用视频尺寸
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, mTextureId);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glUniform1i(textureLoc, 0);

    // 绘制
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // 交换缓冲
    eglSwapBuffers(mEglDisplay, mEglSurface);
}

void RtspStreamPlayer::decodeThreadFunc()
{
    LOGI("Decode thread started");

    // 打开RTSP流
    mFormatContext = avformat_alloc_context();

    // 设置连接超时选项
    AVDictionary* options = nullptr;
    av_dict_set(&options, "buffer_size", "1024000", 0);
    av_dict_set(&options, "rtsp_transport", "tcp", 0);
    av_dict_set(&options, "stimeout", "10000000", 0);  // 10秒超时
    av_dict_set(&options, "max_delay", "5000000", 0);
    av_dict_set(&options, "connect_timeout", "5000000", 0);

    LOGI("Opening RTSP stream: %s", mRtspUrl.c_str());

    int ret = avformat_open_input(&mFormatContext, mRtspUrl.c_str(), nullptr, &options);
    if (ret < 0) {
        char errbuf[128];
        av_strerror(ret, errbuf, sizeof(errbuf));
        mErrorMessage = "Failed to open input: " + std::string(errbuf);
        LOGE("Failed to open input: %s (error %d)", errbuf, ret);
        mIsPlaying = false;
        return;
    }

    LOGI("RTSP stream opened, finding stream info...");

    // 获取流信息
    ret = avformat_find_stream_info(mFormatContext, nullptr);
    if (ret < 0) {
        char errbuf[128];
        av_strerror(ret, errbuf, sizeof(errbuf));
        mErrorMessage = "Failed to find stream info: " + std::string(errbuf);
        LOGE("Failed to find stream info: %s", errbuf);
        avformat_close_input(&mFormatContext);
        mIsPlaying = false;
        return;
    }

    LOGI("Stream info found, nb_streams: %d", mFormatContext->nb_streams);

    // 查找视频流
    int videoStreamIdx = -1;
    for (unsigned int i = 0; i < mFormatContext->nb_streams; i++) {
        if (mFormatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIdx = i;
            LOGI("Found video stream at index %d", i);
            break;
        }
    }

    if (videoStreamIdx == -1) {
        mErrorMessage = "No video stream found";
        LOGE("No video stream found");
        avformat_close_input(&mFormatContext);
        mIsPlaying = false;
        return;
    }

    // 获取解码器
    AVCodecParameters* codecParams = mFormatContext->streams[videoStreamIdx]->codecpar;
    const AVCodec* codec = avcodec_find_decoder(codecParams->codec_id);
    if (!codec) {
        mErrorMessage = "Codec not found: " + std::to_string(codecParams->codec_id);
        LOGE("Codec not found: %d", codecParams->codec_id);
        avformat_close_input(&mFormatContext);
        mIsPlaying = false;
        return;
    }

    LOGI("Codec found: %s", codec->name);

    // 创建解码器上下文
    mCodecContext = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(mCodecContext, codecParams);

    ret = avcodec_open2(mCodecContext, codec, nullptr);
    if (ret < 0) {
        char errbuf[128];
        av_strerror(ret, errbuf, sizeof(errbuf));
        mErrorMessage = "Failed to open codec: " + std::string(errbuf);
        LOGE("Failed to open codec: %s", errbuf);
        avcodec_free_context(&mCodecContext);
        avformat_close_input(&mFormatContext);
        mIsPlaying = false;
        return;
    }

    mVideoWidth = mCodecContext->width;
    mVideoHeight = mCodecContext->height;

    LOGI("Video stream configured: %dx%d, pixel format: %d", mVideoWidth, mVideoHeight, mCodecContext->pix_fmt);

    // 通知Java层视频尺寸变化
    notifyVideoSizeChanged(mVideoWidth, mVideoHeight);

    // 分配帧
    mFrame = av_frame_alloc();
    mRgbaFrame = av_frame_alloc();

    int rgbaSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, mVideoWidth, mVideoHeight, 1);
    uint8_t* rgbaBuffer = (uint8_t*)av_malloc(rgbaSize);
    av_image_fill_arrays(mRgbaFrame->data, mRgbaFrame->linesize, rgbaBuffer,
                        AV_PIX_FMT_RGBA, mVideoWidth, mVideoHeight, 1);

    // 创建SwsContext用于格式转换
    mSwsContext = sws_getContext(mVideoWidth, mVideoHeight, mCodecContext->pix_fmt,
                                  mVideoWidth, mVideoHeight, AV_PIX_FMT_RGBA,
                                  SWS_BILINEAR, nullptr, nullptr, nullptr);

    mPacket = av_packet_alloc();

    LOGI("Starting decode loop...");

    int frameCount = 0;

    // 解码循环
    while (!mShouldStop.load()) {
        if (mIsPaused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        ret = av_read_frame(mFormatContext, mPacket);
        if (ret < 0) {
            if (ret == AVERROR(EAGAIN)) {
                continue;
            }
            char errbuf[128];
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("Failed to read frame: %s", errbuf);
            break;
        }

        if (mPacket->stream_index == videoStreamIdx) {
            ret = avcodec_send_packet(mCodecContext, mPacket);
            if (ret < 0) {
                LOGE("Failed to send packet: %d", ret);
                av_packet_unref(mPacket);
                continue;
            }

            while (ret >= 0) {
                ret = avcodec_receive_frame(mCodecContext, mFrame);
                if (ret == AVERROR(EAGAIN)) {
                    break;
                } else if (ret < 0) {
                    LOGE("Failed to receive frame: %d", ret);
                    break;
                }

                // 转换格式
                sws_scale(mSwsContext, mFrame->data, mFrame->linesize, 0, mVideoHeight,
                         mRgbaFrame->data, mRgbaFrame->linesize);

                // 创建副本放入队列
                AVFrame* frameCopy = av_frame_alloc();
                frameCopy->width = mVideoWidth;
                frameCopy->height = mVideoHeight;
                frameCopy->format = AV_PIX_FMT_RGBA;

                int frameSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, mVideoWidth, mVideoHeight, 1);
                uint8_t* frameBuffer = (uint8_t*)av_malloc(frameSize);
                memcpy(frameBuffer, mRgbaFrame->data[0], frameSize);

                av_image_fill_arrays(frameCopy->data, frameCopy->linesize, frameBuffer,
                                   AV_PIX_FMT_RGBA, mVideoWidth, mVideoHeight, 1);

                {
                    std::lock_guard<std::mutex> lock(mFrameQueueMutex);
                    // 队列最多保留3帧
                    while (mFrameQueue.size() >= 3) {
                        AVFrame* oldFrame = mFrameQueue.front();
                        mFrameQueue.pop();
                        av_free(oldFrame->data[0]);
                        av_frame_free(&oldFrame);
                    }
                    mFrameQueue.push(frameCopy);
                }

                frameCount++;
                if (frameCount % 25 == 0) {
                    LOGI("Decoded %d frames, queue size: %zu", frameCount, mFrameQueue.size());
                }
            }
        }

        av_packet_unref(mPacket);
    }

    LOGI("Decode loop ended, total frames decoded: %d", frameCount);

    // 清理
    av_packet_free(&mPacket);
    if (mSwsContext) {
        sws_freeContext(mSwsContext);
    }
    av_frame_free(&mFrame);
    if (mRgbaFrame) {
        av_free(mRgbaFrame->data[0]);
        av_frame_free(&mRgbaFrame);
    }
    if (mCodecContext) {
        avcodec_close(mCodecContext);
        avcodec_free_context(&mCodecContext);
    }
    if (mFormatContext) {
        avformat_close_input(&mFormatContext);
        avformat_free_context(mFormatContext);
    }

    LOGI("Decode thread ended");
}

void RtspStreamPlayer::renderThreadFunc()
{
    LOGI("Render thread started");

    // 确保EGL context在当前线程上激活
    if (!eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
        LOGE("Failed to make EGL context current in render thread");
        return;
    }

    LOGI("EGL context made current in render thread");

    int renderCount = 0;

    while (!mShouldStop.load() || !mFrameQueue.empty()) {
        if (mIsPaused.load() || mFrameQueue.empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(33));
            continue;
        }

        AVFrame* frame = nullptr;
        {
            std::lock_guard<std::mutex> lock(mFrameQueueMutex);
            if (!mFrameQueue.empty()) {
                frame = mFrameQueue.front();
                mFrameQueue.pop();
            }
        }

        if (frame) {
            // 确保EGL context仍然是激活的
            if (!eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                LOGE("Failed to make EGL context current before render");
                av_free(frame->data[0]);
                av_frame_free(&frame);
                continue;
            }

            renderFrame(frame->data[0], frame->width, frame->height);
            av_free(frame->data[0]);
            av_frame_free(&frame);

            renderCount++;
            if (renderCount % 25 == 0) {
                LOGI("Rendered %d frames", renderCount);
            }
        }
    }

    LOGI("Render thread ended, total frames rendered: %d", renderCount);
}

int RtspStreamPlayer::startPlay(const char* url, ANativeWindow* window)
{
    if (mIsPlaying.load()) {
        LOGE("Already playing");
        return -1;
    }

    mRtspUrl = url;
    mShouldStop = false;
    mIsPaused = false;
    mIsPlaying = true;
    mErrorMessage.clear();
    mVideoWidth = 0;
    mVideoHeight = 0;

    LOGI("Starting decode thread first...");

    // 先启动解码线程，让它连接RTSP流并获取视频信息
    mDecodeThread = new std::thread(&RtspStreamPlayer::decodeThreadFunc, this);

    // 等待视频信息获取（最多等待60秒）
    LOGI("Waiting for stream info...");
    for (int i = 0; i < 600 && mVideoWidth == 0 && mIsPlaying.load(); i++) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (mVideoWidth == 0 || !mIsPlaying.load()) {
        LOGE("Stream not ready, timeout or error");
        mShouldStop = true;
        if (mDecodeThread && mDecodeThread->joinable()) {
            mDecodeThread->join();
            delete mDecodeThread;
            mDecodeThread = nullptr;
        }
        mIsPlaying = false;
        return -3;
    }

    LOGI("Stream ready: %dx%d, initializing renderer", mVideoWidth, mVideoHeight);

    // 初始化EGL
    if (!initEGL(window)) {
        LOGE("Failed to initialize EGL");
        mShouldStop = true;
        if (mDecodeThread && mDecodeThread->joinable()) {
            mDecodeThread->join();
            delete mDecodeThread;
            mDecodeThread = nullptr;
        }
        mIsPlaying = false;
        return -2;
    }

    // 初始化OpenGL
    if (!initGL(mVideoWidth, mVideoHeight)) {
        LOGE("Failed to initialize OpenGL");
        destroyEGL();
        mShouldStop = true;
        if (mDecodeThread && mDecodeThread->joinable()) {
            mDecodeThread->join();
            delete mDecodeThread;
            mDecodeThread = nullptr;
        }
        mIsPlaying = false;
        return -4;
    }

    // 启动渲染线程
    mRenderThread = new std::thread(&RtspStreamPlayer::renderThreadFunc, this);

    LOGI("Playback started");
    return 0;
}

void RtspStreamPlayer::stopPlay()
{
    if (!mIsPlaying.load()) {
        return;
    }

    LOGI("Stopping playback...");

    // 1. 设置停止标志
    mShouldStop = true;
    mIsPlaying = false;

    // 2. 先停止解码线程
    if (mDecodeThread) {
        LOGI("Waiting for decode thread to finish...");
        if (mDecodeThread->joinable()) {
            mDecodeThread->join();
        }
        delete mDecodeThread;
        mDecodeThread = nullptr;
    }

    // 3. 清空帧队列
    {
        std::lock_guard<std::mutex> lock(mFrameQueueMutex);
        while (!mFrameQueue.empty()) {
            AVFrame* frame = mFrameQueue.front();
            mFrameQueue.pop();
            if (frame) {
                av_free(frame->data[0]);
                av_frame_free(&frame);
            }
        }
    }

    // 4. 停止渲染线程
    if (mRenderThread) {
        LOGI("Waiting for render thread to finish...");
        if (mRenderThread->joinable()) {
            mRenderThread->join();
        }
        delete mRenderThread;
        mRenderThread = nullptr;
    }

    // 5. 清理OpenGL资源
    destroyGL();

    // 6. 清理EGL资源
    destroyEGL();

    LOGI("Playback stopped");
}

void RtspStreamPlayer::pausePlay()
{
    mIsPaused = true;
    LOGI("Playback paused");
}

void RtspStreamPlayer::resumePlay()
{
    mIsPaused = false;
    LOGI("Playback resumed");
}

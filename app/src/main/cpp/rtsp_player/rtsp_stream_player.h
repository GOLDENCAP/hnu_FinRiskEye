/**
 * RTSP Stream Player - C++层RTSP流媒体播放器实现
 *
 * 使用FFmpeg接收RTSP流并解码，通过OpenGL渲染到Surface
 */

#ifndef RTSP_STREAM_PLAYER_H
#define RTSP_STREAM_PLAYER_H

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <string>
#include <atomic>
#include <queue>
#include <mutex>
#include <thread>

extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavutil/imgutils.h"
#include "libavutil/time.h"
#include "libswscale/swscale.h"
}

class RtspStreamPlayer {
public:
    RtspStreamPlayer();
    ~RtspStreamPlayer();

    /**
     * 开始播放RTSP流
     * @param url RTSP流地址
     * @param window 用于渲染的Native Window
     * @return 0表示成功，其他值表示错误
     */
    int startPlay(const char* url, ANativeWindow* window);

    /**
     * 停止播放
     */
    void stopPlay();

    /**
     * 暂停播放
     */
    void pausePlay();

    /**
     * 继续播放
     */
    void resumePlay();

    /**
     * 是否正在播放
     */
    bool isPlaying() const { return mIsPlaying.load(); }

    /**
     * 获取错误信息
     */
    std::string getErrorMessage() const { return mErrorMessage; }

private:
    // 解码线程函数
    void decodeThreadFunc();

    // 渲染线程函数
    void renderThreadFunc();

    // 初始化EGL
    bool initEGL(ANativeWindow* window);

    // 销毁EGL
    void destroyEGL();

    // 初始化OpenGL
    bool initGL(int width, int height);

    // 销毁OpenGL
    void destroyGL();

    // 渲染帧
    void renderFrame(uint8_t* rgba, int width, int height);

    // YUV转RGBA
    void yuv420pToRgba(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                       uint8_t* rgba, int width, int height);

private:
    std::atomic<bool> mIsPlaying;
    std::atomic<bool> mIsPaused;
    std::atomic<bool> mShouldStop;

    std::string mRtspUrl;
    std::string mErrorMessage;

    // FFmpeg相关
    AVFormatContext* mFormatContext;
    AVCodecContext* mCodecContext;
    AVFrame* mFrame;
    AVFrame* mRgbaFrame;
    SwsContext* mSwsContext;
    AVPacket* mPacket;

    // 解码线程
    std::thread* mDecodeThread;

    // 渲染线程
    std::thread* mRenderThread;

    // 帧队列
    std::queue<AVFrame*> mFrameQueue;
    std::mutex mFrameQueueMutex;

    // EGL相关
    EGLDisplay mEglDisplay;
    EGLSurface mEglSurface;
    EGLContext mEglContext;

    // OpenGL相关
    GLuint mShaderProgram;
    GLuint mTextureId;
    GLuint mVboId;
    int mVideoWidth;
    int mVideoHeight;
    int mSurfaceWidth;
    int mSurfaceHeight;
};

#endif // RTSP_STREAM_PLAYER_H

/**
 * RTSP Player JNI - JNI接口层
 *
 * 负责Java层与C++层的交互
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include "rtsp_stream_player.h"
#include "rtsp_player_callback.h"

#define LOG_TAG "RtspPlayerJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局播放器实例
static RtspStreamPlayer* gPlayer = nullptr;

// Java回调相关全局变量
static JavaVM* gJavaVM = nullptr;
static jobject gJavaObject = nullptr;
static jmethodID gVideoSizeMethodId = nullptr;

/**
 * 初始化视频尺寸回调
 */
void initVideoSizeCallback(JavaVM* vm, jobject obj, jmethodID methodId) {
    gJavaVM = vm;
    gJavaObject = obj;
    gVideoSizeMethodId = methodId;
}

/**
 * 通知Java层视频尺寸变化
 */
void notifyVideoSizeChanged(int width, int height) {
    if (gJavaVM && gJavaObject && gVideoSizeMethodId) {
        JNIEnv* env;
        bool attached = false;

        if (gJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) < 0) {
            if (gJavaVM->AttachCurrentThread(&env, nullptr) < 0) {
                LOGE("Failed to attach thread to JavaVM");
                return;
            }
            attached = true;
        }

        env->CallVoidMethod(gJavaObject, gVideoSizeMethodId, width, height);
        LOGI("Notified Java: video size changed to %dx%d", width, height);

        if (attached) {
            gJavaVM->DetachCurrentThread();
        }
    }
}

/**
 * JNI方法：playRtspStream
 * Java调用方式：RtspPlayerNative.playRtspStream(String url, Surface surface)
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_chapter07_rtsp_1player_RtspPlayerNative_playRtspStream(
        JNIEnv* env,
        jobject thiz,
        jstring url,
        jobject surface) {

    // 释放之前的播放器
    if (gPlayer) {
        delete gPlayer;
        gPlayer = nullptr;
    }

    // 保存Java对象引用，用于回调
    if (gJavaObject) {
        env->DeleteGlobalRef(gJavaObject);
    }
    gJavaObject = env->NewGlobalRef(thiz);

    // 获取JavaVM实例
    env->GetJavaVM(&gJavaVM);

    // 获取回调方法ID
    jclass clazz = env->GetObjectClass(thiz);
    gVideoSizeMethodId = env->GetMethodID(clazz, "notifyVideoSizeChanged", "(II)V");

    // 初始化回调系统
    initVideoSizeCallback(gJavaVM, gJavaObject, gVideoSizeMethodId);

    // 创建新播放器
    gPlayer = new RtspStreamPlayer();

    // 转换为C字符串
    const char* urlStr = env->GetStringUTFChars(url, nullptr);
    if (!urlStr) {
        LOGE("Failed to get URL string");
        return -1;
    }

    // 获取Surface的Native Window
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("Failed to get native window");
        env->ReleaseStringUTFChars(url, urlStr);
        delete gPlayer;
        gPlayer = nullptr;
        return -2;
    }

    LOGI("Starting playback: %s", urlStr);

    // 开始播放（非阻塞）
    int result = gPlayer->startPlay(urlStr, window);

    // 释放资源
    env->ReleaseStringUTFChars(url, urlStr);

    // 如果启动失败，清理资源
    if (result != 0) {
        LOGE("Playback start failed with error: %d, message: %s", result, gPlayer->getErrorMessage().c_str());
        // 停止播放并清理
        gPlayer->stopPlay();
        delete gPlayer;
        gPlayer = nullptr;
        return result;
    }

    LOGI("Playback started successfully, returning control to Java");
    return 0;
}

/**
 * JNI方法：stopPlayback
 * Java调用方式：RtspPlayerNative.stopPlayback()
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_chapter07_rtsp_1player_RtspPlayerNative_stopPlayback(
        JNIEnv* /* env */,
        jobject /* this */) {

    LOGI("Stopping playback");
    if (gPlayer) {
        gPlayer->stopPlay();
        delete gPlayer;
        gPlayer = nullptr;
    }

    if (gJavaObject) {
        gJavaObject = nullptr;
    }
}

/**
 * JNI方法：pausePlayback
 * Java调用方式：RtspPlayerNative.pausePlayback()
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_chapter07_rtsp_1player_RtspPlayerNative_pausePlayback(
        JNIEnv* /* env */,
        jobject /* this */) {

    LOGI("Pausing playback");
    if (gPlayer) {
        gPlayer->pausePlay();
    }
}

/**
 * JNI方法：resumePlayback
 * Java调用方式：RtspPlayerNative.resumePlayback()
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_chapter07_rtsp_1player_RtspPlayerNative_resumePlayback(
        JNIEnv* /* env */,
        jobject /* this */) {

    LOGI("Resuming playback");
    if (gPlayer) {
        gPlayer->resumePlay();
    }
}

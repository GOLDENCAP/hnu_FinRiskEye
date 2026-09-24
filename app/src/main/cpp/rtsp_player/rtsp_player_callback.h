/**
 * RTSP Player Callback Header
 *
 * 定义JNI回调接口
 */

#ifndef RTSP_PLAYER_CALLBACK_H
#define RTSP_PLAYER_CALLBACK_H

#include <jni.h>

/**
 * 初始化回调系统
 * 需要在播放开始前调用
 */
extern void initVideoSizeCallback(JavaVM* vm, jobject obj, jmethodID methodId);

/**
 * 通知视频尺寸变化
 * 在解码线程中调用
 */
extern void notifyVideoSizeChanged(int width, int height);

#endif // RTSP_PLAYER_CALLBACK_H

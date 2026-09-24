package com.example.chapter07.rtsp_player;

import android.view.Surface;

/**
 * RTSP Player Native - JNI接口封装类
 *
 * 负责调用C++层的FFmpeg RTSP播放功能
 */
public class RtspPlayerNative {

    /**
     * 视频尺寸变化回调接口
     */
    public interface OnVideoSizeListener {
        /**
         * 当获取到视频尺寸时调用
         * @param width 视频宽度
         * @param height 视频高度
         */
        void onVideoSizeChanged(int width, int height);
    }

    private OnVideoSizeListener videoSizeListener;

    static {
        System.loadLibrary("chapter07");
    }

    /**
     * 设置视频尺寸监听器
     */
    public void setOnVideoSizeListener(OnVideoSizeListener listener) {
        this.videoSizeListener = listener;
    }

    /**
     * 内部方法，供Native代码调用
     */
    public void notifyVideoSizeChanged(int width, int height) {
        if (videoSizeListener != null) {
            videoSizeListener.onVideoSizeChanged(width, height);
        }
    }

    /**
     * 播放RTSP视频流
     *
     * @param rtspUrl RTSP流地址，如 rtsp://180.76.247.74:8554/stream1
     * @param surface 用于渲染的Android Surface
     * @return 0表示正常结束，其他值表示错误
     */
    public native int playRtspStream(String rtspUrl, Surface surface);

    /**
     * 停止RTSP视频播放
     */
    public native void stopPlayback();

    /**
     * 暂停播放（如果支持）
     */
    public native void pausePlayback();

    /**
     * 继续播放（如果支持）
     */
    public native void resumePlayback();
}

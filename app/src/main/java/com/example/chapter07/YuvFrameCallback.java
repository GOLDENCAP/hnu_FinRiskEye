package com.example.chapter07;

public interface YuvFrameCallback {
    /**
     * 每帧YUV数据回调，支持修改
     * @param y Y平面数据（可修改）
     * @param u U平面数据（可修改）
     * @param v V平面数据（可修改）
     * @param width 宽度
     * @param height 高度
     * @param yStride Y平面步长
     * @param uvStride UV平面步长
     * @return true表示数据已修改，需要重新渲染；false表示未修改
     */
    boolean onYuvFrame(byte[] y, byte[] u, byte[] v, int width, int height, int yStride, int uvStride);
}
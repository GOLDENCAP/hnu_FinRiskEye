package com.example.chapter07.thirdlevel;

/**
 * 判断采集到的屏幕画面是否为“近乎全黑”或“近乎纯色/空白”。
 *
 * <p>背景：当目标应用使用 FLAG_SECURE、DRM 或厂商录屏限制时，MediaProjection
 * 只能采集到黑屏。黑屏继续送视觉模型分析通常得到 none，会误报“无风险”，
 * 因此需要在发送风险请求前先做本地画面质量判断。</p>
 *
 * <p>该类是纯 Java 实现，不依赖 Android Bitmap：调用方先把画面降采样，
 * 再用 {@code Bitmap.getPixels(...)} 取出 ARGB 数组，最后调用
 * {@link #detect(int[], int, int)}。这样阈值逻辑可以在没有真机的环境中
 * 用 JVM 单元测试验证。</p>
 *
 * <p>阈值刻意保守：只有“平均亮度极低且亮度几乎没有波动”才判黑屏，
 * 避免把深色模式、深色视频画面等正常深色界面误判为采集受限而停止风险分析。
 * 深色但不全黑的均匀界面（平均亮度 12 以上）仍会继续分析。</p>
 */
public final class FrameQualityDetector {

    /** 画面质量判定结果。 */
    public enum Quality {
        /** 画面正常，可以继续做 OCR 和风险分析。 */
        OK,
        /** 近乎全黑，通常是受保护画面或采集失败。 */
        BLACK,
        /** 近乎纯色或空白，没有可分析内容。 */
        BLANK
    }

    /** 建议的采样边长：64×64 足以统计亮度分布，对 2 秒一次的采样开销很小。 */
    public static final int SAMPLE_SIZE = 64;

    /** 判定黑屏的平均亮度上限（0-255，含该值视为全黑）。 */
    public static final double BLACK_MAX_AVERAGE_LUMA = 12.0;

    /** 判定黑屏的亮度标准差上限：超过该值说明画面仍有内容波动。 */
    public static final double BLACK_MAX_STDDEV = 3.0;

    /** 判定“近白空白”的平均亮度下限。 */
    public static final double BLANK_MIN_AVERAGE_LUMA = 230.0;

    /** 判定“近白空白”的亮度标准差上限。 */
    public static final double BLANK_MAX_STDDEV = 2.0;

    /** 判定“亮色纯色占位”的平均亮度下限。 */
    public static final double SOLID_COLOR_MIN_AVERAGE_LUMA = 180.0;

    /** 判定“亮色纯色占位”的亮度标准差上限。 */
    public static final double SOLID_COLOR_MAX_STDDEV = 1.0;

    /**
     * 根据 ARGB 像素判断画面质量。
     *
     * @param pixels ARGB_8888 像素数组，通常由降采样后的 Bitmap.getPixels 得到
     * @param width  采样宽度
     * @param height 采样高度
     * @return OK/BLACK/BLANK；参数无效时返回 OK，宁可继续分析也不误报采集受限
     */
    public Quality detect(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height) {
            return Quality.OK;
        }
        int count = width * height;
        double sum = 0.0;
        double squareSum = 0.0;
        for (int i = 0; i < count; i++) {
            double luma = luminance(pixels[i]);
            sum += luma;
            squareSum += luma * luma;
        }
        double average = sum / count;
        double variance = Math.max(0.0, squareSum / count - average * average);
        return classify(average, Math.sqrt(variance));
    }

    /**
     * 把亮度统计量映射为判定结果；包内可见，便于单元测试直接验证阈值边界。
     *
     * @param averageLuma        平均亮度（0-255）
     * @param standardDeviation  亮度标准差
     */
    static Quality classify(double averageLuma, double standardDeviation) {
        if (averageLuma < BLACK_MAX_AVERAGE_LUMA
                && standardDeviation <= BLACK_MAX_STDDEV) {
            return Quality.BLACK;
        }
        boolean nearWhite = averageLuma >= BLANK_MIN_AVERAGE_LUMA
                && standardDeviation <= BLANK_MAX_STDDEV;
        boolean solidBrightColor = averageLuma >= SOLID_COLOR_MIN_AVERAGE_LUMA
                && standardDeviation <= SOLID_COLOR_MAX_STDDEV;
        if (nearWhite || solidBrightColor) {
            return Quality.BLANK;
        }
        return Quality.OK;
    }

    /** 按人眼敏感度把 ARGB 颜色转换为亮度（0-255）。 */
    static double luminance(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000.0;
    }
}

package com.example.chapter07.thirdlevel;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * FrameQualityDetector 的纯 JVM 单元测试。
 *
 * <p>输入是手工构造的 ARGB 像素数组，不依赖 Android 设备或 Bitmap，
 * 因此可以在没有真机/模拟器的环境下验证黑屏、空白和正常画面的判定阈值。</p>
 */
public class FrameQualityDetectorTest {

    private static final int SIDE = 8;

    private static int[] uniformFrame(int color) {
        int[] pixels = new int[SIDE * SIDE];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = color;
        }
        return pixels;
    }

    @Test
    public void pureBlackFrameIsBlack() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(
                FrameQualityDetector.Quality.BLACK,
                detector.detect(uniformFrame(0xff000000), SIDE, SIDE)
        );
    }

    @Test
    public void nearBlackUniformFrameIsBlack() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(
                FrameQualityDetector.Quality.BLACK,
                detector.detect(uniformFrame(0xff050505), SIDE, SIDE)
        );
    }

    @Test
    public void whiteBlankFrameIsBlank() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(
                FrameQualityDetector.Quality.BLANK,
                detector.detect(uniformFrame(0xffffffff), SIDE, SIDE)
        );
    }

    @Test
    public void lightGrayBlankFrameIsBlank() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(
                FrameQualityDetector.Quality.BLANK,
                detector.detect(uniformFrame(0xffdddddd), SIDE, SIDE)
        );
    }

    @Test
    public void brightSolidColorFrameIsBlank() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(
                FrameQualityDetector.Quality.BLANK,
                detector.detect(uniformFrame(0xff90caf9), SIDE, SIDE)
        );
    }

    @Test
    public void normalContentFrameIsOk() {
        FrameQualityDetector detector = new FrameQualityDetector();
        int[] pixels = new int[SIDE * SIDE];
        for (int i = 0; i < pixels.length; i++) {
            int x = i % SIDE;
            int y = i / SIDE;
            pixels[i] = ((x + y) % 2 == 0) ? 0xff000000 : 0xffffffff;
        }
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(pixels, SIDE, SIDE)
        );
    }

    @Test
    public void darkModeScreenWithTextIsNotLimited() {
        FrameQualityDetector detector = new FrameQualityDetector();
        int[] pixels = uniformFrame(0xff121212);
        for (int i = 0; i < pixels.length; i += 7) {
            pixels[i] = 0xffe8eaed;
        }
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(pixels, SIDE, SIDE)
        );
    }

    @Test
    public void uniformDarkBackgroundIsNotFlaggedAsBlack() {
        FrameQualityDetector detector = new FrameQualityDetector();
        // 深色模式的纯背景亮度约 18，高于黑屏阈值：宁可继续分析，也不误停风险检测。
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(uniformFrame(0xff121212), SIDE, SIDE)
        );
    }

    @Test
    public void noisyDarkVideoFrameIsNotFlaggedAsBlack() {
        FrameQualityDetector detector = new FrameQualityDetector();
        int[] pixels = new int[SIDE * SIDE];
        for (int i = 0; i < pixels.length; i++) {
            int value = (i * 13) % 41;
            pixels[i] = 0xff000000 | (value << 16) | (value << 8) | value;
        }
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(pixels, SIDE, SIDE)
        );
    }

    @Test
    public void invalidPixelsAreTreatedAsOk() {
        FrameQualityDetector detector = new FrameQualityDetector();
        assertEquals(FrameQualityDetector.Quality.OK, detector.detect(null, SIDE, SIDE));
        assertEquals(FrameQualityDetector.Quality.OK, detector.detect(new int[0], SIDE, SIDE));
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(uniformFrame(0xff000000), 0, SIDE)
        );
        assertEquals(
                FrameQualityDetector.Quality.OK,
                detector.detect(new int[4], SIDE, SIDE)
        );
    }

    @Test
    public void classificationBoundariesAreConservative() {
        // 黑屏边界：平均亮度极低且几乎没有波动才算黑屏。
        assertEquals(
                FrameQualityDetector.Quality.BLACK,
                FrameQualityDetector.classify(
                        FrameQualityDetector.BLACK_MAX_AVERAGE_LUMA - 0.1,
                        FrameQualityDetector.BLACK_MAX_STDDEV - 0.1
                )
        );
        assertEquals(
                FrameQualityDetector.Quality.OK,
                FrameQualityDetector.classify(FrameQualityDetector.BLACK_MAX_AVERAGE_LUMA, 0.0)
        );
        assertEquals(
                FrameQualityDetector.Quality.OK,
                FrameQualityDetector.classify(0.0, FrameQualityDetector.BLACK_MAX_STDDEV + 0.1)
        );
        // 空白边界：近白空白和亮色纯色都会被判为“无可分析内容”。
        assertEquals(
                FrameQualityDetector.Quality.BLANK,
                FrameQualityDetector.classify(
                        FrameQualityDetector.BLANK_MIN_AVERAGE_LUMA,
                        FrameQualityDetector.BLANK_MAX_STDDEV
                )
        );
        assertEquals(
                FrameQualityDetector.Quality.BLANK,
                FrameQualityDetector.classify(
                        FrameQualityDetector.SOLID_COLOR_MIN_AVERAGE_LUMA,
                        FrameQualityDetector.SOLID_COLOR_MAX_STDDEV
                )
        );
        assertEquals(
                FrameQualityDetector.Quality.OK,
                FrameQualityDetector.classify(
                        FrameQualityDetector.SOLID_COLOR_MIN_AVERAGE_LUMA - 0.1,
                        FrameQualityDetector.SOLID_COLOR_MAX_STDDEV + 0.5
                )
        );
    }
}

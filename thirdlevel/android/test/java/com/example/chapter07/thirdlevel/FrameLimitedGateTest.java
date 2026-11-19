package com.example.chapter07.thirdlevel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * FrameLimitedGate 的纯 JVM 单元测试。
 *
 * <p>覆盖 12/8 验收中的黑屏/空白迟滞场景：单帧瞬态黑屏不进入“检测受限”，
 * 连续受限帧才进入，1 帧正常画面立即退出，未知质量不误报受限。</p>
 */
public class FrameLimitedGateTest {

    private static final FrameQualityDetector.Quality OK = FrameQualityDetector.Quality.OK;
    private static final FrameQualityDetector.Quality BLACK = FrameQualityDetector.Quality.BLACK;
    private static final FrameQualityDetector.Quality BLANK = FrameQualityDetector.Quality.BLANK;

    @Test
    public void singleBlackFrameDoesNotEnterLimited() {
        FrameLimitedGate gate = new FrameLimitedGate();
        assertFalse(gate.onFrame(BLACK));
        assertFalse(gate.isLimited());
        assertTrue(gate.isPendingLimited());
        assertEquals(1, gate.getConsecutiveLimitedFrames());
    }

    @Test
    public void twoConsecutiveBlackFramesEnterLimited() {
        FrameLimitedGate gate = new FrameLimitedGate();
        assertFalse(gate.onFrame(BLACK));
        assertTrue(gate.onFrame(BLACK));
        assertTrue(gate.isLimited());
        assertEquals(2, gate.getConsecutiveLimitedFrames());
    }

    @Test
    public void twoConsecutiveBlankFramesEnterLimited() {
        FrameLimitedGate gate = new FrameLimitedGate();
        assertFalse(gate.onFrame(BLANK));
        assertTrue(gate.onFrame(BLANK));
        assertTrue(gate.isLimited());
    }

    @Test
    public void mixedBlackAndBlankFramesCountAsConsecutiveLimited() {
        FrameLimitedGate gate = new FrameLimitedGate();
        assertFalse(gate.onFrame(BLACK));
        // 黑屏和空白都属于“检测受限”，连续出现时同样进入受限。
        assertTrue(gate.onFrame(BLANK));
        assertTrue(gate.isLimited());
    }

    @Test
    public void limitedStateStaysWhileLimitedFramesContinue() {
        FrameLimitedGate gate = new FrameLimitedGate();
        gate.onFrame(BLACK);
        gate.onFrame(BLACK);
        for (int i = 0; i < 5; i++) {
            assertTrue(gate.onFrame(BLACK));
        }
        assertTrue(gate.isLimited());
        // 连续受限帧计数封顶在阈值，便于日志排查时不会无限增长。
        assertEquals(FrameLimitedGate.ENTER_LIMITED_FRAMES, gate.getConsecutiveLimitedFrames());
    }

    @Test
    public void singleOkFrameExitsLimitedImmediately() {
        FrameLimitedGate gate = new FrameLimitedGate();
        gate.onFrame(BLACK);
        gate.onFrame(BLACK);
        assertTrue(gate.isLimited());
        assertFalse(gate.onFrame(OK));
        assertFalse(gate.isLimited());
        assertFalse(gate.isPendingLimited());
        assertEquals(0, gate.getConsecutiveLimitedFrames());
    }

    @Test
    public void transientBlackBetweenNormalFramesNeverLimits() {
        FrameLimitedGate gate = new FrameLimitedGate();
        assertFalse(gate.onFrame(OK));
        assertFalse(gate.onFrame(BLACK));
        assertFalse(gate.onFrame(OK));
        assertFalse(gate.onFrame(BLACK));
        assertFalse(gate.onFrame(OK));
        assertFalse(gate.isLimited());
    }

    @Test
    public void unknownOrNullQualityIsTreatedAsNormal() {
        FrameLimitedGate gate = new FrameLimitedGate();
        gate.onFrame(BLACK);
        // 未知质量（检测器自身失败）按正常画面处理：清空待定计数，不误报受限。
        assertFalse(gate.onFrame(null));
        assertFalse(gate.isLimited());
        assertEquals(0, gate.getConsecutiveLimitedFrames());
        // 未知质量之后仍需重新累积连续帧才会进入受限。
        assertFalse(gate.onFrame(BLACK));
        assertTrue(gate.onFrame(BLACK));
    }

    @Test
    public void resetClearsPendingAndLimitedState() {
        FrameLimitedGate gate = new FrameLimitedGate();
        gate.onFrame(BLACK);
        assertTrue(gate.isPendingLimited());
        gate.reset();
        assertFalse(gate.isPendingLimited());
        assertEquals(0, gate.getConsecutiveLimitedFrames());

        gate.onFrame(BLANK);
        gate.onFrame(BLANK);
        assertTrue(gate.isLimited());
        gate.reset();
        assertFalse(gate.isLimited());
        assertFalse(gate.isPendingLimited());
    }

    @Test
    public void pendingLimitedFrameIsNotReportedAsLimited() {
        FrameLimitedGate gate = new FrameLimitedGate();
        // 第一帧受限只丢弃该帧，不改变对外“检测受限”状态。
        assertFalse(gate.onFrame(BLANK));
        assertTrue(gate.isPendingLimited());
        assertFalse(gate.isLimited());
    }

    @Test
    public void consecutiveCountIsCappedAtEnterThreshold() {
        FrameLimitedGate gate = new FrameLimitedGate();
        gate.onFrame(BLACK);
        gate.onFrame(BLACK);
        gate.onFrame(BLACK);
        gate.onFrame(BLACK);
        assertEquals(FrameLimitedGate.ENTER_LIMITED_FRAMES, gate.getConsecutiveLimitedFrames());
    }
}

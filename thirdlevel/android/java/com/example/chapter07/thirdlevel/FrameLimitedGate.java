package com.example.chapter07.thirdlevel;

/**
 * 黑屏/空白画面的迟滞门控：单帧瞬态黑帧不立即进入“检测受限”。
 *
 * <p>背景：应用切换、横竖屏旋转、加载动画等场景会出现单帧全黑或纯色画面。
 * 如果每帧都按受限处理，悬浮窗会在“检测受限”和“检测中/无风险”之间反复跳变，
 * 影响 12 月 8 日验收的演示体验。因此引入迟滞：</p>
 *
 * <ul>
 *   <li>连续 {@link #ENTER_LIMITED_FRAMES} 帧受限画面（BLACK 或 BLANK）才进入受限；</li>
 *   <li>只要出现 1 帧正常画面立即退出受限；</li>
 *   <li>未知/空的质量（检测器自身失败）按正常画面处理，宁可继续分析也不误报受限。</li>
 * </ul>
 *
 * <p>该类是纯 Java 实现、不依赖 Android，可在无真机环境用 JVM 单元测试验证；
 * 阈值常量集中定义，便于真机调优。</p>
 */
public final class FrameLimitedGate {

    /** 连续多少帧受限画面才进入“检测受限”。设为 2 可过滤单帧瞬态黑屏，又保持 2 秒采样下的响应速度。 */
    public static final int ENTER_LIMITED_FRAMES = 2;

    private int consecutiveLimitedFrames;
    private boolean limited;

    /**
     * 输入一帧画面质量，返回“当前是否应处于检测受限状态”。
     *
     * @param quality 画面质量；{@code null} 视为未知，按正常画面处理
     * @return true 表示应显示“检测受限”；false 表示应继续正常分析
     */
    public boolean onFrame(FrameQualityDetector.Quality quality) {
        boolean limitedFrame = quality == FrameQualityDetector.Quality.BLACK
                || quality == FrameQualityDetector.Quality.BLANK;
        if (!limitedFrame) {
            // 任意一帧正常画面立即退出门控，避免受限状态残留。
            consecutiveLimitedFrames = 0;
            limited = false;
            return false;
        }
        if (consecutiveLimitedFrames < ENTER_LIMITED_FRAMES) {
            consecutiveLimitedFrames++;
        }
        if (consecutiveLimitedFrames >= ENTER_LIMITED_FRAMES) {
            limited = true;
        }
        return limited;
    }

    /** 当前是否已进入“检测受限”。 */
    public boolean isLimited() {
        return limited;
    }

    /** 是否处于“疑似受限、但迟滞未满”的待定期：该帧应丢弃但不改变对外状态。 */
    public boolean isPendingLimited() {
        return !limited && consecutiveLimitedFrames > 0;
    }

    /** 已连续出现的受限帧数，用于日志排查。 */
    public int getConsecutiveLimitedFrames() {
        return consecutiveLimitedFrames;
    }

    /** 重置为初始状态（新会话/画面恢复时调用）。 */
    public void reset() {
        consecutiveLimitedFrames = 0;
        limited = false;
    }
}

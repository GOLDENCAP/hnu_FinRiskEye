package com.example.chapter07.thirdlevel;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * AnalysisStatsFormatter 的纯 JVM 单元测试。
 *
 * <p>只验证“毫秒数 → 中文统计文案”的纯函数逻辑，与 Android SystemClock 解耦；
 * Android 侧只需传入 {@code SystemClock.elapsedRealtime()} 算出的间隔毫秒数。</p>
 */
public class AnalysisStatsFormatterTest {

    @Test
    public void formatLastAnalysisJustNow() {
        assertEquals("刚刚", AnalysisStatsFormatter.formatLastAnalysis(0L));
        assertEquals("刚刚", AnalysisStatsFormatter.formatLastAnalysis(1L));
        assertEquals("刚刚", AnalysisStatsFormatter.formatLastAnalysis(999L));
    }

    @Test
    public void formatLastAnalysisSeconds() {
        assertEquals("1s 前", AnalysisStatsFormatter.formatLastAnalysis(1_000L));
        assertEquals("12s 前", AnalysisStatsFormatter.formatLastAnalysis(12_500L));
        assertEquals("59s 前", AnalysisStatsFormatter.formatLastAnalysis(59_999L));
    }

    @Test
    public void formatLastAnalysisMinutes() {
        assertEquals("1 分钟前", AnalysisStatsFormatter.formatLastAnalysis(60_000L));
        assertEquals("3 分钟前", AnalysisStatsFormatter.formatLastAnalysis(180_500L));
        assertEquals("59 分钟前", AnalysisStatsFormatter.formatLastAnalysis(3_599_000L));
    }

    @Test
    public void formatLastAnalysisHours() {
        assertEquals("1 小时前", AnalysisStatsFormatter.formatLastAnalysis(3_600_000L));
        assertEquals("2 小时前", AnalysisStatsFormatter.formatLastAnalysis(7_200_000L));
    }

    @Test
    public void formatLastAnalysisUnknownForNegativeInput() {
        assertEquals("未知", AnalysisStatsFormatter.formatLastAnalysis(-1L));
    }

    @Test
    public void formatLatencyMilliseconds() {
        assertEquals("耗时 0ms", AnalysisStatsFormatter.formatLatency(0));
        assertEquals("耗时 850ms", AnalysisStatsFormatter.formatLatency(850));
        assertEquals("耗时 999ms", AnalysisStatsFormatter.formatLatency(999));
    }

    @Test
    public void formatLatencySecondsWithOneDecimal() {
        assertEquals("耗时 1.0s", AnalysisStatsFormatter.formatLatency(1_000));
        assertEquals("耗时 2.3s", AnalysisStatsFormatter.formatLatency(2_340));
        assertEquals("耗时 12.3s", AnalysisStatsFormatter.formatLatency(12_340));
    }

    @Test
    public void formatLatencyUnknownForNegativeInput() {
        assertEquals("耗时未知", AnalysisStatsFormatter.formatLatency(-1));
    }

    @Test
    public void formatSessionCounts() {
        assertEquals("本次会话：分析 0 次，风险 0 次", AnalysisStatsFormatter.formatSession(0, 0));
        assertEquals("本次会话：分析 5 次，风险 2 次", AnalysisStatsFormatter.formatSession(5, 2));
    }

    @Test
    public void formatSessionClampsNegativeCounts() {
        assertEquals("本次会话：分析 0 次，风险 0 次", AnalysisStatsFormatter.formatSession(-3, -1));
    }

    @Test
    public void formatLineMatchesAcceptanceExample() {
        // 12/8 验收样例：最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次
        assertEquals(
                "最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次",
                AnalysisStatsFormatter.formatLine(12_500L, 2_340, 5, 2)
        );
    }

    @Test
    public void formatLineWithoutAnalysisShowsNone() {
        assertEquals(
                "最近分析：暂无｜本次会话：分析 0 次，风险 0 次",
                AnalysisStatsFormatter.formatLine(-1L, -1, 0, 0)
        );
    }

    @Test
    public void formatLineWithUnknownLatency() {
        assertEquals(
                "最近分析：刚刚，耗时未知｜本次会话：分析 3 次，风险 1 次",
                AnalysisStatsFormatter.formatLine(500L, -1, 3, 1)
        );
    }
}

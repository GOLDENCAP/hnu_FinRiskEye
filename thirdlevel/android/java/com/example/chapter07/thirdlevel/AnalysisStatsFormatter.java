package com.example.chapter07.thirdlevel;

import java.util.Locale;

/**
 * “最近分析耗时 + 本次会话统计”的文本格式化工具。
 *
 * <p>背景：12 月 8 日验收要求“记录延迟”，悬浮窗/通知里需要能直接看到
 * 最近一次分析的耗时与本次会话的分析次数、风险次数。</p>
 *
 * <p>该类是纯 Java 实现、只接收已经算好的毫秒数，不依赖 Android 与
 * {@code SystemClock}：Android 侧用 {@code SystemClock.elapsedRealtime()}
 * 计算“距上次分析多少毫秒”，这里只负责把数字格式化成稳定的中文文案，
 * 因此可以在没有真机/模拟器的环境下用 JVM 单元测试直接验证输入输出。</p>
 */
public final class AnalysisStatsFormatter {

    /** 距上次分析小于该值（毫秒）时显示“刚刚”，避免出现“0s 前”。 */
    public static final long JUST_NOW_MAX_MS = 1_000L;

    /** 一小时对应的毫秒数，用于选择“秒/分钟/小时”展示粒度。 */
    public static final long HOUR_MS = 3_600_000L;

    private static final String BAR = "｜";

    private AnalysisStatsFormatter() {
    }

    /**
     * 格式化“最近分析”的相对时间。
     *
     * @param elapsedMs 距最近一次分析的毫秒数；负数表示未知
     * @return 例如“刚刚”“12s 前”“3 分钟前”“2 小时前”“未知”
     */
    public static String formatLastAnalysis(long elapsedMs) {
        if (elapsedMs < 0) {
            return "未知";
        }
        if (elapsedMs < JUST_NOW_MAX_MS) {
            return "刚刚";
        }
        long seconds = elapsedMs / 1_000L;
        if (seconds < 60L) {
            return seconds + "s 前";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟前";
        }
        return (minutes / 60L) + " 小时前";
    }

    /**
     * 格式化单次请求耗时。
     *
     * @param latencyMs 耗时毫秒数；负数表示未记录
     * @return 例如“耗时 850ms”“耗时 2.3s”“耗时未知”
     */
    public static String formatLatency(int latencyMs) {
        if (latencyMs < 0) {
            return "耗时未知";
        }
        if (latencyMs < 1_000) {
            return "耗时 " + latencyMs + "ms";
        }
        return String.format(Locale.US, "耗时 %.1fs", latencyMs / 1_000.0);
    }

    /**
     * 格式化本次会话统计。
     *
     * @param analysisCount 本次会话已完成的分析次数（失败也计入）
     * @param riskCount     本次会话发现风险的次数
     * @return 例如“本次会话：分析 5 次，风险 2 次”
     */
    public static String formatSession(int analysisCount, int riskCount) {
        int safeAnalysisCount = Math.max(0, analysisCount);
        int safeRiskCount = Math.max(0, riskCount);
        return "本次会话：分析 " + safeAnalysisCount + " 次，风险 " + safeRiskCount + " 次";
    }

    /**
     * 生成悬浮窗/通知详情里的统计行（始终包含会话统计；尚无分析时显示“暂无”）。
     *
     * @param elapsedSinceLastAnalysisMs 距最近一次分析的毫秒数；负数表示未知/尚无分析
     * @param latencyMs                  最近一次分析的耗时毫秒数；负数表示未记录
     * @param analysisCount              本次会话已完成的分析次数
     * @param riskCount                  本次会话发现风险的次数
     * @return 例如“最近分析：12s 前，耗时 2.3s｜本次会话：分析 5 次，风险 2 次”
     */
    public static String formatLine(long elapsedSinceLastAnalysisMs, int latencyMs,
                                    int analysisCount, int riskCount) {
        String session = formatSession(analysisCount, riskCount);
        if (analysisCount <= 0) {
            return "最近分析：暂无" + BAR + session;
        }
        return "最近分析：" + formatLastAnalysis(elapsedSinceLastAnalysisMs)
                + "，" + formatLatency(latencyMs)
                + BAR + session;
    }
}

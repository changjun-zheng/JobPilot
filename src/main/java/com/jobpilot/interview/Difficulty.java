package com.jobpilot.interview;

import java.util.Arrays;
import java.util.Locale;

/**
 * 面试难度（BRD US-3）。
 * <p>
 * 封闭枚举：由公司档位预设（{@code InterviewProperties.tiers}）给出，用户可覆盖。
 * 未知值显式拒绝，不静默降级——与 {@code ApplicationStatus} / {@code MemoryType} 同一约定。
 */
public enum Difficulty {

    EASY,
    MEDIUM,
    HARD;

    public static Difficulty parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("难度不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知难度：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }

    /**
     * 难度高低序号（{@code EASY < MEDIUM < HARD}）。
     * <p>
     * 声明顺序即高低顺序——「多家公司混成一套题」时取其中<b>最高</b>的档位（{@code max(rank)}）。
     */
    public int rank() {
        return ordinal();
    }
}

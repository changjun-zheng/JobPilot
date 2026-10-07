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
}

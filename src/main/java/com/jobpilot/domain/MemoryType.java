package com.jobpilot.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 记忆类型（PRD-FP-4 的「允许内容」）。
 * <p>
 * <b>封闭枚举，不是自由文本</b>：自由文本无法过滤与统计，且会静默接受模型自造的分类。
 * 与 {@link ApplicationStatus} 同一写法——实体里存 {@code String}，枚举做边界校验。
 */
public enum MemoryType {
    /** 求职偏好 */
    JOB_PREFERENCE,
    /** 面试弱点 */
    INTERVIEW_WEAKNESS,
    /** 已确认的准备计划 */
    PREPARATION_PLAN,
    /** 表达问题 */
    EXPRESSION_ISSUE;

    /** 解析并校验；未知值抛 {@link IllegalArgumentException}，消息里列出允许值 */
    public static MemoryType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知记忆类型：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }
}

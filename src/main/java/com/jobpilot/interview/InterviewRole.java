package com.jobpilot.interview;

import java.util.Arrays;
import java.util.Locale;

/**
 * 面试消息角色。
 * <p>
 * 刻意区别于聊天的 {@code MessageRole}（USER/ASSISTANT）——面试是面试官与候选人的对答，
 * 两者语义不同，分成两个枚举而不是复用。
 */
public enum InterviewRole {

    /** 面试官提问 */
    INTERVIEWER,

    /** 候选人作答 */
    CANDIDATE;

    public static InterviewRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知面试消息角色：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }
}

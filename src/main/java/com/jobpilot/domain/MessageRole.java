package com.jobpilot.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 会话消息角色（PRD-FP-2.1）。
 * <p>
 * 只含本实现真正持久化的两种：{@code USER} 与 {@code ASSISTANT}。PRD 还提到「必要时 {@code TOOL}」，
 * 但工具调用的细节存在 {@code agent_trace_step}，本表不重复——因此这里**没有** TOOL。
 * <p>
 * <b>这是边界类型，不是持久化类型</b>：{@link ConversationMessageEntity#getRole()} 仍是 {@code String}，
 * 与项目既有实体一致；枚举负责写入校验与读取映射，未知值显式拒绝。
 */
public enum MessageRole {

    /** 用户输入 */
    USER,

    /** 助手回复（最终答案；含 HITL / 预算耗尽时的提示语） */
    ASSISTANT;

    /**
     * 解析并校验角色；未知值抛 {@link IllegalArgumentException}，消息里列出允许值。
     * <p>
     * 读取库里存过的角色值时才走这里——正常写入只产生上面的常量。未知值即数据被外部改过，
     * 早失败胜过静默按某个默认角色渲染。
     */
    public static MessageRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知消息角色：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }
}

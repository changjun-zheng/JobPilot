package com.jobpilot.usage;

/**
 * 计量维度（PRD-FP-10 的计量维度表；存储维度不入事件行，由汇总接口现查）。
 * <p>
 * 封闭集合：新增维度必须显式改这里并补齐汇总口径，不允许字符串散落各处拼出来的「第 N 个维度」。
 */
public enum UsageDimension {

    /** Embedding 调用量：调用次数 + 字符数（码点） */
    EMBEDDING,

    /** LLM token 消耗：输入 / 输出分开，供应商未返回为 NULL */
    LLM_TOKEN,

    /** Agent 调用次数：按 run 统计，含迭代数与工具调用次数 */
    AGENT_RUN
}

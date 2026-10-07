package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 配置（jobpilot.agent.*，I-2）。
 * <p>
 * 默认值全部落在 application.yml；record 只保留规范构造器——加第二个构造器会让 Boot 的绑定
 * 直接报「No default constructor found」（实测踩过，见 2026-10-02 交接）。
 * <p>
 * 预算与超时对应 ARCHITECTURE.md §6 的 runner 规则。
 *
 * @param providerPath provider id：{@code ollama}（本地，默认）/ {@code openai}（云端）。
 *                     **只允许出现在 Bean 装配处**（{@code @ConditionalOnProperty}），业务代码不得读取——
 *                     那是「业务层不得判断当前是哪条路径」这条约束的落点。同一个环境变量还驱动
 *                     {@code spring.ai.model.chat/embedding}，由 {@code AiProviderConsistencyCheck} 启动时断言一致。
 */
@ConfigurationProperties(prefix = "jobpilot.agent")
public record AgentProperties(
        /** 最大迭代轮次；达到即以 BUDGET_EXHAUSTED 结束 */
        int maxIterations,
        /** 单次 run 的工具调用总上限；防止模型在工具间来回打转烧 token */
        int maxToolCallsPerRun,
        /** 单次模型调用超时 */
        Duration llmTimeout,
        /** 模型超时或失败后的重试次数；ARCHITECTURE §6 规定为 1 */
        int llmRetry,
        /** 覆盖配置的模型名；为空时由适配器回落到自身默认模型 */
        String model,
        double temperature,
        int maxTokens,
        String providerPath
) {
}

package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重排序配置（{@code jobpilot.rerank.*}）。
 * <p>
 * {@code enabled=false}（tracked yml 的默认）时检索直接用向量分数排序，不发起重排调用。
 * 只保留规范构造器（Boot 绑定；加第二个构造器会报「No default constructor found」）。
 *
 * @param enabled 是否启用重排
 * @param baseUrl OpenAI 兼容服务的 base（SiliconFlow：{@code https://api.siliconflow.cn/v1}）
 * @param apiKey  云端凭证；本地留空即可（此时 enabled 应为 false）
 * @param model   重排模型名，如 {@code BAAI/bge-reranker-v2-m3}
 */
@ConfigurationProperties(prefix = "jobpilot.rerank")
public record RerankProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model
) {
}

package com.jobpilot.ai.adapter;

import com.jobpilot.config.AgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 启动断言：注入的 {@code ChatModel}/{@code EmbeddingModel} 必须与 {@code jobpilot.agent.provider-path} 一致。
 *
 * <h3>为什么需要它</h3>
 * 「走哪个 provider」由**三处配置**共同决定：{@code jobpilot.agent.provider-path}（选适配器）与
 * {@code spring.ai.model.chat}/{@code .embedding}（选客户端）。它们本该由同一个
 * {@code JOBPILOT_AGENT_PROVIDER_PATH} 驱动，但若有人只改了其中一处，就会出现
 * 「适配器以为是 openai、注入的却是 Ollama 模型」这种**不报错的错配**。这里在启动时炸掉，
 * 把「静默错配」变成一条明确的报错。
 * <p>
 * 构造函数里抛异常 = bean 创建失败 = 上下文启动失败——刻意 fail-fast。
 */
@Component
public class AiProviderConsistencyCheck {

    public AiProviderConsistencyCheck(ChatModel chatModel, EmbeddingModel embeddingModel,
                                      AgentProperties props) {
        String path = props.providerPath() == null ? "ollama" : props.providerPath().strip().toLowerCase();
        assertMatches("chat", chatModel.getClass(), path);
        assertMatches("embedding", embeddingModel.getClass(), path);
    }

    private void assertMatches(String kind, Class<?> beanClass, String providerPath) {
        String simpleName = beanClass.getSimpleName();
        boolean ok = switch (providerPath) {
            case "ollama" -> simpleName.startsWith("Ollama");
            case "openai" -> simpleName.startsWith("OpenAi");
            default -> false;
        };
        if (!ok) {
            throw new IllegalStateException(
                    "provider 配置不一致：jobpilot.agent.provider-path=" + providerPath
                            + "，但注入的 " + kind + " 实现是 " + simpleName
                            + "。请确认 application.yml 里 spring.ai.model." + kind
                            + " 与 jobpilot.agent.provider-path **同源**"
                            + "（三处都应由环境变量 JOBPILOT_AGENT_PROVIDER_PATH 驱动）。");
        }
    }
}

package com.jobpilot.ai.adapter;

import com.jobpilot.ai.ChatRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 云端路径的 ChatAdapter（OpenAI 兼容：智谱 GLM 等）。provider-path=openai 时生效。
 * <p>
 * 与本地路径共用 {@link AbstractSpringAiChatAdapter} 的全部映射逻辑，唯一差别是 options 类型——
 * OpenAI 系用 {@code OpenAiChatOptions}。
 */
@Component
@ConditionalOnProperty(name = "jobpilot.agent.provider-path", havingValue = "openai")
public class CloudChatAdapter extends AbstractSpringAiChatAdapter {

    public CloudChatAdapter(ChatModel chatModel) {
        super(chatModel);
    }

    @Override
    protected String providerId() {
        return "openai";
    }

    @Override
    protected String modelPropertyHint() {
        return "spring.ai.openai.chat.options.model";
    }

    @Override
    protected ChatOptions toSpringOptions(ChatRequest request) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .toolCallbacks(request.tools().stream().map(AbstractSpringAiChatAdapter::toToolCallback).toList())
                .model(resolveModel(request));
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        return builder.build();
    }
}

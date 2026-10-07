package com.jobpilot.ai.adapter;

import com.jobpilot.ai.ChatRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地路径的 ChatAdapter（Ollama）。provider-path=ollama（或未设置）时生效。
 * <p>
 * <b>必须用 {@code OllamaChatOptions.builder()}</b>：Ollama 的 chat model 内部会把 options 强转成
 * {@code OllamaChatOptions}，用 {@code ToolCallingChatOptions.builder()} 会直接 {@code ClassCastException}。
 */
@Component
@ConditionalOnProperty(name = "jobpilot.agent.provider-path", havingValue = "ollama", matchIfMissing = true)
public class LocalChatAdapter extends AbstractSpringAiChatAdapter {

    public LocalChatAdapter(ChatModel chatModel) {
        super(chatModel);
    }

    @Override
    protected String providerId() {
        return "ollama";
    }

    @Override
    protected String modelPropertyHint() {
        return "spring.ai.ollama.chat.options.model";
    }

    @Override
    protected ChatOptions toSpringOptions(ChatRequest request) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
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

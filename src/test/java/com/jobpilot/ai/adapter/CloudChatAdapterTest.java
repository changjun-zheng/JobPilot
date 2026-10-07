package com.jobpilot.ai.adapter;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 云端路径的 options 构造：必须产出 {@link OpenAiChatOptions}（OpenAI 兼容 provider 认它），
 * 且模型名与工具定义都要带上——这是它与本地 Ollama 路径唯一的实质差别。
 */
class CloudChatAdapterTest {

    private ChatModel chatModel;
    private CloudChatAdapter adapter;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        when(chatModel.getDefaultOptions())
                .thenReturn(ChatOptions.builder().model("glm-4.7-flash").build());
        adapter = new CloudChatAdapter(chatModel);
    }

    @Test
    void buildsOpenAiOptionsWithModelAndTools() {
        ChatRequest request = new ChatRequest(
                List.of(new AgentMessage.User("问题")),
                List.of(new ToolDefinition("knowledge_search", "搜知识库", "{}")),
                null, 0.2, 512, null);

        ChatOptions options = adapter.toSpringOptions(request);

        assertThat(options).isInstanceOf(OpenAiChatOptions.class);
        assertThat(options.getModel()).isEqualTo("glm-4.7-flash");
        assertThat(((OpenAiChatOptions) options).getToolCallbacks()).hasSize(1);
    }
}

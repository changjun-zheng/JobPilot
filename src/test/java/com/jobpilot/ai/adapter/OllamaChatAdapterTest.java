package com.jobpilot.ai.adapter;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 适配器侧的用量提取（FP-10）：Spring AI 的 Usage 元数据 → 端口 record。
 * 提取逻辑只能在适配器里（Spring AI 类型不越界），所以这里的测试是计量链路的源头闸门。
 */
class OllamaChatAdapterTest {

    private ChatModel chatModel;
    private OllamaChatAdapter adapter;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        // toSpringOptions 需要「配置了默认模型」的 ChatModel，否则在提取 usage 之前就会抛「未指定模型」
        when(chatModel.getDefaultOptions())
                .thenReturn(ChatOptions.builder().model("qwen2.5:3b").build());
        adapter = new OllamaChatAdapter(chatModel);
    }

    @Test
    void chatExtractsTokenSplitFromProviderMetadata() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("回答").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7)).build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ChatCompletion completion = adapter.chat(singleTurnRequest());

        assertThat(completion.usage()).isEqualTo(new TokenUsage(11, 7));
        assertThat(completion.model()).isEqualTo("qwen2.5:3b");
    }

    @Test
    void chatWithoutUsageMetadataYieldsNullNotZero() {
        // metadata 存在但没有 usage：null = 供应商未提供，不得伪装成 0 消耗
        ChatResponse response = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("回答").build())),
                ChatResponseMetadata.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertThat(adapter.chat(singleTurnRequest()).usage()).isNull();
    }

    @Test
    void chatWithoutMetadataAtAllYieldsNull() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("回答").build())));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertThat(adapter.chat(singleTurnRequest()).usage()).isNull();
    }

    private ChatRequest singleTurnRequest() {
        return new ChatRequest(List.of(new AgentMessage.User("问题")), List.of(), null, null, null, null);
    }
}

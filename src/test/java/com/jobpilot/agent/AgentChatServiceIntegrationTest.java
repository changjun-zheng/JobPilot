package com.jobpilot.agent;

import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.RerankPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.conversation.ConversationService;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 对话持久化与跨 run 上下文的真实 SQL 行为。
 * <p>
 * 用 mock {@code ChatPort}（返回值可预期），但会话 / 消息走真实 MySQL——「消息真按时间升序」
 * 「次轮真把上轮回填给模型」只能靠数据库与真实的 {@code ChatRequest} 证明。
 * <p>
 * 本测试用 {@code @Transactional} 回滚会话数据；{@code AgentRunner} 触发的 trace / 用量写入是
 * {@code REQUIRES_NEW}（会真提交、不在本断言范围内），因此留少量旁路行，无碍。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(classes = AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
public class AgentChatServiceIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private AgentChatService agentChatService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ChatPort chatPort;

    private String tenantA;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        UserContext.set(tenantA);
        reset(chatPort);
        when(chatPort.chat(any()))
                .thenReturn(new ChatCompletion("这是回答", List.of(), FinishReason.STOP, null, "test", "test-model"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void firstRunPersistsConversationAndTwoOrderedMessages() {
        AgentRunner.RunResult result = agentChatService.chat(null, "你好");

        ConversationEntity conversation = conversationService.get(result.conversationId());
        assertThat(conversation.getTitle()).isEqualTo("你好");

        List<ConversationMessageEntity> messages = conversationService.messages(result.conversationId());
        assertThat(messages).extracting(ConversationMessageEntity::getRole)
                .containsExactly("USER", "ASSISTANT");
        assertThat(messages.get(0).getContent()).isEqualTo("你好");
        assertThat(messages.get(1).getContent()).isEqualTo("这是回答");
        // 助手消息带回了 trace，可回放到 agent_trace
        assertThat(messages.get(1).getTraceId()).isEqualTo(result.traceId());
    }

    @Test
    void secondRunFeedsPreviousTurnBackToTheModel() {
        AgentRunner.RunResult first = agentChatService.chat(null, "第一问");
        agentChatService.chat(first.conversationId(), "第二问");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(chatPort, org.mockito.Mockito.times(2)).chat(captor.capture());
        List<com.jobpilot.ai.AgentMessage> secondCall = captor.getAllValues().get(1).messages();

        // [System, User(第一问), Assistant(这是回答), User(第二问)]
        assertThat(secondCall).extracting(com.jobpilot.ai.AgentMessage::text)
                .containsSubsequence("第一问", "这是回答", "第二问");
    }

    @Test
    void unknownConversationIdIsNotFound() {
        assertThatThrownBy(() -> agentChatService.chat("no-such-conversation", "hi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void deleteCascadesMessages() {
        AgentRunner.RunResult result = agentChatService.chat(null, "你好");

        conversationService.delete(result.conversationId());

        assertThat(conversationService.messages(result.conversationId())).isEmpty();
        assertThatThrownBy(() -> conversationService.get(result.conversationId()))
                .isInstanceOf(ApiException.class);
    }

    @TestConfiguration
    public static class TestPorts {

        @Bean
        @Primary
        EmbeddingPort embeddingPort() {
            return org.mockito.Mockito.mock(EmbeddingPort.class);
        }

        @Bean
        @Primary
        VectorStorePort vectorStorePort() {
            return org.mockito.Mockito.mock(VectorStorePort.class);
        }

        @Bean
        @Primary
        ChatPort chatPort() {
            return org.mockito.Mockito.mock(ChatPort.class);
        }

        /**
         * 恒空的 RerankPort：让集成测试**不依赖真实的云端重排**。恒空 → 服务按「不可用」处理，
         * 回退到向量分数顺序（确定性、无网络）。
         */
        @Bean
        @Primary
        RerankPort rerankPort() {
            return (query, documents, topN) -> java.util.List.of();
        }
    }
}

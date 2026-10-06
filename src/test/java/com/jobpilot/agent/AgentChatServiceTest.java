package com.jobpilot.agent;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.conversation.ConversationService;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import com.jobpilot.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话编排（{@code AgentChatService}）。
 * <p>
 * 只锁「编排顺序」与「失败路径」——真实的跨 run 上下文回填交给 {@code AgentChatServiceIT}
 * 用真实 MySQL 证明。
 */
class AgentChatServiceTest {

    private AgentRunner agentRunner;
    private ConversationService conversationService;
    private AgentChatService service;

    private static final String TENANT = "tenant-a";
    private static final String CONV = "conv-1";

    @BeforeEach
    void setUp() {
        agentRunner = mock(AgentRunner.class);
        conversationService = mock(ConversationService.class);
        service = new AgentChatService(agentRunner, conversationService);
        UserContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void firstTurnCreatesConversationAndPersistsBothMessages() {
        when(conversationService.create(TENANT, "你好")).thenReturn(conversation(CONV));
        when(conversationService.loadRecentMessages(eq(CONV), anyInt())).thenReturn(List.of());
        when(agentRunner.run(any())).thenReturn(result(FinishReason.STOP, "你好，我在"));

        service.chat(null, "你好");

        InOrder order = inOrder(conversationService, agentRunner);
        order.verify(conversationService).create(TENANT, "你好");
        order.verify(conversationService).appendUserMessage(TENANT, CONV, "你好");
        order.verify(agentRunner).run(any());
        order.verify(conversationService).appendAssistantMessage(
                eq(TENANT), eq(CONV), eq("你好，我在"), any(), any());
    }

    @Test
    void existingConversationIdUsesGetNotCreate() {
        when(conversationService.get(CONV)).thenReturn(conversation(CONV));
        when(conversationService.loadRecentMessages(eq(CONV), anyInt())).thenReturn(List.of());
        when(agentRunner.run(any())).thenReturn(result(FinishReason.STOP, "答"));

        service.chat(CONV, "追问");

        verify(conversationService, never()).create(anyString(), anyString());
        verify(conversationService).get(CONV);
    }

    @Test
    void errorFinishReasonDoesNotPersistAnAssistantMessage() {
        when(conversationService.create(TENANT, "问题")).thenReturn(conversation(CONV));
        when(conversationService.loadRecentMessages(eq(CONV), anyInt())).thenReturn(List.of());
        when(agentRunner.run(any())).thenReturn(result(FinishReason.ERROR, "模型调用失败，请稍后重试。"));

        service.chat(null, "问题");

        // 罐头错误话不该当成助手的真实回答落库（该会话将出现两条连续 USER 消息，是有意为之）
        verify(conversationService, never())
                .appendAssistantMessage(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void priorMessagesAreMappedToProtocolAndPassedToRunner() {
        when(conversationService.create(TENANT, "现在")).thenReturn(conversation(CONV));
        when(conversationService.loadRecentMessages(eq(CONV), anyInt())).thenReturn(List.of(
                storedMessage("USER", "上一轮的问题"),
                storedMessage("ASSISTANT", "上一轮的回答")));
        when(agentRunner.run(any())).thenReturn(result(FinishReason.STOP, "答"));

        service.chat(null, "现在");

        ArgumentCaptor<AgentRunner.RunRequest> captor = ArgumentCaptor.forClass(AgentRunner.RunRequest.class);
        verify(agentRunner).run(captor.capture());
        List<AgentMessage> prior = captor.getValue().priorMessages();
        assertThat(prior).hasSize(2);
        assertThat(prior.get(0)).isInstanceOf(AgentMessage.User.class);
        assertThat(prior.get(1)).isInstanceOf(AgentMessage.Assistant.class);
        assertThat(prior.get(0).text()).isEqualTo("上一轮的问题");
        assertThat(captor.getValue().conversationId()).isEqualTo(CONV);
    }

    // ── helpers ───────────────────────────────────────────────

    private ConversationEntity conversation(String id) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(id);
        conversation.setUserId(TENANT);
        return conversation;
    }

    private ConversationMessageEntity storedMessage(String role, String content) {
        ConversationMessageEntity message = new ConversationMessageEntity();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private AgentRunner.RunResult result(FinishReason reason, String answer) {
        return new AgentRunner.RunResult("trace-1", CONV, answer, reason, List.of(), List.of());
    }
}

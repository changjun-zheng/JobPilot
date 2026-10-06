package com.jobpilot.conversation;

import com.jobpilot.common.ApiException;
import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import com.jobpilot.mapper.ConversationMapper;
import com.jobpilot.mapper.ConversationMessageMapper;
import com.jobpilot.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话持久化服务的校验与边界（PRD-FP-2.1）。
 * <p>
 * mock mapper、不依赖 MySQL。排序 / 级联删除的真实行为交给 {@code AgentChatServiceIT}
 * 用真实 SQL 断言——「消息真按时间升序」「跨租户删除真影响 0 行」这类只能靠数据库证明。
 */
class ConversationServiceTest {

    private ConversationMapper conversationMapper;
    private ConversationMessageMapper messageMapper;
    private ConversationService service;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(ConversationMessageMapper.class);
        service = new ConversationService(conversationMapper, messageMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ── 创建 ───────────────────────────────────────────────────

    @Test
    void createDerivesTitleFromFirstMessageAndSetsTenant() {
        service.create(TENANT, "帮我分析这份 JD 的差距");

        ArgumentCaptor<ConversationEntity> captor = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getTitle()).isEqualTo("帮我分析这份 JD 的差距");
    }

    @Test
    void titleCollapsesWhitespaceAndTruncatesByCodePoint() {
        // 换行折叠成空格；40 个字符截到 30 个
        ConversationEntity created = service.create(TENANT, "第一行\n第二行 " + "x".repeat(40));

        assertThat(created.getTitle()).doesNotContain("\n");
        assertThat(created.getTitle().codePointCount(0, created.getTitle().length())).isEqualTo(30);
    }

    @Test
    void titleTruncationDoesNotSplitSurrogatePair() {
        // 全是 4 字节 emoji（每个 2 个 char）：按 char 截会劈开代理对产生乱码
        String emojis = "😀".repeat(40);

        ConversationEntity created = service.create(TENANT, emojis);

        assertThat(created.getTitle().codePointCount(0, created.getTitle().length())).isEqualTo(30);
        // 劈开代理对会留下孤立的高位代理，round-trip 后出现替换符
        assertThat(created.getTitle()).doesNotContain("�");
    }

    @Test
    void createRejectsTenantMismatchAgainstContext() {
        UserContext.set("tenant-b");

        assertThatThrownBy(() -> service.create(TENANT, "hi"))
                .isInstanceOf(UnauthorizedException.class);
        verify(conversationMapper, never()).insert(any(ConversationEntity.class));
    }

    // ── 读取 ───────────────────────────────────────────────────

    @Test
    void getOnMissingIdIsNotFound() {
        when(conversationMapper.selectById("gone")).thenReturn(null);

        assertThatThrownBy(() -> service.get("gone"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void loadRecentMessagesReversesDescendingQueryBackToAscending() {
        // 查询按倒序取「最近 N 条」，服务必须翻回升序（上下文回填要按时间）
        ConversationMessageEntity newest = message("newer");
        ConversationMessageEntity older = message("older");
        when(messageMapper.selectList(any())).thenReturn(List.of(newest, older));

        List<ConversationMessageEntity> result = service.loadRecentMessages("conv-1", 10);

        assertThat(result).extracting(ConversationMessageEntity::getContent)
                .containsExactly("older", "newer");
    }

    // ── 删除 ───────────────────────────────────────────────────

    @Test
    void deleteChecksExistenceBeforeDeleting() {
        when(conversationMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.delete("ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话不存在");
        // 无外键时不做前置检查就会静默删 0 行并谎报成功
        verify(messageMapper, never()).delete(any());
        verify(conversationMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteCascadesMessagesThenConversation() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation("conv-1"));

        service.delete("conv-1");

        verify(messageMapper).delete(any());
        verify(conversationMapper).deleteById("conv-1");
    }

    // ── helpers ───────────────────────────────────────────────

    private ConversationEntity conversation(String id) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setUserId(TENANT);
        entity.setTitle("t");
        return entity;
    }

    private ConversationMessageEntity message(String content) {
        ConversationMessageEntity message = new ConversationMessageEntity();
        message.setContent(content);
        return message;
    }
}

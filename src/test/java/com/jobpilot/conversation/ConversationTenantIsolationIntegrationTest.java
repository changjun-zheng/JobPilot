package com.jobpilot.conversation;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.security.JwtService;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话 / 消息的跨租户隔离（NFR-4）。
 * <p>
 * 租户条件由 {@code TenantLineInnerInterceptor} 注入，业务代码里没有一处手写 {@code user_id} 过滤——
 * 本测试要锁的正是「漏不掉」：A 的会话对 B 表现为不存在（读、删均 404），列表互不可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class ConversationTenantIsolationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private JwtService jwtService;

    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void anotherTenantCannotReadOrDeleteConversation() throws Exception {
        String conversationA = createConversation(tenantA, "A 的私密问题");
        String tokenB = jwtService.issue(tenantB);

        // HTTP 路径：跨租户读 / 删都表现为 404（不可区分「不存在」与「无权限」）
        mockMvc.perform(get("/api/v1/conversations/{id}", conversationA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/conversations/{id}", conversationA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // 服务层直连同样挡住
        UserContext.set(tenantB);
        assertThatThrownBy(() -> conversationService.get(conversationA))
                .isInstanceOf(ApiException.class);
        assertThat(conversationService.messages(conversationA)).isEmpty();
    }

    @Test
    void listOnlyReturnsOwnConversations() {
        String conversationA = createConversation(tenantA, "A 的会话");
        String conversationB = createConversation(tenantB, "B 的会话");

        UserContext.set(tenantA);
        assertThat(conversationService.list(50)).extracting(ConversationEntity::getId)
                .contains(conversationA).doesNotContain(conversationB);

        UserContext.set(tenantB);
        assertThat(conversationService.list(50)).extracting(ConversationEntity::getId)
                .contains(conversationB).doesNotContain(conversationA);
    }

    private String createConversation(String tenant, String firstMessage) {
        UserContext.set(tenant);
        ConversationEntity conversation = conversationService.create(tenant, firstMessage);
        conversationService.appendUserMessage(tenant, conversation.getId(), firstMessage);
        UserContext.clear();
        return conversation.getId();
    }
}

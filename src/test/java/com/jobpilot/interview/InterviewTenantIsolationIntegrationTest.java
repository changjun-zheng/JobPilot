package com.jobpilot.interview;

import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.common.ApiException;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 面试会话的跨租户隔离（NFR-4）：A 的面试对 B 表现为不存在（读/答/收尾均 404）。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class InterviewTenantIsolationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private InterviewService interviewService;
    @Autowired
    private ChatPort chatPort;

    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
        reset(chatPort);
        when(chatPort.chat(any()))
                .thenReturn(new ChatCompletion("题", List.of(), FinishReason.STOP, null, "test", "test-model"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void anotherTenantCannotTouchInterviewSession() {
        UserContext.set(tenantA);
        String id = interviewService.start(
                new InterviewService.StartCommand(tenantA, "A 公司", "后端", "BIG_TECH", null)).sessionId();

        UserContext.set(tenantB);
        assertThatThrownBy(() -> interviewService.detail(id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> interviewService.answer(id, "偷看")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> interviewService.report(id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> interviewService.finish(id)).isInstanceOf(ApiException.class);
        assertThat(interviewService.list(50)).isEmpty();

        // A 自己仍然看得到
        UserContext.set(tenantA);
        assertThat(interviewService.detail(id).sessionId()).isEqualTo(id);
    }
}

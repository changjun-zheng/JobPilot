package com.jobpilot.interview;

import com.jobpilot.agent.ApprovalExecutionService;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 面试「完整弧线 → 报告 → 部分审批写记忆」的真实 SQL 行为（BRD US-3）。
 * <p>
 * mock {@code ChatPort} 让每轮出题可预期，但会话 / 消息 / 草稿 / 记忆都走真实 MySQL——
 * 「三轮走完」「报告落草稿」「勾几条就写几条记忆」只能靠数据库证明。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class InterviewFlowIntegrationTest extends MySqlIntegrationTestBase {

    private static final String REPORT_JSON = """
            {"summary":"总体不错","dimensions":[{"name":"技术深度","comment":"ok","score":3}],
             "weaknesses":[{"content":"并发基础薄弱","confidence":0.8,"note":"多线程回答不完整"},
                           {"content":"表达不够结构化","confidence":0.6,"note":"回答跳跃"}]}
            """;

    @Autowired
    private InterviewService interviewService;
    @Autowired
    private ApprovalExecutionService approvalExecutionService;
    @Autowired
    private ChatPort chatPort;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenant;

    @BeforeEach
    void setUp() {
        tenant = "tenant-" + UUID.randomUUID();
        UserContext.set(tenant);
        reset(chatPort);
        // 配置 questions-per-round=[3,2,2] → 共 7 题。ChatPort 被调 8 次：7 次出题 + 1 次出报告
        ChatCompletion question = completion("请继续讲讲。");
        ChatCompletion report = completion(REPORT_JSON);
        when(chatPort.chat(any()))
                .thenReturn(question).thenReturn(question).thenReturn(question).thenReturn(question)
                .thenReturn(question).thenReturn(question).thenReturn(question)
                .thenReturn(report);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void fullArcProducesReportAndPartialApprovalWritesExactlyTheChosenMemories() {
        var start = interviewService.start(
                new InterviewService.StartCommand(tenant, "字节跳动", "后端", "BIG_TECH", null));
        String sessionId = start.sessionId();
        assertThat(start.round()).isEqualTo(1);
        assertThat(start.question()).isNotBlank();

        // 依次作答，直到服务端判定结束（共 7 轮）
        InterviewService.TurnResult last = start;
        for (int i = 0; i < 7 && !last.finished(); i++) {
            last = interviewService.answer(sessionId, "第 " + (i + 1) + " 题的回答");
        }
        assertThat(last.finished()).isTrue();

        var report = interviewService.report(sessionId);
        assertThat(report.draftId()).isNotBlank();
        assertThat(report.candidateIds()).containsExactly("c1", "c2");
        assertThat(report.status()).isEqualTo("REPORT_PENDING");

        // 只勾一条 → 只写一条记忆（部分审批）
        approvalExecutionService.approve(report.draftId(), List.of("c1"));

        assertThat(countMemories()).isEqualTo(1);
        assertThat(draftStatus(report.draftId())).isEqualTo("PARTIALLY_APPROVED");
    }

    private ChatCompletion completion(String content) {
        return new ChatCompletion(content, List.of(), FinishReason.STOP, null, "test", "test-model");
    }

    private int countMemories() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_memory WHERE user_id = ?", Integer.class, tenant);
        return n == null ? 0 : n;
    }

    private String draftStatus(String draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval_draft WHERE id = ?", String.class, draftId);
    }
}

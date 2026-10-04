package com.jobpilot.memory;

import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.agent.ApprovalExecutionService;
import com.jobpilot.common.ApiException;
import com.jobpilot.domain.UserMemoryEntity;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批量部分审批的**真实数据库**证据（PRD-FP-3.2「整批，可部分选择」）。
 * <p>
 * 这是 I-3b 与 I-2 那个单条审批的本质区别，也是最容易写错的地方：
 * 用户勾了几条就只该写几条，而「勾了 3 条却写了 2 条」这种偏差必须能被断言抓住。
 * 用真实 MySQL 而不是 mock——「恰好写入了选中子集」是数据库里的事实。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class MemoryApprovalIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private ApprovalDraftService draftService;
    @Autowired
    private ApprovalExecutionService executionService;
    @Autowired
    private MemoryService memoryService;

    private static final String TENANT = "mem-tenant";

    @BeforeEach
    void setUp() {
        UserContext.set(TENANT);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ── 部分审批：只写选中的 ────────────────────────────────────

    @Test
    void approvingASubsetWritesExactlyThatSubset() {
        String draftId = draftOfThreeCandidates();

        ApprovalExecutionService.ApprovalResult result =
                executionService.approve(draftId, List.of("c1", "c3"));

        assertThat(result.status()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(result.writtenMemoryIds()).hasSize(2);
        // 关键：只写入选中的，未选中的那条不得存在
        List<UserMemoryEntity> memories = memoryService.list(null, null, 0);
        assertThat(memories).hasSize(2);
        assertThat(memories).extracting(UserMemoryEntity::getContent)
                .containsExactlyInAnyOrder("弱点一", "计划三")
                .doesNotContain("弱点二");
    }

    @Test
    void approvingEveryCandidateIsApprovedNotPartially() {
        String draftId = draftOfThreeCandidates();

        ApprovalExecutionService.ApprovalResult result =
                executionService.approve(draftId, List.of("c1", "c2", "c3"));

        // 全选是 APPROVED，不是 PARTIALLY_APPROVED——后者意味着「还有没批的」，那样报就是撒谎
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(memoryService.list(null, null, 0)).hasSize(3);
    }

    @Test
    void omittingTheSelectionApprovesTheWholeBatch() {
        String draftId = draftOfThreeCandidates();

        ApprovalExecutionService.ApprovalResult result = executionService.approve(draftId, null);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(memoryService.list(null, null, 0)).hasSize(3);
    }

    @Test
    void approvingTwiceWithADifferentSelectionIsANoOpReturningPersistedState() {
        String draftId = draftOfThreeCandidates();

        executionService.approve(draftId, List.of("c1"));
        // 第二次带着另一套选择：草稿已终态，必须被丢弃，且返回的是**持久化**的事实
        ApprovalExecutionService.ApprovalResult second =
                executionService.approve(draftId, List.of("c2", "c3"));

        assertThat(second.status()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(second.writtenMemoryIds()).hasSize(1);
        // 库里仍只有第一次写入的那条
        assertThat(memoryService.list(null, null, 0)).hasSize(1);
        assertThat(memoryService.list(null, null, 0).get(0).getContent()).isEqualTo("弱点一");
    }

    // ── 会撒谎的输入必须被拒 ────────────────────────────────────

    @Test
    void emptySelectionIsRejectedRatherThanApprovingNothing() {
        String draftId = draftOfThreeCandidates();

        // 空选择若被接受，会得到 PARTIALLY_APPROVED 却零写入——一个自相矛盾的终态
        assertThatThrownBy(() -> executionService.approve(draftId, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未选择任何候选项");
        assertThat(memoryService.list(null, null, 0)).isEmpty();
    }

    @Test
    void unknownCandidateIdIsRejectedRatherThanSilentlyDropped() {
        String draftId = draftOfThreeCandidates();

        // 静默丢弃会让用户看到「我勾了 2 条」而实际写 1 条，响应无法自证
        assertThatThrownBy(() -> executionService.approve(draftId, List.of("c1", "c9")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("候选项不存在");
        assertThat(memoryService.list(null, null, 0)).isEmpty();
    }

    @Test
    void duplicateCandidateIdIsRejected() {
        String draftId = draftOfThreeCandidates();

        assertThatThrownBy(() -> executionService.approve(draftId, List.of("c1", "c1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重复选择");
        assertThat(memoryService.list(null, null, 0)).isEmpty();
    }

    @Test
    void selectionOnANonBatchDraftIsRejected() {
        String draftId = draftService.createDraft(TENANT, "trace-jd", "conv-1",
                "save_jd_analysis_to_kb", "{\"content\":\"# 分析\"}");

        assertThatThrownBy(() -> executionService.approve(draftId, List.of("c1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不是批量审批");
    }

    @Test
    void failedValidationLeavesTheDraftPendingSoItCanBeRetried() {
        String draftId = draftOfThreeCandidates();

        // 校验发生在抢占审批权之前，因此失败后草稿仍是 PENDING——用户能重来
        assertThatThrownBy(() -> executionService.approve(draftId, List.of("c9")))
                .isInstanceOf(ApiException.class);

        assertThat(draftService.get(draftId).getStatus()).isEqualTo("PENDING");
    }

    // ── 载荷校验 ────────────────────────────────────────────────

    @Test
    void unknownMemoryTypeInAPayloadIsRejected() {
        // 模拟「枚举改名后遗留的旧草稿」：写入时必须 fail-closed，而不是插入未知类型
        String draftId = draftService.createDraft(TENANT, "trace-old", "conv-1",
                "memory_candidate_create",
                "{\"source\":\"面试评估\",\"candidates\":[{\"candidateId\":\"c1\","
                        + "\"type\":\"LEGACY_TYPE\",\"content\":\"旧类型\"}]}");

        assertThatThrownBy(() -> executionService.approve(draftId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知记忆类型");
        assertThat(memoryService.list(null, null, 0)).isEmpty();
    }

    @Test
    void overlengthContentInAPayloadIsRejected() {
        String draftId = draftService.createDraft(TENANT, "trace-long", "conv-1",
                "memory_candidate_create",
                "{\"source\":\"面试评估\",\"candidates\":[{\"candidateId\":\"c1\","
                        + "\"type\":\"JOB_PREFERENCE\",\"content\":\""
                        + "长".repeat(MemoryService.MAX_CONTENT + 1) + "\"}]}");

        assertThatThrownBy(() -> executionService.approve(draftId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过");
    }

    // ── 写入的记忆携带可回溯的来源 ──────────────────────────────

    @Test
    void writtenMemoriesPointBackToTheirApprovalDraft() {
        String draftId = draftOfThreeCandidates();

        executionService.approve(draftId, List.of("c1"));

        // 「当初批准过什么」必须可回溯，即使之后用户删掉了这条记忆
        assertThat(memoryService.findIdsBySourceDraft(draftId)).hasSize(1);
        assertThat(draftService.get(draftId).getApprovalSelection()).contains("c1");
    }

    // ── helpers ───────────────────────────────────────────────

    /** 三条候选：c1 弱点、c2 弱点、c3 计划 */
    private String draftOfThreeCandidates() {
        String payload = """
                {"source":"面试评估","candidates":[
                  {"candidateId":"c1","type":"INTERVIEW_WEAKNESS","content":"弱点一","confidence":0.8},
                  {"candidateId":"c2","type":"INTERVIEW_WEAKNESS","content":"弱点二","note":"追问时暴露"},
                  {"candidateId":"c3","type":"PREPARATION_PLAN","content":"计划三"}
                ]}""";
        // trace_id 是 VARCHAR(36)，直接用 UUID 本体（加前缀就超长了）
        return draftService.createDraft(TENANT, java.util.UUID.randomUUID().toString(),
                "conv-1", "memory_candidate_create", payload);
    }
}

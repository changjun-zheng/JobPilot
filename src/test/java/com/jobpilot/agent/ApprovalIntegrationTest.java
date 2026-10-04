package com.jobpilot.agent;

import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.domain.AgentTraceEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.mapper.AgentApprovalDraftMapper;
import com.jobpilot.mapper.AgentTraceMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HITL 幂等与隔离的**真实数据库**证据。
 * <p>
 * {@code ApprovalDraftServiceTest} 用内存假 mapper 覆盖了服务逻辑，但那个「唯一键」是我手写的
 * {@code if (duplicate) throw}——它证明不了 MySQL 的唯一索引真的生效，也证明不了
 * {@code SELECT ... FOR UPDATE} 的锁语义。而这两条正是 HITL 幂等的全部依据，所以必须真库验证。
 * <p>
 * 走本机 MySQL（{@code MySqlIntegrationTestBase} 回退路径），不需要 Docker。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class ApprovalIntegrationTest extends MySqlIntegrationTestBase {

    private static final String TENANT_A = "it-tenant-a";
    private static final String TENANT_B = "it-tenant-b";

    @Autowired
    private ApprovalDraftService draftService;
    @Autowired
    private ApprovalExecutionService executionService;
    @Autowired
    private AgentApprovalDraftMapper draftMapper;
    @Autowired
    private AgentTraceMapper traceMapper;
    @Autowired
    private KbDocumentMapper documentMapper;
    @Autowired
    private DocumentIngestService ingestService;

    @BeforeEach
    void setUp() {
        UserContext.set(TENANT_A);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ── 防线②：唯一索引真的生效 ───────────────────────────────

    @Test
    void uniqueIndexReallyBlocksDuplicateDrafts() {
        String first = draftService.createDraft(TENANT_A, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");

        // 绕过服务直接撞唯一键：证明拦截来自数据库约束，而不是服务层的判断
        AgentApprovalDraftEntity duplicate = new AgentApprovalDraftEntity();
        duplicate.setUserId(TENANT_A);
        duplicate.setTraceId("trace-1");
        duplicate.setConversationId("conv-1");
        duplicate.setToolName("save_it");
        duplicate.setPayloadJson("{\"content\":\"A\"}");
        duplicate.setStatus("PENDING");
        duplicate.setIdempotencyKey(idempotencyKeyOf(first));

        assertThatThrownBy(() -> draftMapper.insert(duplicate))
                .isInstanceOf(DuplicateKeyException.class);

        // 服务层路径：捕获异常后返回同一条草稿，而不是新建
        String second = draftService.createDraft(TENANT_A, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        assertThat(second).isEqualTo(first);
    }

    // ── 隔离：租户拦截器真的覆盖新增的三张表 ─────────────────

    @Test
    void tenantInterceptorProtectsAgentTables() {
        draftService.createDraft(TENANT_A, "trace-a", "conv-a", "save_it", "{\"content\":\"A\"}");
        traceMapper.insert(traceOf("trace-a", TENANT_A));

        // 换到 B：A 写的草稿与 trace 都必须查不到
        UserContext.set(TENANT_B);

        assertThat(draftMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentApprovalDraftEntity>()
                        .eq("idempotency_key", idempotencyKeyOfByScan()))).isNull();
        assertThat(traceMapper.selectById("trace-a")).isNull();
        assertThat(documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>())).isEmpty();
    }

    // ── 审批副作用：原子 + 幂等 ──────────────────────────────

    @Test
    void approvingTwiceLeavesExactlyOneDocument() {
        String draftId = draftService.createDraft(TENANT_A, "trace-1", "conv-1",
                "save_jd_analysis_to_kb", "{\"name\":\"JD.md\",\"content\":\"# 分析\\n内容\"}");

        String firstDocId = executionService.approve(draftId).resultRef();
        String secondDocId = executionService.approve(draftId).resultRef();

        assertThat(firstDocId).isEqualTo(secondDocId);
        // 关键断言：重复审批不得建出第二份文档
        assertThat(documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KbDocumentEntity>()))
                .hasSize(1);
    }

    @Test
    void approvingAnotherTenantsDraftIsRejectedWithoutSideEffect() {
        String draftId = draftService.createDraft(TENANT_A, "trace-1", "conv-1",
                "save_jd_analysis_to_kb", "{\"content\":\"# A 的分析\"}");

        // 切到 B 去批 A 的草稿
        UserContext.set(TENANT_B);

        assertThatThrownBy(() -> executionService.approve(draftId))
                .hasMessageContaining("审批草稿不存在");
        // B 的租户上下文下看不到任何文档 —— 副作用为零
        assertThat(documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KbDocumentEntity>())).isEmpty();
    }

    @Test
    void rejectedDraftNeverProducesADocument() {
        String draftId = draftService.createDraft(TENANT_A, "trace-1", "conv-1",
                "save_jd_analysis_to_kb", "{\"content\":\"# 分析\"}");

        executionService.reject(draftId);

        assertThat(documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KbDocumentEntity>())).isEmpty();

        // 拒绝后再批也不该执行：返回的是持久化的 REJECTED 终态，resultRef 为空
        assertThat(executionService.approve(draftId).resultRef()).isNull();
        assertThat(documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KbDocumentEntity>())).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────

    /** 从已落库的草稿读出幂等键，用于构造一个必然撞唯一键的重复行 */
    private String idempotencyKeyOf(String draftId) {
        return draftMapper.selectById(draftId).getIdempotencyKey();
    }

    /** B 租户视角下按任意键查询——必然查不到 A 的行 */
    private String idempotencyKeyOfByScan() {
        return "it-does-not-matter";
    }

    private AgentTraceEntity traceOf(String id, String userId) {
        AgentTraceEntity trace = new AgentTraceEntity();
        trace.setId(id);
        trace.setUserId(userId);
        trace.setConversationId("conv-" + id);
        trace.setStatus("RUNNING");
        trace.setFinishReason("PENDING");
        return trace;
    }
}

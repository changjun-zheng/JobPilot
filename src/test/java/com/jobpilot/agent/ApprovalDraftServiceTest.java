package com.jobpilot.agent;

import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.memory.MemoryService;
import com.jobpilot.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL 审批链（ARCHITECTURE.md §4.4）。
 * <p>
 * 覆盖两条独立防线：并发靠 {@code FOR UPDATE} 锁行 + 状态检查，重复落库靠唯一键 +
 * {@link DuplicateKeyException}。此外还要证明<b>跨租户审批被拒</b>且不产生任何副作用。
 */
class ApprovalDraftServiceTest {

    private AgentApprovalDraftMapperHolder holder;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        holder = new AgentApprovalDraftMapperHolder();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ── 防线②：重复落库 ───────────────────────────────────────

    @Test
    void duplicateIdempotencyKeyReturnsTheSameDraftInsteadOfCreatingANewOne() {
        // 草稿的写入与重读都发生在请求线程上，租户拦截器依赖 UserContext——
        // 真实链路里认证拦截器已 set 好，测试必须照样 set，否则重读会「查不到」。
        UserContext.set(TENANT);
        ApprovalDraftService service = holder.service();
        String first = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        // 同一 trace / 工具 / 载荷再次请求 → 唯一键撞车
        String second = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");

        assertThat(second).isEqualTo(first);
        assertThat(holder.drafts).hasSize(1); // 没有产生第二份草稿
    }

    @Test
    void differentPayloadProducesADifferentDraft() {
        ApprovalDraftService service = holder.service();
        String first = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        String second = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"B\"}");

        assertThat(second).isNotEqualTo(first);
        assertThat(holder.drafts).hasSize(2);
    }

    // ── 防线①：并发 / 重复审批 ────────────────────────────────

    @Test
    void approvingTwiceExecutesTheSideEffectOnlyOnce() {
        ApprovalDraftService service = holder.service();
        String draftId = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        UserContext.set(TENANT);

        assertThat(service.claimForApproval(draftId)).isTrue();
        // 第二次：状态已是 APPROVED，必须返回 false，让调用方按幂等返回而不重复执行副作用
        assertThat(service.claimForApproval(draftId)).isFalse();
    }

    @Test
    void approvingUnknownDraftIsNotFound() {
        UserContext.set(TENANT);

        assertThatThrownBy(() -> holder.service().claimForApproval("no-such-draft"))
                .hasMessageContaining("审批草稿不存在");
    }

    @Test
    void rejectedDraftCannotBeApprovedAfterwards() {
        ApprovalDraftService service = holder.service();
        String draftId = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        UserContext.set(TENANT);

        service.reject(draftId);

        // 拒绝后不能再批
        assertThat(service.claimForApproval(draftId)).isFalse();
    }

    @Test
    void rejectingTwiceIsIdempotent() {
        ApprovalDraftService service = holder.service();
        String draftId = service.createDraft(TENANT, "trace-1", "conv-1", "save_it", "{\"content\":\"A\"}");
        UserContext.set(TENANT);

        assertThat(service.reject(draftId)).isTrue();
        assertThat(service.reject(draftId)).isFalse();
    }

    /**
     * 跨租户防线：草稿归属 A，B 去审批时<b>查不到</b>这一行。
     * <p>
     * 真实链路里这是租户拦截器干的（SELECT 会被加上 {@code user_id = B}）。
     * 这里用内存假实现模拟同一语义：按当前 {@code UserContext} 过滤。
     */
    @Test
    void otherTenantCannotApproveTheDraft() {
        ApprovalDraftService service = holder.service();
        String draftId = service.createDraft("tenant-a-owner", "trace-1", "conv-1", "save_it", "{}");
        // 切换到另一个租户
        UserContext.set("tenant-b-attacker");

        assertThatThrownBy(() -> service.claimForApproval(draftId))
                .hasMessageContaining("审批草稿不存在");
    }

    // ── 执行侧：审批通过才产生副作用，且只发生一次 ──────────────

    @Test
    void approveExecutesIngestExactlyOnceEvenWhenCalledTwice() {
        UserContext.set(TENANT);
        ApprovalDraftService draftService = holder.service();
        DocumentIngestService ingestService = mock(DocumentIngestService.class);
        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setId("doc-new");
        when(ingestService.enqueue(any())).thenReturn(doc);
        ApprovalExecutionService execution = new ApprovalExecutionService(draftService, ingestService, mock(MemoryService.class));

        String draftId = draftService.createDraft(TENANT, "trace-1", "conv-1",
                "save_jd_analysis_to_kb", "{\"content\":\"# 分析\"}");
        UserContext.set(TENANT);

        ApprovalExecutionService.ApprovalResult first = execution.approve(draftId);
        ApprovalExecutionService.ApprovalResult second = execution.approve(draftId);

        assertThat(first.resultRef()).isEqualTo("doc-new");
        // 第二次是幂等 no-op，返回的是**持久化**的结果，不是本次请求又执行了一遍
        assertThat(second.resultRef()).isEqualTo("doc-new");
        assertThat(second.status()).isEqualTo("APPROVED");
        // 关键：重复审批不得建出第二份文档
        verify(ingestService, times(1)).enqueue(any());
    }

    @Test
    void rejectProducesNoSideEffectAtAll() {
        ApprovalDraftService draftService = holder.service();
        DocumentIngestService ingestService = mock(DocumentIngestService.class);
        ApprovalExecutionService execution = new ApprovalExecutionService(draftService, ingestService, mock(MemoryService.class));

        String draftId = draftService.createDraft(TENANT, "trace-1", "conv-1",
                "save_jd_analysis_to_kb", "{\"content\":\"# 分析\"}");
        UserContext.set(TENANT);

        execution.reject(draftId);

        verify(ingestService, never()).enqueue(any());
    }

    // ── 内存假 mapper：只为让上面这些状态机用例脱离 MySQL ──────

    /**
     * 极简内存版 mapper，刻意只实现被测行为：
     * <ul>
     *   <li>{@code insert} 按 {@code (userId, idempotencyKey)} 唯一键去重，重复即抛
     *       {@link DuplicateKeyException}（对应数据库唯一约束）；</li>
     *   <li>{@code selectByIdForUpdate} <b>按当前 UserContext 过滤</b>——这正是租户拦截器
     *       在真实 SQL 上做的事，也是跨租户用例能成立的前提。</li>
     * </ul>
     * 真实 SQL 行为由 {@code ApprovalIntegrationTest} 在真 MySQL 上覆盖。
     */
    private static final class AgentApprovalDraftMapperHolder {

        private final java.util.List<AgentApprovalDraftEntity> drafts = new java.util.ArrayList<>();
        private final com.jobpilot.mapper.AgentApprovalDraftMapper mapper =
                mock(com.jobpilot.mapper.AgentApprovalDraftMapper.class);

        private AgentApprovalDraftMapperHolder() {
            doAnswer(invocation -> {
                AgentApprovalDraftEntity draft = invocation.getArgument(0);
                if (draft.getId() == null) {
                    draft.setId("draft-" + (drafts.size() + 1));
                }
                boolean duplicate = drafts.stream().anyMatch(existing ->
                        java.util.Objects.equals(existing.getUserId(), draft.getUserId())
                                && java.util.Objects.equals(existing.getIdempotencyKey(), draft.getIdempotencyKey()));
                if (duplicate) {
                    throw new DuplicateKeyException("uk_agent_approval_idempotency");
                }
                drafts.add(draft);
                return 1;
            }).when(mapper).insert(any(AgentApprovalDraftEntity.class));

            // 对应 ApprovalDraftService 的「按 user_id 重读」：不能跨租户读到别人的草稿
            when(mapper.selectOne(any())).thenAnswer(invocation -> {
                String currentTenant = UserContext.get();
                return drafts.stream()
                        .filter(d -> java.util.Objects.equals(d.getUserId(), currentTenant))
                        .reduce((first, second) -> second)
                        .orElse(null);
            });

            // 租户拦截器的真实效果：只能看到自己租户的行
            when(mapper.selectByIdForUpdate(anyString())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                String currentTenant = UserContext.get();
                return drafts.stream()
                        .filter(d -> java.util.Objects.equals(d.getId(), id))
                        .filter(d -> java.util.Objects.equals(d.getUserId(), currentTenant))
                        .findFirst()
                        .orElse(null);
            });

            // selectById 与 selectByIdForUpdate 受同一套租户条件保护
            when(mapper.selectById(anyString())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                String currentTenant = UserContext.get();
                return drafts.stream()
                        .filter(d -> java.util.Objects.equals(d.getId(), id))
                        .filter(d -> java.util.Objects.equals(d.getUserId(), currentTenant))
                        .findFirst()
                        .orElse(null);
            });

            doAnswer(invocation -> {
                AgentApprovalDraftEntity updated = invocation.getArgument(0);
                return drafts.stream()
                        .filter(d -> java.util.Objects.equals(d.getId(), updated.getId()))
                        .findFirst()
                        .map(d -> {
                            // 只复制非空字段——真实 MyBatis-Plus 的 updateById 默认按 NOT_NULL 策略
                            // 生成 SET 子句，部分实体不会把未设置的列清成 NULL。
                            // 全量复制会让 recordResult（只带 id/resultRef/executedAt）把 status 抹掉，
                            // 那是假实现比真实现更严格，会掩盖真实行为。
                            if (updated.getStatus() != null) {
                                d.setStatus(updated.getStatus());
                            }
                            if (updated.getApprovalSelection() != null) {
                                d.setApprovalSelection(updated.getApprovalSelection());
                            }
                            if (updated.getDecidedAt() != null) {
                                d.setDecidedAt(updated.getDecidedAt());
                            }
                            if (updated.getDecidedBy() != null) {
                                d.setDecidedBy(updated.getDecidedBy());
                            }
                            if (updated.getResultRef() != null) {
                                d.setResultRef(updated.getResultRef());
                            }
                            if (updated.getExecutedAt() != null) {
                                d.setExecutedAt(updated.getExecutedAt());
                            }
                            return d;
                        })
                        .map(d -> 1)
                        .orElse(0);
            }).when(mapper).updateById(any(AgentApprovalDraftEntity.class));
        }

        private ApprovalDraftService service() {
            return new ApprovalDraftService(mapper);
        }
    }
}

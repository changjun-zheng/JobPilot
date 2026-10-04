package com.jobpilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.agent.ApprovalDraftService.ApprovalOutcome;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.memory.MemoryService;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审批通过后的副作用执行（ARCHITECTURE.md §4.4）。
 *
 * <h3>白名单而不是 if-else 分派</h3>
 * 只认 {@code save_jd_analysis_to_kb}（单条）与 {@code memory_candidate_create}（批量）。
 * 白名单的好处是「将来加工具却忘了实现」会立刻抛错，而不是静默不执行任何副作用。
 *
 * <h3>校验必须在抢占审批权之前</h3>
 * {@link ApprovalDraftService#claimForApproval} 与副作用共用调用方的事务，因此它抛异常时会一起回滚。
 * 但<b>不能依赖这一点</b>：一旦某次抢占提交而副作用没跑，草稿就成了永久终态，
 * 此后每次 approve 都是静默 no-op，用户再也推不动它——没有任何补救接口。
 * 所以载荷解析、选择校验、工具白名单全部前置，抢占之后只剩「执行」。
 *
 * <h3>事务边界</h3>
 * 副作用与状态翻转同一事务：{@code enqueue} / 批量插记忆与草稿终态同生共死。
 */
@Service
public class ApprovalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExecutionService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String TOOL_SAVE_JD = "save_jd_analysis_to_kb";
    private static final String TOOL_MEMORY_CANDIDATE = "memory_candidate_create";

    private final ApprovalDraftService draftService;
    private final DocumentIngestService ingestService;
    private final MemoryService memoryService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalExecutionService(ApprovalDraftService draftService,
                                    DocumentIngestService ingestService,
                                    MemoryService memoryService) {
        this.draftService = draftService;
        this.ingestService = ingestService;
        this.memoryService = memoryService;
    }

    /**
     * 审批结果。
     *
     * @param status           草稿的<b>实际终态</b>：APPROVED 或 PARTIALLY_APPROVED
     * @param resultRef        单条工具产生的资源 ID；批量工具为 null（{@code result_ref} 装不下列表）
     * @param writtenMemoryIds 批量工具实际写入的记忆 ID，按 source_draft_id 回查——<b>持久化的事实</b>
     */
    public record ApprovalResult(String status, String resultRef, List<String> writtenMemoryIds) {
    }

    /**
     * 审批通过：校验 → 抢占审批权 → 执行副作用。
     *
     * @param selectedCandidateIds 批量工具：用户勾选的候选 ID；<b>null = 整批通过</b>。
     *                             单条工具必须为 null
     */
    @Transactional
    public ApprovalResult approve(String draftId, List<String> selectedCandidateIds) {
        String userId = UserContext.require();
        AgentApprovalDraftEntity draft = draftService.get(draftId);
        if (draft == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        // 已终态：返回<b>持久化</b>的结果，绝不回显本次请求的选择——
        // 后到的选择被静默丢弃，若把请求里的内容放进响应，就等于报告了一件没发生的事。
        if (!STATUS_PENDING.equals(draft.getStatus())) {
            return persistedResult(draft);
        }

        // ── 校验全部前置：任何失败都必须发生在状态被翻转之前 ──
        MemoryBatch plan = null;
        ApprovalOutcome outcome = ApprovalOutcome.APPROVED;
        String selectionJson = null;
        if (TOOL_MEMORY_CANDIDATE.equals(draft.getToolName())) {
            MemoryService.CandidateBatch batch = memoryService.parseBatch(draft.getPayloadJson());
            List<MemoryService.Candidate> selected =
                    memoryService.resolveSelection(batch, selectedCandidateIds);
            boolean wholeBatch = selected.size() == batch.candidates().size();
            outcome = wholeBatch ? ApprovalOutcome.APPROVED : ApprovalOutcome.PARTIALLY_APPROVED;
            selectionJson = wholeBatch ? null : writeJson(
                    selected.stream().map(MemoryService.Candidate::candidateId).toList());
            plan = new MemoryBatch(batch.source(), selected);
        } else if (TOOL_SAVE_JD.equals(draft.getToolName())) {
            if (selectedCandidateIds != null) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "该草稿不是批量审批，不接受候选项选择：" + draft.getToolName());
            }
            parseJdPayload(draft.getPayloadJson()); // 提前校验，别等到抢占之后才发现载荷坏了
        } else {
            log.error("审批执行遇到未实现的工具 tool={} draftId={}", draft.getToolName(), draftId);
            throw new ApiException(ErrorCode.BAD_REQUEST, "暂不支持审批该工具：" + draft.getToolName());
        }

        // ── 抢占审批权（锁行 + 状态检查）──
        if (!draftService.claimForApproval(draftId, outcome, selectionJson)) {
            return persistedResult(draftService.get(draftId));
        }

        // ── 副作用 ──
        if (plan != null) {
            List<String> memoryIds = memoryService.writeCandidates(
                    userId, draftId, plan.source(), plan.candidates());
            return new ApprovalResult(outcome.name(), null, memoryIds);
        }
        String documentId = saveJdAnalysis(userId, draft.getPayloadJson());
        draftService.recordResult(draftId, documentId);
        log.info("审批执行完成 draftId={} documentId={}", draftId, documentId);
        return new ApprovalResult(outcome.name(), documentId, List.of());
    }

    /** 兼容既有调用：单条工具无选择 */
    @Transactional
    public ApprovalResult approve(String draftId) {
        return approve(draftId, null);
    }

    /** 拒绝：只改状态，不产生任何副作用 */
    @Transactional
    public void reject(String draftId) {
        draftService.reject(draftId);
    }

    /** 已终态草稿的结果：全部来自持久化状态，不掺本次请求的输入 */
    private ApprovalResult persistedResult(AgentApprovalDraftEntity draft) {
        List<String> memoryIds = TOOL_MEMORY_CANDIDATE.equals(draft.getToolName())
                ? memoryService.findIdsBySourceDraft(draft.getId())
                : List.of();
        return new ApprovalResult(draft.getStatus(), draft.getResultRef(), memoryIds);
    }

    private record MemoryBatch(String source, List<MemoryService.Candidate> candidates) {
    }

    /** 校验 JD 载荷的形状，返回其内容；解析失败要能提前抛，不能等到副作用阶段 */
    private String parseJdPayload(String payloadJson) {
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷不是合法 JSON，无法执行");
        }
        String content = payload.path("content").asText("").strip();
        if (content.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷缺少 content，无法执行");
        }
        return content;
    }

    /**
     * 把 JD 分析结果作为一篇 Markdown 文档入队。
     * <p>
     * 用 {@code enqueue}（落 PENDING）而不是直接写 READY：审批的语义是「允许写入」，
     * 索引仍走正常的异步链路，不给审批开后门。
     */
    private String saveJdAnalysis(String userId, String payloadJson) {
        String content = parseJdPayload(payloadJson);
        String name = readJdDocumentName(payloadJson);
        // userId 来自认证上下文，不是载荷——载荷是模型写的
        KbDocumentEntity doc = ingestService.enqueue(
                new IngestCommand(userId, name, "MARKDOWN", "jd-analysis", content));
        return doc.getId();
    }

    private String readJdDocumentName(String payloadJson) {
        try {
            String name = mapper.readTree(payloadJson == null ? "{}" : payloadJson)
                    .path("name").asText("").strip();
            return name.isEmpty() ? "JD分析.md" : name;
        } catch (Exception e) {
            return "JD分析.md";
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化审批选择失败", e);
        }
    }
}

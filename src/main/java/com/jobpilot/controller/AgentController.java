package com.jobpilot.controller;

import com.jobpilot.agent.AgentRunner;
import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.agent.ApprovalExecutionService;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.memory.MemoryService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * I-2 Agent 与审批接口。
 * <p>
 * <b>请求体一律不含 {@code userId}</b>：身份只从 JWT 解析、经 {@code UserContext} 传递。
 * 客户端即使在 body 里塞一个 {@code userId}，也不会被绑定——record 里根本没这个字段。
 */
@RestController
@RequestMapping("/api/v1/agent")
@Validated
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 批量记忆候选工具名；决定审批是否需要候选清单与部分选择语义 */
    private static final String TOOL_MEMORY_CANDIDATE = "memory_candidate_create";

    private final AgentRunner agentRunner;
    private final ApprovalExecutionService approvalExecutionService;
    private final ApprovalDraftService approvalDraftService;
    private final MemoryService memoryService;

    public AgentController(AgentRunner agentRunner,
                           ApprovalExecutionService approvalExecutionService,
                           ApprovalDraftService approvalDraftService,
                           MemoryService memoryService) {
        this.agentRunner = agentRunner;
        this.approvalExecutionService = approvalExecutionService;
        this.approvalDraftService = approvalDraftService;
        this.memoryService = memoryService;
    }

    public record RunRequest(
            @NotBlank String message,
            /** 可选；为空时服务端新建会话 */
            String conversationId
    ) {
    }

    public record StepView(int iteration, String kind, String name, long durationMs, String status, String summary) {

        static StepView from(AgentRunner.Step step) {
            return new StepView(step.iteration(), step.kind(), step.name(),
                    step.durationMs(), step.status(), step.summary());
        }
    }

    public record RunResponse(
            String traceId,
            String conversationId,
            String answer,
            String finishReason,
            List<StepView> steps,
            /** 待审批草稿 ID；非空表示本轮因 HITL 结束，副作用尚未执行 */
            List<String> draftIds
    ) {

        static RunResponse from(AgentRunner.RunResult result) {
            return new RunResponse(result.traceId(), result.conversationId(), result.answer(),
                    result.finishReason().name(), result.steps().stream().map(StepView::from).toList(),
                    result.draftIds());
        }
    }

    /** 批量候选的展示视图：客户端要渲染「勾哪些」就必须能看到它们 */
    public record CandidateView(String candidateId, String type, String content,
                                BigDecimal confidence, String note) {

        static CandidateView from(MemoryService.Candidate candidate) {
            return new CandidateView(candidate.candidateId(), candidate.type().name(),
                    candidate.content(), candidate.confidence(), candidate.note());
        }
    }

    /**
     * 审批草稿视图。
     *
     * @param status           <b>实际终态</b>，不再硬编码——批量草稿可能是 PARTIALLY_APPROVED
     * @param writtenMemoryIds 批量工具实际写入的记忆 ID，按 {@code source_draft_id} 回查（持久化事实，
     *                         重复审批时返回的也是它，绝不复述本次请求里的选择）
     * @param candidates       批量草稿的候选清单；单条工具为空
     */
    public record ApprovalResponse(
            String draftId,
            String toolName,
            String status,
            String resultRef,
            List<String> writtenMemoryIds,
            List<CandidateView> candidates
    ) {

        static ApprovalResponse of(String draftId, AgentApprovalDraftEntity draft,
                                   List<String> writtenMemoryIds, List<CandidateView> candidates) {
            return new ApprovalResponse(draftId, draft.getToolName(), draft.getStatus(),
                    draft.getResultRef(), writtenMemoryIds, candidates);
        }
    }

    /** 审批请求体；整个 body 可省略，缺省即「整批通过」。仅批量草稿接受 selectedCandidateIds */
    public record ApproveRequest(List<String> selectedCandidateIds) {
    }

    /** 发起一次 Agent 对话；模型自行决定是否调用工具 */
    @PostMapping("/run")
    public ApiResponse<RunResponse> run(@RequestBody @Validated RunRequest request) {
        return ApiResponse.ok(RunResponse.from(
                agentRunner.run(new AgentRunner.RunRequest(request.conversationId(), request.message()))));
    }

    /**
     * 审批通过并执行副作用。
     * <p>
     * 重复审批幂等：已终态的草稿不会再次执行副作用，返回的是<b>持久化</b>的结果。
     * 批量草稿可带 {@code selectedCandidateIds} 做部分审批（PRD-FP-3.2）；缺省即整批通过。
     */
    @PostMapping("/approvals/{draftId}/approve")
    public ApiResponse<ApprovalResponse> approve(@PathVariable String draftId,
                                                 @RequestBody(required = false) ApproveRequest request) {
        ApprovalExecutionService.ApprovalResult result = approvalExecutionService.approve(
                draftId, request == null ? null : request.selectedCandidateIds());
        return ApiResponse.ok(new ApprovalResponse(
                draftId, null, result.status(), result.resultRef(), result.writtenMemoryIds(), List.of()));
    }

    /** 拒绝：只改状态，不产生任何副作用 */
    @PostMapping("/approvals/{draftId}/reject")
    public ApiResponse<ApprovalResponse> reject(@PathVariable String draftId) {
        approvalExecutionService.reject(draftId);
        // 回读实际状态而不是硬编码 REJECTED：草稿若已是终态，reject 是幂等 no-op，
        // 硬编码会让响应谎报状态被改成了 REJECTED
        return ApiResponse.ok(responseOf(draftId));
    }

    /**
     * 查询草稿状态；跨租户访问与「不存在」不可区分（租户拦截器过滤，查不到即 404）。
     * <p>
     * 批量草稿会带上候选清单——否则客户端无从渲染「勾选哪些」，这个接口对部分审批就是不可用的。
     */
    @GetMapping("/approvals/{draftId}")
    public ApiResponse<ApprovalResponse> approval(@PathVariable String draftId) {
        return ApiResponse.ok(responseOf(draftId));
    }

    private ApprovalResponse responseOf(String draftId) {
        AgentApprovalDraftEntity draft = approvalDraftService.get(draftId);
        if (draft == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        List<String> writtenMemoryIds = TOOL_MEMORY_CANDIDATE.equals(draft.getToolName())
                ? memoryService.findIdsBySourceDraft(draftId)
                : List.of();
        return ApprovalResponse.of(draftId, draft, writtenMemoryIds, candidatesOf(draft));
    }

    /**
     * 草稿的候选清单。
     * <p>
     * 解析失败时返回空清单而不是让 GET 失败：草稿可能是旧版本结构，而查询接口的职责是
     * 「如实展示当前状态」。真正写入时仍会 fail-closed 地重新校验一次载荷。
     */
    private List<CandidateView> candidatesOf(AgentApprovalDraftEntity draft) {
        if (!TOOL_MEMORY_CANDIDATE.equals(draft.getToolName())) {
            return List.of();
        }
        try {
            return memoryService.parseBatch(draft.getPayloadJson()).candidates().stream()
                    .map(CandidateView::from).toList();
        } catch (ApiException e) {
            log.warn("草稿候选解析失败，返回空清单 draftId={}", draft.getId(), e);
            return List.of();
        }
    }
}

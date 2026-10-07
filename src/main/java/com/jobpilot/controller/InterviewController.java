package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.interview.InterviewService;
import com.jobpilot.security.UserContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 模拟面试（BRD US-3）。
 * <p>
 * <b>请求体一律不含 {@code userId}</b>：身份只从 JWT 解析、经 {@code UserContext} 传递。
 * 会话按公司档位起步，逐轮作答；走完三轮弧线或主动 finish 后出报告，弱点候选走既有审批接口。
 */
@RestController
@RequestMapping("/api/v1/interview")
@Validated
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    public record StartRequest(
            @NotBlank String company,
            String position,
            /** 公司档位（jobpilot.interview.tiers 的键）；缺省用默认档位 */
            String tier,
            /** 覆盖难度（EASY/MEDIUM/HARD）；缺省用档位预设 */
            String difficultyOverride
    ) {
    }

    public record AnswerRequest(@NotBlank String answer) {
    }

    public record TurnResponse(String sessionId, String phase, int round, int totalRounds,
                               String status, String question, boolean finished) {

        static TurnResponse from(InterviewService.TurnResult r) {
            return new TurnResponse(r.sessionId(), r.phase(), r.round(), r.totalRounds(),
                    r.status(), r.question(), r.finished());
        }
    }

    public record ReportResponse(String sessionId, InterviewService.InterviewReport report,
                                 String draftId, List<String> candidateIds, String status) {

        static ReportResponse from(InterviewService.ReportResult r) {
            return new ReportResponse(r.sessionId(), r.report(), r.draftId(), r.candidateIds(), r.status());
        }
    }

    /** 开一场面试并返回第一题 */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TurnResponse> start(@RequestBody @Validated StartRequest request) {
        return ApiResponse.ok(TurnResponse.from(interviewService.start(new InterviewService.StartCommand(
                UserContext.require(), request.company(), request.position(),
                request.tier(), request.difficultyOverride()))));
    }

    /** 提交一条回答，返回下一题（或 {@code finished=true}，此时去取报告） */
    @PostMapping("/sessions/{id}/answers")
    public ApiResponse<TurnResponse> answer(@PathVariable String id,
                                            @RequestBody @Validated AnswerRequest request) {
        return ApiResponse.ok(TurnResponse.from(interviewService.answer(id, request.answer())));
    }

    /** 提前收尾：按已有问答出报告 + 落弱点草稿 */
    @PostMapping("/sessions/{id}/finish")
    public ApiResponse<ReportResponse> finish(@PathVariable String id) {
        return ApiResponse.ok(ReportResponse.from(interviewService.finish(id)));
    }

    /** 取报告；未收尾则 404 */
    @GetMapping("/sessions/{id}/report")
    public ApiResponse<ReportResponse> report(@PathVariable String id) {
        return ApiResponse.ok(ReportResponse.from(interviewService.report(id)));
    }

    /** 会话详情（状态 + 全部消息） */
    @GetMapping("/sessions/{id}")
    public ApiResponse<InterviewService.DetailView> detail(@PathVariable String id) {
        return ApiResponse.ok(interviewService.detail(id));
    }

    /** 当前租户的面试列表 */
    @GetMapping("/sessions")
    public ApiResponse<List<InterviewService.SessionSummary>> list(
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return ApiResponse.ok(interviewService.list(limit));
    }
}

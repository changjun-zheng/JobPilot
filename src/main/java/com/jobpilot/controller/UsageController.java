package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.usage.UsageSummaryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 用量查询（I-3c，PRD-FP-10「用量数据属于用户可见信息」）。
 * <p>
 * 只做归集结果的读取；配额与限流是后续迭代（阈值待真实数据校准）。
 * 租户范围由数据访问层强制注入，Controller 不接收也不转发任何身份参数。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageSummaryService summaryService;

    public UsageController(UsageSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** 当前租户的用量汇总；{@code from}/{@code to} 为 yyyy-MM-dd（含两端），缺省为全部历史 */
    @GetMapping("/summary")
    public ApiResponse<UsageSummaryService.UsageSummary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(summaryService.summary(from, to));
    }
}

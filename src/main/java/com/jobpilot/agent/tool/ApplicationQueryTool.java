package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.application.ApplicationService;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.domain.ApplicationStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 查询投递记录（PRD-FP-2.2，无副作用）。
 *
 * <p>只返回当前租户的记录——租户条件由拦截器注入，模型无从指定。
 */
@Component
public class ApplicationQueryTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "status": {"type": "string",  "description": "按状态过滤：WISHLIST/APPLIED/SCREENING/INTERVIEWING/OFFER/REJECTED/WITHDRAWN"},
                "from":   {"type": "string",  "description": "投递日期下界 yyyy-MM-dd（含当天）"},
                "to":     {"type": "string",  "description": "投递日期上界 yyyy-MM-dd（含当天）"},
                "limit":  {"type": "integer", "description": "返回条数上限，默认 50，最大 200"}
              }
            }""";

    private final ApplicationService applicationService;

    public ApplicationQueryTool(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public String name() {
        return "application_query";
    }

    @Override
    public String description() {
        return "查询用户的投递记录，可按状态与投递日期范围过滤。"
                + "需要知道某条记录的 ID（用于更新）时也用它。";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ApprovalMode approvalMode() {
        return ApprovalMode.AUTO;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson) {
        return AgentToolSupport.guarded(context.callId(), name(), () -> {
            JsonNode args = AgentToolSupport.parse(argumentsJson);
            AgentToolSupport.warnForgedUserId(args, name(), context);
            // 未知状态显式报错，不静默返回空列表——空列表会让模型以为「用户没有记录」
            String rawStatus = AgentToolSupport.optionalText(args, "status");
            ApplicationStatus status = rawStatus == null ? null : ApplicationStatus.parse(rawStatus);
            LocalDate from = AgentToolSupport.optionalDate(args, "from");
            LocalDate to = AgentToolSupport.optionalDate(args, "to");
            int limit = args.path("limit").asInt(0);

            List<ApplicationEntity> items = applicationService
                    .query(new ApplicationService.Query(status, from, to, limit));
            if (items.isEmpty()) {
                return "没有符合条件的投递记录。";
            }
            StringBuilder sb = new StringBuilder("共 " + items.size() + " 条投递记录：\n");
            for (ApplicationEntity item : items) {
                sb.append("- ID=").append(item.getId())
                        .append(" | ").append(item.getCompany())
                        .append(" · ").append(item.getPosition())
                        .append(" | 状态 ").append(item.getStatus());
                if (item.getAppliedAt() != null) {
                    sb.append(" | 投递日期 ").append(item.getAppliedAt());
                }
                if (item.getNotes() != null && !item.getNotes().isBlank()) {
                    sb.append(" | 备注：").append(item.getNotes());
                }
                sb.append('\n');
            }
            return sb.toString();
        });
    }
}

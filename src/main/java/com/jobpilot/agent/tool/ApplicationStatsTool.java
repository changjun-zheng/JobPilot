package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.application.ApplicationService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 投递统计（PRD-FP-2.2，无副作用）。
 *
 * <p>七种状态全部零填充，模型不必自己推断「哪些状态是 0」；
 * 各分组之和恒等于 total，两者不会互相矛盾。
 */
@Component
public class ApplicationStatsTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "from": {"type": "string", "description": "投递日期下界 yyyy-MM-dd（含当天），可选"},
                "to":   {"type": "string", "description": "投递日期上界 yyyy-MM-dd（含当天），可选"}
              }
            }""";

    private final ApplicationService applicationService;

    public ApplicationStatsTool(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public String name() {
        return "application_stats";
    }

    @Override
    public String description() {
        return "按状态统计用户的投递数量，可按投递日期范围限定。"
                + "用户问「我总共投了多少家」「有多少在面试」时使用。";
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
            LocalDate from = AgentToolSupport.optionalDate(args, "from");
            LocalDate to = AgentToolSupport.optionalDate(args, "to");

            ApplicationService.Stats stats = applicationService.stats(from, to);
            StringBuilder sb = new StringBuilder("投递总数：" + stats.total() + "\n按状态：\n");
            for (Map.Entry<String, Long> entry : stats.byStatus().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
            return sb.toString();
        });
    }
}

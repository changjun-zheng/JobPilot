package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.application.ApplicationService;
import com.jobpilot.domain.ApplicationEntity;
import org.springframework.stereotype.Component;

/**
 * 创建投递记录（PRD-FP-2.2，低风险业务写入）。
 *
 * <p><b>为什么它不需要 HITL</b>：AGENTS.md 要求走审批的是「会写入<b>长期记忆或知识库</b>的工具」
 * ——那是用户的私密材料沉淀，误写会污染检索结果。投递记录属于低风险业务数据，用户可随时改删，
 * PRD-FP-2.2 明确标注「无需审批」。别把这条规则扩大成「凡写入都要审批」，那会让对话寸步难行。
 *
 * <p>租户只来自 {@link ToolExecutionContext}，参数里的 {@code userId} 一律忽略。
 */
@Component
public class ApplicationCreateTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "company":      {"type": "string",  "description": "公司名称"},
                "position":     {"type": "string",  "description": "岗位名称"},
                "status":       {"type": "string",  "description": "WISHLIST/APPLIED/SCREENING/INTERVIEWING/OFFER/REJECTED/WITHDRAWN"},
                "appliedAt":    {"type": "string",  "description": "投递日期 yyyy-MM-dd；状态非 WISHLIST 时必填"},
                "source":       {"type": "string",  "description": "来源或链接，可选"},
                "jdDocumentId": {"type": "string",  "description": "关联的 JD 文档 ID，可选"},
                "notes":        {"type": "string",  "description": "备注，可选"}
              },
              "required": ["company", "position", "status"]
            }""";

    private final ApplicationService applicationService;

    public ApplicationCreateTool(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public String name() {
        return "application_create";
    }

    @Override
    public String description() {
        return "创建一条投递记录。用户说「我投了某公司某岗位」「帮我记一下」时使用。"
                + "创建前建议先用 application_query 查是否已存在同一岗位，避免重复录入。";
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
            ApplicationEntity created = applicationService.create(new ApplicationService.CreateCommand(
                    context.userId(),
                    AgentToolSupport.requiredText(args, "company"),
                    AgentToolSupport.requiredText(args, "position"),
                    AgentToolSupport.requiredText(args, "status"),
                    AgentToolSupport.optionalDate(args, "appliedAt"),
                    AgentToolSupport.optionalText(args, "source"),
                    AgentToolSupport.optionalText(args, "jdDocumentId"),
                    AgentToolSupport.optionalText(args, "notes")));
            return "已创建投递记录（ID=" + created.getId() + "）："
                    + created.getCompany() + " · " + created.getPosition()
                    + "，状态 " + created.getStatus()
                    + (created.getAppliedAt() == null ? "" : "，投递日期 " + created.getAppliedAt());
        });
    }
}

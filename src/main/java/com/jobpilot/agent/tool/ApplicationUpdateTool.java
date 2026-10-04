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
 * 更新投递记录（PRD-FP-2.2，低风险业务写入，无需审批——理由见 {@link ApplicationCreateTool}）。
 *
 * <p><b>改别人的记录会失败，且必须失败</b>：租户拦截器把 {@code user_id} 注入 WHERE，
 * 目标不属于本租户时影响 0 行；服务层把这种情况转成 {@code NOT_FOUND}。
 * 若不检查影响行数就返回成功，模型会告诉用户「已更新」而数据一字未动。
 *
 * <p>字段缺省 = 不修改；字符串传空串 = 清空（例如误关联的 JD 可以解除）。
 */
@Component
public class ApplicationUpdateTool implements AgentTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "id":           {"type": "string",  "description": "要更新的投递记录 ID"},
                "company":      {"type": "string",  "description": "公司名称"},
                "position":     {"type": "string",  "description": "岗位名称"},
                "status":       {"type": "string",  "description": "WISHLIST/APPLIED/SCREENING/INTERVIEWING/OFFER/REJECTED/WITHDRAWN"},
                "appliedAt":    {"type": "string",  "description": "投递日期 yyyy-MM-dd"},
                "source":       {"type": "string",  "description": "来源或链接；传空串表示清空"},
                "jdDocumentId": {"type": "string",  "description": "关联的 JD 文档 ID；传空串表示解除关联"},
                "notes":        {"type": "string",  "description": "备注；传空串表示清空"}
              },
              "required": ["id"]
            }""";

    private final ApplicationService applicationService;

    public ApplicationUpdateTool(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public String name() {
        return "application_update";
    }

    @Override
    public String description() {
        return "更新一条投递记录，常用于推进状态（如「那家已经面试了」→ INTERVIEWING）。"
                + "不确定 ID 时先用 application_query 查出来。";
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
            String id = AgentToolSupport.requiredText(args, "id");
            // 传了哪些字段就改哪些：null = 不修改（空串在服务层解释为「清空」）
            ApplicationEntity updated = applicationService.update(id, new ApplicationService.Patch(
                    AgentToolSupport.optionalText(args, "company"),
                    AgentToolSupport.optionalText(args, "position"),
                    AgentToolSupport.optionalText(args, "status"),
                    AgentToolSupport.optionalDate(args, "appliedAt"),
                    presentValue(args, "source"),
                    presentValue(args, "jdDocumentId"),
                    presentValue(args, "notes")));
            return "已更新投递记录（ID=" + updated.getId() + "）："
                    + updated.getCompany() + " · " + updated.getPosition()
                    + "，状态 " + updated.getStatus();
        });
    }

    /**
     * 区分「字段没传」与「传了空串」。
     * <p>
     * {@code optionalText} 会把两者都变成 null——对可清空字段那样是错的：
     * 用户说「把这个 JD 取消关联」时模型传 {@code ""}，服务层必须收到空串才能清空，
     * 收到 null 只会理解成「不修改」，于是永远解不掉。
     */
    private static String presentValue(JsonNode args, String field) {
        return args.has(field) ? args.path(field).asText("") : null;
    }
}

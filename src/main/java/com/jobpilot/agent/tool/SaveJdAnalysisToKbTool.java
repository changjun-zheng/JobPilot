package com.jobpilot.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 JD 分析结果存入知识库（PRD-FP-2.2，<b>写入知识库，必须走 HITL</b>）。
 *
 * <h3>它绝不直接写入</h3>
 * 即使 {@code approvalMode} 是 AUTO（理论上不会，本工具固定 REQUIRE_APPROVAL），
 * 实现里也只落草稿、不调 {@code enqueue}。模型<b>没有</b>绕过审批的备用工具——
 * 这是 AGENTS.md 的硬约束：会写入长期记忆或知识库的工具必须走 HITL。
 * <p>
 * 重复请求由 {@link ApprovalDraftService#createDraft} 的唯一键兜住：模型一次 run 里
 * 调两次同样的写入，只会得到同一个 draftId，用户审批一次即可。
 */
@Component
public class SaveJdAnalysisToKbTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SaveJdAnalysisToKbTool.class);

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name":    {"type": "string", "description": "文档名，默认 JD分析.md"},
                "content": {"type": "string", "description": "要保存的 JD 分析全文（Markdown）"}
              },
              "required": ["content"]
            }""";

    private final ApprovalDraftService draftService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SaveJdAnalysisToKbTool(ApprovalDraftService draftService) {
        this.draftService = draftService;
    }

    @Override
    public String name() {
        return "save_jd_analysis_to_kb";
    }

    @Override
    public String description() {
        return "把一份 JD 分析结果保存到用户知识库。会产生写入副作用，必须经用户审批后才真正执行。";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ApprovalMode approvalMode() {
        return ApprovalMode.REQUIRE_APPROVAL;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson) {
        JsonNode args;
        try {
            args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception e) {
            return ToolExecutionResult.failed(context.callId(), name(),
                    "参数不是合法 JSON。", ToolErrorCode.INVALID_ARGUMENTS);
        }
        String content = args.path("content").asText("").strip();
        if (content.isEmpty()) {
            return ToolExecutionResult.failed(context.callId(), name(),
                    "缺少必填参数 content。", ToolErrorCode.INVALID_ARGUMENTS);
        }
        if (args.has("userId")) {
            log.warn("save_jd_analysis_to_kb 参数中出现 userId，已忽略（租户只来自认证上下文）traceId={}",
                    context.traceId());
        }
        String documentName = args.path("name").asText("JD分析.md");

        // 租户唯一来源：上下文。载荷里没有也不该有 userId。
        String draftId = draftService.createDraft(
                context.userId(), context.traceId(), context.conversationId(),
                name(), payloadJson(documentName, content));

        return ToolExecutionResult.pendingApproval(context.callId(), name(),
                "已生成待审批草稿（ID=" + draftId + "）。保存到知识库需要用户确认，本轮对话到此结束，"
                        + "请告知用户前往审批中心确认。",
                Map.of("draftId", draftId));
    }

    /**
     * 组装审批载荷。用 Jackson 而不是手工拼 JSON 字符串：
     * 手工转义漏一个字符就是载荷被截断或字段错位，而 content 完全由模型给出。
     * <p>
     * 用 {@link LinkedHashMap} 而非 {@code Map.of}：草稿的幂等键是
     * {@code sha256(...|payloadJson)}，而 {@code Map.of} 的迭代顺序**每次 JVM 启动随机**，
     * 同一逻辑载荷会算出不同字符串，跨重启去重就静默失效——不报错，只是不再去重。
     */
    private String payloadJson(String documentName, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", documentName);
        payload.put("content", content);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("组装审批载荷失败", e);
        }
    }
}

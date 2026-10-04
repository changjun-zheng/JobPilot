package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.memory.MemoryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成长期记忆候选（PRD-FP-2.2，<b>必须进入 HITL</b>）。
 *
 * <h3>它只落草稿，绝不写记忆</h3>
 * 与 {@code save_jd_analysis_to_kb} 同一纪律：会写入长期记忆的工具必须经用户确认，
 * 模型<b>没有</b>绕过审批的备用工具。这也是「未经确认的模型猜测不得入库」那条 PRD 约束的
 * <b>结构性</b>保证——猜测最多停在草稿里，人勾选才落库。
 *
 * <h3>为什么是批量 + 可部分选择</h3>
 * PRD-FP-3.2 对「面试弱点写入 Memory」的规定是「整批，可部分选择」：一次面试复盘往往同时
 * 产出几条弱点与计划，逐条审批太碎；而全批通过又太粗。所以草稿一次装 N 条候选，
 * 审批时由用户勾选子集（见 {@code ApprovalExecutionService}）。
 *
 * <h3>candidateId 由服务端生成</h3>
 * 不接受模型自报的 ID——审批时正是靠它标识「用户勾了哪几条」，模型可控就会撞号或伪造。
 */
@Component
public class MemoryCandidateCreateTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "source": {"type": "string", "description": "来源标签，如「面试评估」「简历复盘」"},
                "candidates": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 20,
                  "description": "记忆候选；每条都是短小条目",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type":       {"type": "string", "enum": ["JOB_PREFERENCE","INTERVIEW_WEAKNESS","PREPARATION_PLAN","EXPRESSION_ISSUE"],
                                     "description": "JOB_PREFERENCE 求职偏好 / INTERVIEW_WEAKNESS 面试弱点 / PREPARATION_PLAN 准备计划 / EXPRESSION_ISSUE 表达问题"},
                      "content":    {"type": "string", "description": "记忆正文，不超过 512 字。长篇材料请改用 save_jd_analysis_to_kb 存入知识库"},
                      "confidence": {"type": "number", "minimum": 0, "maximum": 1, "description": "可选，你对这条判断的把握"},
                      "note":       {"type": "string", "description": "可选，依据或补充说明，不超过 255 字"}
                    },
                    "required": ["type", "content"]
                  }
                }
              },
              "required": ["source", "candidates"]
            }""";

    private final MemoryService memoryService;
    private final ApprovalDraftService draftService;

    public MemoryCandidateCreateTool(MemoryService memoryService, ApprovalDraftService draftService) {
        this.memoryService = memoryService;
        this.draftService = draftService;
    }

    @Override
    public String name() {
        return "memory_candidate_create";
    }

    @Override
    public String description() {
        return "把面试复盘、简历分析中值得长期记住的偏好、弱点或计划提炼成候选条目，"
                + "提交给用户确认后写入长期记忆。**必须经用户审批，不会立即生效**。"
                + "只放短小条目；长篇材料用 save_jd_analysis_to_kb 存入知识库。";
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
        return AgentToolSupport.guardedResult(context.callId(), name(), () -> {
            JsonNode args = AgentToolSupport.parse(argumentsJson);
            AgentToolSupport.warnForgedUserId(args, name(), context);

            String source = AgentToolSupport.requiredText(args, "source");
            JsonNode candidatesNode = args.path("candidates");
            if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
                throw new IllegalArgumentException("候选清单不能为空");
            }
            String payloadJson = buildPayloadJson(source, candidatesNode);
            // 用执行侧那份校验规则再验一遍：类型未知、正文超长、条数越界都在这里被挡下，
            // 且错误消息与审批时完全一致（单一校验源，不做第二个实现）
            memoryService.parseBatch(payloadJson);

            String draftId = draftService.createDraft(
                    context.userId(), context.traceId(), context.conversationId(), name(), payloadJson);

            return ToolExecutionResult.pendingApproval(context.callId(), name(),
                    "已生成 " + candidatesNode.size() + " 条长期记忆候选（草稿 ID=" + draftId + "），"
                            + "**尚未写入**。需要用户确认保留哪几条，本轮到此结束。",
                    Map.of("draftId", draftId));
        });
    }

    /**
     * 组装草稿载荷。
     * <p>
     * 用 {@link LinkedHashMap} 而非 {@code Map.of}：草稿的幂等键是
     * {@code sha256(...|payloadJson)}，而 {@code Map.of} 的迭代顺序**每次 JVM 启动随机**，
     * 同一逻辑载荷会算出不同字符串，跨重启去重就静默失效了。LinkedHashMap 的顺序是插入序。
     */
    private String buildPayloadJson(String source, JsonNode candidatesNode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", source);
        List<Map<String, Object>> candidates = new ArrayList<>(candidatesNode.size());
        for (int i = 0; i < candidatesNode.size(); i++) {
            JsonNode node = candidatesNode.get(i);
            Map<String, Object> candidate = new LinkedHashMap<>();
            // ID 由服务端按位置生成，不接受模型自报
            candidate.put("candidateId", "c" + (i + 1));
            candidate.put("type", node.path("type").asText(""));
            candidate.put("content", node.path("content").asText(""));
            if (node.hasNonNull("confidence")) {
                candidate.put("confidence", node.path("confidence").asDouble());
            }
            if (node.hasNonNull("note")) {
                candidate.put("note", node.path("note").asText(""));
            }
            candidates.add(candidate);
        }
        root.put("candidates", candidates);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("组装记忆候选载荷失败", e);
        }
    }
}

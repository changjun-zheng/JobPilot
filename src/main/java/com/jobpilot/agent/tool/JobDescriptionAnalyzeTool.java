package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.agent.AgentTool;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.usage.UsageScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JD 分析的<b>证据供给</b>（PRD-FP-2.3，无副作用）。
 *
 * <h3>它不产出八项分析字段，名字比实际做的事大</h3>
 * PRD-FP-2.3 要求输出技术栈、匹配度、优势、差距等八项。产出这些需要一次 LLM 推理，而本工具
 * <b>刻意不在内部再调一次模型</b>：那会让延迟翻倍、token 预算变成两层、且用 qwen2.5:3b 做
 * 嵌套 JSON 抽取很脆。
 * <p>
 * 因此分工是：<b>本工具只提供有依据的事实</b>（JD 原文里明确写了的字段 + 检索到的个人材料及引用），
 * 八项分析由外层 ReAct 模型在 system prompt 约束下完成。这样每个差距都能挂上真实引用，
 * 而不是模型凭印象编一个「匹配度 85%」。
 */
@Component
public class JobDescriptionAnalyzeTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(JobDescriptionAnalyzeTool.class);

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "jobDescription": {"type": "string", "description": "岗位描述原文"},
                "focus":         {"type": "string", "description": "可选，指定重点考察的方向"}
              },
              "required": ["jobDescription"]
            }""";

    /** 关键要求维度的关键词；用于从用户材料里定向取证，命中与否都如实回报 */
    private static final List<String> REQUIREMENT_KEYWORDS = List.of(
            "Java", "Spring", "Spring Boot", "MySQL", "Redis", "Kafka", "Docker",
            "Kubernetes", "分布式", "并发", "性能", "中间件", "微服务", "算法", "数据结构");

    private final KnowledgeRetrievalService retrievalService;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobDescriptionAnalyzeTool(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "job_description_analyze";
    }

    @Override
    public String description() {
        return "分析一份 JD：解析其中明确写出的要求，并检索用户材料作为匹配依据。"
                + "返回的是有引用支撑的事实，分析结论由你基于这些事实给出。";
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
        JsonNode args;
        try {
            args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception e) {
            return ToolExecutionResult.failed(context.callId(), name(),
                    "参数不是合法 JSON。", ToolErrorCode.INVALID_ARGUMENTS);
        }
        String jobDescription = args.path("jobDescription").asText("").strip();
        if (jobDescription.isEmpty()) {
            return ToolExecutionResult.failed(context.callId(), name(),
                    "缺少必填参数 jobDescription。", ToolErrorCode.INVALID_ARGUMENTS);
        }
        if (args.has("userId")) {
            log.warn("job_description_analyze 参数中出现 userId，已忽略（租户只来自认证上下文）traceId={}",
                    context.traceId());
        }

        List<String> requirements = extractRequirements(jobDescription);
        // 逐个关键要求定向检索用户材料；租户只来自上下文
        List<RequirementEvidence> evidences = new ArrayList<>();
        for (String requirement : requirements) {
            RetrievalResult result = retrievalService.search(
                    new RetrievalQuery(context.userId(), requirement, 3, null), UsageScenario.AGENT);
            if (!result.items().isEmpty()) {
                evidences.add(new RequirementEvidence(requirement, result.items()));
            }
        }
        return ToolExecutionResult.success(context.callId(), name(),
                buildEvidenceText(jobDescription, requirements, evidences), evidences,
                evidences.stream().flatMap(e -> e.chunks().stream().map(RetrievedChunk::citation)).toList());
    }

    /** JD 里明确出现的技术关键词；不猜测，JD 没写就是没写（PRD-FP-2.3：无法确定的字段标记为未知） */
    private List<String> extractRequirements(String jobDescription) {
        List<String> found = new ArrayList<>();
        for (String keyword : REQUIREMENT_KEYWORDS) {
            if (jobDescription.contains(keyword)) {
                found.add(keyword);
            }
        }
        return found;
    }

    private String buildEvidenceText(String jobDescription, List<String> requirements,
                                     List<RequirementEvidence> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("【JD 原文】\n").append(jobDescription).append("\n\n");
        sb.append("【JD 中明确出现的要求关键词】\n");
        if (requirements.isEmpty()) {
            sb.append("（未识别到已知技术关键词，请直接依据原文判断，不要臆测）\n");
        } else {
            sb.append(String.join("、", requirements)).append('\n');
        }
        sb.append("\n【个人材料中的对应证据】\n");
        if (evidences.isEmpty()) {
            sb.append("（知识库中未检索到与该 JD 相关的材料，匹配度无从谈起，应如实说明）\n");
        } else {
            for (RequirementEvidence evidence : evidences) {
                sb.append("· ").append(evidence.requirement()).append("：\n");
                for (RetrievedChunk chunk : evidence.chunks()) {
                    sb.append("  [").append(chunk.citation().display()).append("] ")
                            .append(chunk.text().strip()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 一个要求关键词命中的用户材料；作为结构化数据留给应用层，不进对话历史 */
    public record RequirementEvidence(String requirement, List<RetrievedChunk> chunks) {
    }
}

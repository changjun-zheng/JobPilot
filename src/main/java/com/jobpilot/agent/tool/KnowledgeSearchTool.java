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

import java.util.List;

/**
 * 检索用户知识库（PRD-FP-2.2，无副作用）。
 * <p>
 * <b>租户隔离的关键落点</b>：{@code query} 只取自上下文，模型参数里的任何 userId 一律忽略。
 * 检索服务自身还会再校验一次 {@code UserContext} 与 {@code query.userId()} 是否一致（双保险）。
 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query":       {"type": "string", "description": "检索关键词或问题文本"},
                "topK":        {"type": "integer", "description": "返回条数，缺省由服务端配置决定"},
                "docType":     {"type": "string", "description": "可选，限定 MARKDOWN 或 PLAIN_TEXT"}
              },
              "required": ["query"]
            }""";

    private final KnowledgeRetrievalService retrievalService;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeSearchTool(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "检索当前用户的求职知识库（简历、项目经历等）。回答任何涉及用户自身经历的问题前必须先调用。";
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
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) {
            return ToolExecutionResult.failed(context.callId(), name(),
                    "缺少必填参数 query。", ToolErrorCode.INVALID_ARGUMENTS);
        }
        // 模型若塞了 userId，忽略并留痕：这是模型编造的输入，不是授权凭据
        if (args.has("userId")) {
            log.warn("knowledge_search 参数中出现 userId，已忽略（租户只来自认证上下文）traceId={}",
                    context.traceId());
        }
        int topK = args.path("topK").asInt(0);
        String docType = args.path("docType").asText(null);

        // 租户唯一来源：上下文，不是参数
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(context.userId(), query, topK, docType), UsageScenario.AGENT);
        List<RetrievedChunk> items = result.items();
        if (items.isEmpty()) {
            return ToolExecutionResult.success(context.callId(), name(),
                    result.degraded()
                            ? "知识库中没有检索到相关证据（关键词降级模式）。"
                            : "知识库中没有检索到相关证据。",
                    List.of());
        }
        return ToolExecutionResult.success(context.callId(), name(),
                buildEvidenceText(items, result), items,
                items.stream().map(RetrievedChunk::citation).toList());
    }

    /**
     * 编号证据文本，格式与 {@code RagAskService} 一致：[n] [文档 > 章节] 原文。
     * <p>
     * 编号是刻意的：prompt 里用 [n] 引用，模型就不容易虚构文档名——这是 I-0 就定下的不变量。
     */
    private String buildEvidenceText(List<RetrievedChunk> items, RetrievalResult result) {
        StringBuilder sb = new StringBuilder("【检索证据】\n");
        for (int i = 0; i < items.size(); i++) {
            RetrievedChunk chunk = items.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append('[').append(chunk.citation().display()).append("] ")
                    .append(chunk.text().strip()).append('\n');
        }
        if (result.degraded()) {
            sb.append("\n（本次为关键词降级检索，向量库不可用）");
        }
        return sb.toString();
    }
}

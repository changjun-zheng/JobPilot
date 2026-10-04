package com.jobpilot.knowledge;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 引用问答（ARCHITECTURE.md §4.2 尾段 / PRD-FP-1.4）：
 * 检索 → 证据不足直接拒答（不强行走模型）→ 有证据则带引用约束生成回答。
 * 引用列表由服务端从命中 Chunk 组装，模型只负责正文，杜绝虚构文档名。
 * <p>
 * I-3c 起改走 {@link ChatPort#chat}（原 {@code complete} 已删）：用量计量需要
 * {@link ChatCompletion#usage()}，String 返回值装不下。拒答路径不触碰模型，
 * 因此也**不产生 LLM 计量行**——「没调用就没有消耗」是计量与计费共同的前提。
 */
@Service
public class RagAskService {

    private static final String SYSTEM_PROMPT = """
            你是 JobPilot 求职知识库助手。规则：
            1. 只能依据【证据】回答，不得编造；
            2. 引用证据时使用其编号，形如 [1]、[2]；
            3. 证据不足以回答时，直接回答"知识库中缺少足够依据"，不要猜测；
            4. 回答使用简体中文，简洁分点。
            """;

    private final KnowledgeRetrievalService retrievalService;
    private final ChatPort chatPort;
    private final UsageRecorder usageRecorder;

    public RagAskService(KnowledgeRetrievalService retrievalService, ChatPort chatPort,
                         UsageRecorder usageRecorder) {
        this.retrievalService = retrievalService;
        this.chatPort = chatPort;
        this.usageRecorder = usageRecorder;
    }

    public record AskAnswer(
            String answer,
            List<RetrievedChunk> evidence,
            RetrievalResult retrieval
    ) {
    }

    public AskAnswer ask(String userId, String question, int topK, String docType) {
        RetrievalResult retrieval = retrievalService.search(
                new RetrievalQuery(userId, question, topK, docType), UsageScenario.ASK);
        List<RetrievedChunk> evidence = retrieval.items();
        if (evidence.isEmpty()) {
            String answer = retrieval.degraded()
                    ? "知识库中没有检索到相关证据（关键词降级模式），无法回答该问题。"
                    : "知识库中缺少足够依据，无法回答该问题。";
            return new AskAnswer(answer, evidence, retrieval);
        }
        String userPrompt = buildPrompt(question, evidence);
        ChatCompletion completion = chatPort.chat(new ChatRequest(
                List.of(new AgentMessage.System(SYSTEM_PROMPT), new AgentMessage.User(userPrompt)),
                List.of(), null, null, null, null));
        usageRecorder.recordLlmCall(userId, UsageScenario.ASK, completion.model(),
                completion.usage(), true, null);
        return new AskAnswer(completion.content(), evidence, retrieval);
    }

    private String buildPrompt(String question, List<RetrievedChunk> evidence) {
        StringBuilder sb = new StringBuilder("【证据】\n");
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append('[').append(chunk.citation().display()).append("] ")
                    .append(chunk.text().strip()).append('\n');
        }
        sb.append("\n【问题】\n").append(question);
        return sb.toString();
    }
}

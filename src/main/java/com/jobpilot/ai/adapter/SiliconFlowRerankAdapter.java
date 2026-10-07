package com.jobpilot.ai.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobpilot.ai.RerankPort;
import com.jobpilot.config.RerankProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow 的 {@code POST /rerank} 适配器（Jina/Cohere 风格的 cross-encoder 重排）。
 * <p>
 * 用 {@link RestClient} 手写 HTTP（同 {@link ChromaVectorStoreAdapter} 的写法）——重排不是 OpenAI 的
 * chat/embedding 协议，不套 Spring AI 的模型抽象。失败抛运行时异常，由 {@code KnowledgeRetrievalService}
 * 决定降级（回退向量序）。
 */
@Component
public class SiliconFlowRerankAdapter implements RerankPort {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public SiliconFlowRerankAdapter(RerankProperties props, ClientHttpRequestFactory requestFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.apiKey = props.apiKey() == null ? "" : props.apiKey();
        this.model = props.model();
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return List.of();
        }
        int top = topN <= 0 ? documents.size() : Math.min(topN, documents.size());
        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", documents,
                "top_n", top);

        RerankResponse response = restClient.post()
                .uri("/rerank")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            return List.of();
        }
        List<RerankHit> hits = new ArrayList<>(response.results().size());
        for (RerankItem item : response.results()) {
            hits.add(new RerankHit(item.index() == null ? 0 : item.index(),
                    item.relevanceScore() == null ? 0.0 : item.relevanceScore()));
        }
        // 供应商一般已按分数降序；这里再排一次，不依赖对方顺序
        hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
        return hits;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<RerankItem> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankItem(
            @JsonProperty("index") Integer index,
            @JsonProperty("relevance_score") Double relevanceScore) {
    }
}

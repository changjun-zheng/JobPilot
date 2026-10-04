package com.jobpilot.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.ai.Citation;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 相似度检索（ARCHITECTURE.md §4.2）：
 * query 嵌入 → Chroma top-K → MySQL 回捞原文（仅 READY 文档）→ 相似度阈值截断。
 * Chroma/Ollama 不可用时降级为 MySQL 关键词检索，结果明确标记 KEYWORD_FALLBACK。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    /**
     * 向量路径候选池倍数：按 topK 的 N 倍取候选，相似度阈值截断与 READY 过滤
     * 吃掉一部分后仍能凑满 topK——不放大候选池时，过滤后的结果经常低于用户要的条数。
     */
    private static final int CANDIDATE_POOL_FACTOR = 3;

    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;
    private final RagProperties props;
    private final UsageRecorder usageRecorder;

    public KnowledgeRetrievalService(KbChunkMapper chunkMapper,
                                     KbDocumentMapper documentMapper,
                                     EmbeddingPort embeddingPort,
                                     VectorStorePort vectorStore,
                                     RagProperties props,
                                     UsageRecorder usageRecorder) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
        this.usageRecorder = usageRecorder;
    }

    /**
     * 检索（FP-10：查询嵌入按租户计量）。
     * <p>
     * {@code scenario} 由调用方显式声明——同一个检索动作在问答 / 纯检索 / Agent / 评测里的
     * 归属不同，编译器强迫每个调用点选边，不给「默认场景」留吞掉归属错误的余地。
     */
    public RetrievalResult search(RetrievalQuery query, UsageScenario scenario) {
        if (query.userId() == null || query.userId().isBlank()
                || query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("userId 与查询文本不能为空");
        }
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(query.userId())) {
            throw new UnauthorizedException("租户上下文与查询身份不一致");
        }
        int topK = query.topK() > 0 ? query.topK() : props.topK();
        try {
            return vectorSearch(query, topK, scenario);
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("向量检索失败，降级为关键词检索。VECTOR_STORE_DEGRADED", e);
            return RetrievalResult.keywordFallback(keywordSearch(query, topK));
        }
    }

    private RetrievalResult vectorSearch(RetrievalQuery query, int topK, UsageScenario scenario) {
        List<Double> queryVector = embeddingPort.embed(query.text());
        // 嵌入一旦成功就是真实消耗，无论后续 Chroma 是否失败——所以紧跟 embed 记账
        usageRecorder.recordEmbedding(query.userId(), scenario, props.embeddingModel(),
                query.text().codePointCount(0, query.text().length()), 1, true, null);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("user_id", query.userId());
        if (query.docType() != null && !query.docType().isBlank()) {
            filters.put("doc_type", query.docType());
        }
        List<VectorStorePort.VectorMatch> matches =
                vectorStore.search(queryVector, topK * CANDIDATE_POOL_FACTOR, filters);
        if (matches.isEmpty()) {
            return RetrievalResult.vector(List.of());
        }

        Map<String, Double> scoreById = new LinkedHashMap<>();
        for (VectorStorePort.VectorMatch match : matches) {
            if (match.score() >= props.similarityThreshold()) {
                scoreById.put(match.id(), match.score());
            }
        }
        if (scoreById.isEmpty()) {
            log.info("向量命中 {} 条但全部低于阈值 {}，返回空结果",
                    matches.size(), props.similarityThreshold());
            return RetrievalResult.vector(List.of());
        }

        Map<String, KbChunkEntity> chunks = loadReadyChunks(scoreById.keySet());
        List<RetrievedChunk> items = scoreById.entrySet().stream()
                .filter(e -> chunks.containsKey(e.getKey()))
                .limit(topK) // 候选池放大过，最终仍按用户要的条数截断
                .map(e -> toRetrieved(chunks.get(e.getKey()), e.getValue()))
                .toList();
        return RetrievalResult.vector(items);
    }

    /** 降级路径：提取关键词 → MySQL LIKE OR 查询（暴力版，够 M-1 演示降级语义） */
    private List<RetrievedChunk> keywordSearch(RetrievalQuery query, int topK) {
        List<String> keywords = extractKeywords(query.text());
        if (keywords.isEmpty()) {
            return List.of();
        }
        QueryWrapper<KbDocumentEntity> documentWrapper = new QueryWrapper<>();
        documentWrapper.eq("status", "READY");
        List<String> readyDocumentIds = documentMapper.selectList(documentWrapper).stream()
                .map(KbDocumentEntity::getId)
                .toList();
        if (readyDocumentIds.isEmpty()) {
            return List.of();
        }

        QueryWrapper<KbChunkEntity> wrapper = new QueryWrapper<>();
        wrapper.in("document_id", readyDocumentIds)
                .and(w -> keywords.forEach(kw -> w.or().like("text", kw)));
        if (query.docType() != null && !query.docType().isBlank()) {
            wrapper.eq("doc_type", query.docType());
        }
        int candidatePool = Math.max(topK * 4, 20);
        wrapper.orderByAsc("document_id", "seq").last("LIMIT " + candidatePool);
        return chunkMapper.selectList(wrapper).stream()
                .map(chunk -> new Scored(chunk, countHits(chunk.getText(), keywords)))
                .filter(s -> s.hits() >= props.keywordMinHits())   // 降级闸门：命中太少不算证据
                .sorted(Comparator.comparingInt(Scored::hits).reversed())
                .limit(topK)
                .map(s -> toRetrieved(s.chunk(), s.hits()))         // score = 命中数，不再是写死的 0.0
                .toList();
    }

    /** 降级打分的中转载体：一个候选 chunk + 它命中了几个关键词 */
    private record Scored(KbChunkEntity chunk, int hits) {
    }

    /** 数一数这段原文命中了几个不同的关键词 */
    private int countHits(String text, List<String> keywords) {
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * 暴力关键词：按非字母数字切 token，中文长 token 用 2 字滑窗、拉丁用 3 字；
     * 总数截断防 SQL 爆炸。窗口按码点滑动——切进代理对的关键词在 LIKE 里永远匹配不上。
     */
    List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        for (String token : text.split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) {
                continue;
            }
            int cpCount = token.codePointCount(0, token.length());
            if (cpCount <= 4) {
                keywords.add(token);
                continue;
            }
            int window = containsHan(token) ? 2 : 3;
            int i = 0;
            while (i + window <= cpCount && keywords.size() < 12) {
                int start = token.offsetByCodePoints(0, i);
                keywords.add(token.substring(start, token.offsetByCodePoints(start, window)));
                i++;
            }
        }
        return keywords.stream().distinct().limit(12).toList();
    }

    private boolean containsHan(String token) {
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** 回捞 Chunk 并过滤：只允许 READY 文档参与检索（ARCHITECTURE.md §7.3） */
    private Map<String, KbChunkEntity> loadReadyChunks(Collection<String> vectorIds) {
        List<KbChunkEntity> chunks = chunkMapper.selectByIds(vectorIds);
        if (chunks.isEmpty()) {
            return Map.of();
        }
        List<String> docIds = chunks.stream().map(KbChunkEntity::getDocumentId).distinct().toList();
        QueryWrapper<KbDocumentEntity> docWrapper = new QueryWrapper<>();
        docWrapper.in("id", docIds).eq("status", "READY");
        Map<String, ?> readyDocs = documentMapper.selectList(docWrapper).stream()
                .collect(Collectors.toMap(KbDocumentEntity::getId, Function.identity()));
        return chunks.stream()
                .filter(c -> readyDocs.containsKey(c.getDocumentId()))
                .collect(Collectors.toMap(KbChunkEntity::getVectorId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private RetrievedChunk toRetrieved(KbChunkEntity chunk, double score) {
        Citation citation = new Citation(chunk.getDocumentId(), chunk.getDocName(),
                chunk.getSectionPath(), chunk.getVectorId(),
                chunk.getCharStart() == null ? -1 : chunk.getCharStart(),
                chunk.getCharEnd() == null ? -1 : chunk.getCharEnd());
        return new RetrievedChunk(
                chunk.getDocumentId(), chunk.getVectorId(), chunk.getText(), score, citation);
    }
}

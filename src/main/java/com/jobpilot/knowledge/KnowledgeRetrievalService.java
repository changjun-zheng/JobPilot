package com.jobpilot.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.ai.Citation;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RerankPort;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.config.RagProperties;
import com.jobpilot.config.RerankProperties;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RerankPort rerankPort;
    private final RerankProperties rerankProps;

    public KnowledgeRetrievalService(KbChunkMapper chunkMapper,
                                     KbDocumentMapper documentMapper,
                                     EmbeddingPort embeddingPort,
                                     VectorStorePort vectorStore,
                                     RagProperties props,
                                     UsageRecorder usageRecorder,
                                     RerankPort rerankPort,
                                     RerankProperties rerankProps) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
        this.usageRecorder = usageRecorder;
        this.rerankPort = rerankPort;
        this.rerankProps = rerankProps;
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
        // includePlatform=true：把平台内容并入检索（where = user_id=<t> OR owner='PLATFORM'）
        List<VectorStorePort.VectorMatch> matches =
                vectorStore.search(queryVector, topK * CANDIDATE_POOL_FACTOR, filters, true);
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

        Map<String, KbChunkEntity> chunks = loadReadyChunks(scoreById.keySet(), query.companyIds());
        List<String> candidateIds = scoreById.keySet().stream().filter(chunks::containsKey).toList();
        return RetrievalResult.vector(rank(query.text(), candidateIds, chunks, scoreById, topK));
    }

    /**
     * 对候选排序并截到 topK：启用时用 cross-encoder 重排，否则/失败时用向量分数顺序。
     * <p>
     * <b>降级语义（别搞混）</b>：重排是可选增强——失败只告警并**保持向量分数顺序**，
     * **不设 {@code degraded}**。那个标记专指「向量→关键词」的降级，混用会让「降级必须可见」这条不变量失真。
     */
    private List<RetrievedChunk> rank(String queryText, List<String> ids,
                                      Map<String, KbChunkEntity> chunks,
                                      Map<String, Double> vectorScores, int topK) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RerankPort.RerankHit> order;
        if (rerankProps.enabled()) {
            try {
                List<String> texts = ids.stream().map(id -> chunks.get(id).getText()).toList();
                List<RerankPort.RerankHit> hits = rerankPort.rerank(queryText, texts, topK);
                // 空结果当作不可用处理：不该因重排返回空就把候选全丢掉
                order = hits.isEmpty() ? vectorOrder(ids, vectorScores) : hits;
            } catch (Exception e) {
                log.warn("重排序失败，回退为向量分数排序", e);
                order = vectorOrder(ids, vectorScores);
            }
        } else {
            order = vectorOrder(ids, vectorScores);
        }
        return order.stream()
                .limit(topK)
                .map(hit -> toRetrieved(chunks.get(ids.get(hit.index())), hit.score()))
                .toList();
    }

    /** 向量分数顺序（重排未启用或失败时的回退） */
    private List<RerankPort.RerankHit> vectorOrder(List<String> ids, Map<String, Double> vectorScores) {
        List<RerankPort.RerankHit> hits = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            hits.add(new RerankPort.RerankHit(i, vectorScores.get(ids.get(i))));
        }
        return hits;
    }

    /**
     * 降级路径：提取关键词 → MySQL LIKE OR 查询（暴力版，够 M-1 演示降级语义）。
     * <p>
     * 也**并入平台内容**——否则「一降级就看不到平台面经」是个不对称的坑。
     * 注意：用户自己没有 READY 文档时**不能提前返回**，那时仍要查平台。
     */
    private List<RetrievedChunk> keywordSearch(RetrievalQuery query, int topK) {
        List<String> keywords = extractKeywords(query.text());
        if (keywords.isEmpty()) {
            return List.of();
        }
        int candidatePool = Math.max(topK * 4, 20);
        List<KbChunkEntity> candidates = new ArrayList<>();

        QueryWrapper<KbDocumentEntity> documentWrapper = new QueryWrapper<>();
        documentWrapper.eq("status", "READY");
        List<String> readyDocumentIds = documentMapper.selectList(documentWrapper).stream()
                .map(KbDocumentEntity::getId)
                .toList();
        if (!readyDocumentIds.isEmpty()) {
            QueryWrapper<KbChunkEntity> wrapper = new QueryWrapper<>();
            wrapper.in("document_id", readyDocumentIds)
                    .and(w -> keywords.forEach(kw -> w.or().like("text", kw)));
            if (query.docType() != null && !query.docType().isBlank()) {
                wrapper.eq("doc_type", query.docType());
            }
            wrapper.orderByAsc("document_id", "seq").last("LIMIT " + candidatePool);
            candidates.addAll(chunkMapper.selectList(wrapper));
        }
        // 平台内容也并进来（@InterceptorIgnore 方法，只读 owner='PLATFORM'，永不返回租户行）；
        // 公司范围非空时平台文档按 company_id 收窄
        candidates.addAll(chunkMapper.selectPlatformChunksByKeywords(
                keywords, query.docType(), query.companyIds(), candidatePool));

        return candidates.stream()
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

    /**
     * 回捞 Chunk 并过滤：只允许 READY 文档参与检索（ARCHITECTURE.md §7.3）。
     * <p>
     * <b>平台行另读</b>：平台 Chunk 的 {@code user_id} 为 NULL，上面那两条租户查询会静默滤掉它们，
     * 所以用 {@code @InterceptorIgnore} 的平台读方法（只读 {@code owner='PLATFORM'}，永不返回租户行）单独取，
     * 再与本租户结果**按 vectorId 合并**。
     * <p>
     * {@code companyIds} 非空时**平台文档**再按公司硬收窄（{@code company_id ∈ companyIds}）——
     * 面试按「选中的公司」取面经（见 {@code InterviewService}）。**本租户的文档不受此限**（那是用户自己的材料）。
     */
    private Map<String, KbChunkEntity> loadReadyChunks(Collection<String> vectorIds, List<String> companyIds) {
        Map<String, KbChunkEntity> merged = new LinkedHashMap<>();

        // 本租户：文档必须 READY（公司范围不作用于本租户行）
        List<KbChunkEntity> chunks = chunkMapper.selectByIds(vectorIds);
        if (!chunks.isEmpty()) {
            List<String> docIds = chunks.stream().map(KbChunkEntity::getDocumentId).distinct().toList();
            QueryWrapper<KbDocumentEntity> docWrapper = new QueryWrapper<>();
            docWrapper.in("id", docIds).eq("status", "READY");
            Set<String> readyDocIds = documentMapper.selectList(docWrapper).stream()
                    .map(KbDocumentEntity::getId).collect(Collectors.toSet());
            chunks.stream()
                    .filter(c -> readyDocIds.contains(c.getDocumentId()))
                    .forEach(c -> merged.put(c.getVectorId(), c));
        }

        // 平台：单独读（租户查询够不着），文档同样要 READY，并按公司范围收窄
        List<KbChunkEntity> platformChunks = chunkMapper.selectPlatformChunksByIds(vectorIds);
        if (!platformChunks.isEmpty()) {
            List<String> platformDocIds = platformChunks.stream()
                    .map(KbChunkEntity::getDocumentId).distinct().toList();
            Set<String> readyPlatformDocIds = new HashSet<>(
                    documentMapper.selectPlatformReadyDocumentIds(platformDocIds, companyIds));
            platformChunks.stream()
                    .filter(c -> readyPlatformDocIds.contains(c.getDocumentId()))
                    .forEach(c -> merged.put(c.getVectorId(), c));
        }
        return merged;
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

package com.jobpilot.ai.adapter;

import com.jobpilot.ai.CollectionNotFoundException;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.RagProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chroma 0.6.x REST 适配器（/api/v1）。
 * 集合按名 get-or-create（409 表示已存在，再按名取 id），向量操作一律用 collection UUID。
 * 距离空间 cosine：返回的 distance = 1 - cos_similarity，因此 score = 1 - distance。
 * 失败抛运行时异常，由检索服务决定降级。原文一律以 MySQL 为准，这里只存向量 + 过滤 metadata。
 */
@Component
public class ChromaVectorStoreAdapter implements VectorStorePort {

    private final RestClient restClient;
    private final String collectionName;
    private final String configuredCollectionId;
    private final String embeddingModel;
    private volatile String collectionId;  // 懒加载缓存

    public ChromaVectorStoreAdapter(RagProperties props, ClientHttpRequestFactory requestFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(props.chromaBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.collectionName = props.chromaCollection();
        this.configuredCollectionId = props.chromaCollectionId();
        this.embeddingModel = props.embeddingModel();
    }

    @Override
    public void upsert(String id, List<Double> vector, Map<String, Object> metadata) {
        restClient.post()
                .uri("/api/v1/collections/{cid}/upsert", ensureCollectionId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "ids", List.of(id),
                        "embeddings", List.of(vector),
                        "metadatas", List.of(metadata)))
                .retrieve()
                .body(String.class);
    }

    @Override
    public void delete(String id) {
        restClient.post()
                .uri("/api/v1/collections/{cid}/delete", ensureCollectionId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", List.of(id)))
                .retrieve()
                .body(String.class);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        restClient.post()
                .uri("/api/v1/collections/{cid}/delete", ensureCollectionId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("where", Map.of("document_id", Map.of("$eq", String.valueOf(documentId)))))
                .retrieve()
                .body(String.class);
    }

    @Override
    public List<VectorMatch> search(List<Double> queryVector, int topK, Map<String, Object> filters,
                                    boolean includePlatform) {
        Map<String, Object> request = Map.of(
                "query_embeddings", List.of(queryVector),
                "n_results", topK,
                "where", buildWhere(filters, includePlatform));
        QueryResponse response = restClient.post()
                .uri("/api/v1/collections/{cid}/query", ensureCollectionId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(QueryResponse.class);
        if (response == null || response.ids() == null || response.ids().isEmpty()) {
            return List.of();
        }
        List<String> ids = response.ids().get(0);
        List<Double> distances = response.distances() == null || response.distances().isEmpty()
                ? List.of() : response.distances().get(0);
        List<VectorMatch> matches = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            double distance = i < distances.size() ? distances.get(i) : Double.MAX_VALUE;
            matches.add(new VectorMatch(ids.get(i), 1.0 - distance));
        }
        return matches;
    }

    /**
     * 只读探活：绝不创建集合，因此不走 ensureCollectionId()（那个在配置为空时会创建）。
     * 配置为空 → 返回 empty（未配置，无从自检）；配置了但不存在 → 抛 CollectionNotFoundException。
     */
    @Override
    public Optional<CollectionInfo> collectionInfo() {
        if (configuredCollectionId == null || configuredCollectionId.isBlank()) {
            return Optional.empty();
        }
        // 0.6.x 没有"按 UUID 单查"的 GET 路由（/{collection_id} 挂的是 PUT），只能拉全量再比对。
        // 该接口返回的是 JSON 数组，不是对象。
        List<CollectionDto> all = restClient.get()
                .uri("/api/v1/collections")
                .retrieve()
                .body(new ParameterizedTypeReference<List<CollectionDto>>() {
                });
        List<CollectionDto> list = all == null ? List.of() : all;

        return Optional.of(list.stream()
                .filter(c -> c.id() != null && c.id().equalsIgnoreCase(configuredCollectionId))
                .findFirst()
                .map(c -> new CollectionInfo(c.id(), c.name(), c.dimension(), c.metadata()))
                .orElseThrow(() -> new CollectionNotFoundException(
                        "配置的集合 UUID 不存在：" + configuredCollectionId
                                + "；当前实际存在的集合："
                                + list.stream().map(CollectionDto::name).toList())));
    }

    /**
     * 组装 Chroma 的 {@code where}。
     * <p>
     * <b>fail-closed</b>：{@code filters} 必须含非空 {@code user_id}，否则抛——绝不能发出无租户条件的查询。
     * {@code includePlatform=true} 时把租户条件从 {@code user_id=<t>} 改成
     * {@code $or[user_id=<t>, owner='PLATFORM']}（**另一支是「平台」，不是「任意」**），
     * 其余过滤键与它 {@code $and}。
     */
    private Map<String, Object> buildWhere(Map<String, Object> filters, boolean includePlatform) {
        Object tenant = filters == null ? null : filters.get("user_id");
        if (tenant == null || String.valueOf(tenant).isBlank()) {
            throw new IllegalArgumentException("向量检索缺少 user_id 租户过滤条件");
        }
        Map<String, Object> tenantCondition = includePlatform
                ? Map.of("$or", List.of(
                        Map.of("user_id", Map.of("$eq", String.valueOf(tenant))),
                        Map.of("owner", Map.of("$eq", "PLATFORM"))))
                : Map.of("user_id", Map.of("$eq", String.valueOf(tenant)));

        List<Map<String, Object>> others = new ArrayList<>();
        filters.forEach((key, value) -> {
            if (!"user_id".equals(key) && value != null) {
                others.add(Map.of(key, Map.of("$eq", String.valueOf(value))));
            }
        });
        if (others.isEmpty()) {
            return tenantCondition;
        }
        List<Map<String, Object>> and = new ArrayList<>();
        and.add(tenantCondition);
        and.addAll(others);
        return Map.of("$and", and);
    }

    private String ensureCollectionId() {
        String cached = collectionId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (collectionId != null) {
                return collectionId;
            }
            if (configuredCollectionId != null && !configuredCollectionId.isBlank()) {
                collectionId = configuredCollectionId;
                return collectionId;
            }
            // 建集合时把 embedding_model 写进集合 metadata：启动自检据此发现「换了嵌入模型但没重建索引」——
            // 维度相同（如 bge-m3 → bge-large-zh 都是 1024）时维度检查漏得掉，模型名不会。
            Map<String, Object> collectionMetadata = new java.util.LinkedHashMap<>();
            collectionMetadata.put("hnsw:space", "cosine");
            if (embeddingModel != null && !embeddingModel.isBlank()) {
                collectionMetadata.put("embedding_model", embeddingModel);
            }
            Map<String, Object> createBody = Map.of(
                    "name", collectionName,
                    "metadata", collectionMetadata);
            // 409 = 集合已存在；0.6.x 按名查询路由有缺陷，此时要求配置 chroma-collection-id
            CollectionDto created = restClient.post()
                    .uri("/api/v1/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // 4xx 不抛异常，下面统一给出可操作错误
                    })
                    .body(CollectionDto.class);
            if (created == null || created.id() == null) {
                throw new IllegalStateException("Chroma 集合已存在但无法按名获取，"
                        + "请在 jobpilot.rag.chroma-collection-id 配置集合 UUID：" + collectionName);
            }
            collectionId = created.id();
            return collectionId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionDto(String id, String name, Integer dimension, Map<String, Object> metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResponse(List<List<String>> ids, List<List<Double>> distances) {
    }
}

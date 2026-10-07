package com.jobpilot.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向量库端口。实现负责把 metadata（user_id/doc_type 等）翻译成底层存储的过滤语法。
 * 向量不可用/失败时抛 RuntimeException，由检索服务决定降级策略。
 */
public interface VectorStorePort {

    /** 幂等写入：同 id 覆盖 */
    void upsert(String id, List<Double> vector, Map<String, Object> metadata);

    void delete(String id);

    /**
     * 按文档批量删除向量（I-1 顺带清理：重试幂等与终态清场）。
     * document_id 是 upsert 时写入的 metadata，全局唯一且与行内 user_id 一一对应；
     * 调用方只能对已认领/租户校验过的文档调用，不存在跨租户面。
     */
    void deleteByDocumentId(String documentId);

    /** 相似度检索，按分数降序返回至多 topK 条 */
    List<VectorMatch> search(List<Double> queryVector, int topK, Map<String, Object> filters);

    /**
     * 探活：<b>只读地</b>检查配置的集合是否存在，绝不创建集合。
     * <p>
     * 返回 {@code Optional.empty()} 表示<b>未配置</b> collection id——无从自检，不是错误；
     * 配置了但集合不存在时抛 {@link CollectionNotFoundException}（永久性配置错误，调用方应阻断启动）；
     * 向量库不可达时抛其他运行时异常（临时故障，调用方应降级而非中断）。
     * <p>
     * 本方法<b>不</b>走 {@code ensureCollectionId()}——后者在配置为空时会创建集合，
     * 而查询方法不应带写副作用（CQS）。dimension 可能为 null，因为空集合尚未确立维度。
     */
    Optional<CollectionInfo> collectionInfo();

    record VectorMatch(String id, double score) {
    }

    /** 集合展示信息，供启动自检使用。{@code metadata} 里带 {@code embedding_model}（若创建时写入） */
    record CollectionInfo(String id, String name, Integer dimension, Map<String, Object> metadata) {
    }
}

package com.jobpilot.ai;

import java.util.List;

/**
 * 重排序端口：把一批候选文档按与 query 的相关度重新排序（cross-encoder，比向量检索更准但更贵）。
 * <p>
 * 端口不关心用哪个 provider；失败由调用方决定降级（本项目：回退到向量分数顺序，**不标记 degraded**）。
 */
public interface RerankPort {

    /**
     * @param documents 候选文档文本（顺序即下标）
     * @param topN      返回条数上限；{@code <=0} 表示不限
     * @return 命中按相关度降序；{@link RerankHit#index()} 指向入参 {@code documents} 的下标
     */
    List<RerankHit> rerank(String query, List<String> documents, int topN);

    record RerankHit(int index, double score) {
    }
}

package com.jobpilot.ai;

import java.util.List;

/**
 * 检索请求（ARCHITECTURE.md §5.3 RetrievalQuery）。
 * topK <= 0 时由服务层回落默认值。
 *
 * @param companyIds 公司范围（空 = 不限）。非空时把**平台内容硬收窄**到这些公司：
 *                   平台文档须 {@code company_id ∈ companyIds}（不带 {@code company_id} 的通用平台文档被排除）；
 *                   <b>用户自己的文档不受此限制</b>（companyIds 只作用于 {@code owner='PLATFORM'} 这一支）。
 */
public record RetrievalQuery(
        String userId,
        String text,
        int topK,
        String docType,
        List<String> companyIds
) {

    public RetrievalQuery {
        companyIds = companyIds == null ? List.of() : List.copyOf(companyIds);
    }

    /** 不限公司范围的检索（问答 / 通用检索 / Agent 工具） */
    public RetrievalQuery(String userId, String text, int topK, String docType) {
        this(userId, text, topK, docType, List.of());
    }
}

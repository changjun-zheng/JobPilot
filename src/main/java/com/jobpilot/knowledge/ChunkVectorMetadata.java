package com.jobpilot.knowledge;

import com.jobpilot.domain.KbDocumentEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chunk 向量的元数据形状——**租户导入与平台导入共用一处**。
 * <p>
 * 这些键是检索过滤的契约：Chroma 的 {@code where} 用 {@code user_id} / {@code owner} / {@code doc_type} /
 * {@code company_id} 过滤，两处写入路径若漂移（少写一个键、键名拼错），检索会**静默**召回不到或召回错内容
 * ——不报错，只是结果变差。因此形状收拢在这里，而不是各写一遍。
 */
public final class ChunkVectorMetadata {

    private ChunkVectorMetadata() {
    }

    /**
     * @param owner USER / PLATFORM
     *              <p>
     *              平台行（{@code user_id} 为 null）**不带 user_id 键**：{@code Map.of} 遇 null 会 NPE、
     *              Chroma 也不接受 null 值——平台行改带 {@code owner=PLATFORM}，检索按 owner 区分。
     */
    public static Map<String, Object> of(KbDocumentEntity doc, String owner) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (doc.getUserId() != null) {
            metadata.put("user_id", doc.getUserId());
        }
        metadata.put("owner", owner);
        metadata.put("document_id", doc.getId());
        metadata.put("doc_type", doc.getDocType());
        metadata.put("index_version", doc.getIndexVersion());
        if (doc.getCompanyId() != null) {
            metadata.put("company_id", doc.getCompanyId());
        }
        return metadata;
    }
}

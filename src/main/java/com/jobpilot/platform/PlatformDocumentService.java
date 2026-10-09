package com.jobpilot.platform;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.knowledge.ChunkPart;
import com.jobpilot.knowledge.ChunkSplitter;
import com.jobpilot.knowledge.ChunkVectorMetadata;
import com.jobpilot.mapper.PlatformCompanyMapper;
import com.jobpilot.mapper.PlatformKbMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 平台面经的导入与下架（管理端写入口，设计草案 §7/§8）。
 *
 * <h3>为什么是**同步**管线，而不是复用 I-1c 的 DB 队列</h3>
 * 队列的语义是「认领行自带的 {@code user_id} 写回 {@code UserContext} 再处理」；平台行 {@code user_id=NULL}，
 * 撞 {@code TenantLineInnerInterceptor} 的 fail-closed，认领 SQL 自己也会被拦截器改写出错。
 * 给 worker 开一条平台分支意味着在<b>最热的状态机</b>里加 if——而管理导入是低频人工操作，等待几秒可接受。
 * 于是平台导入**同步执行**，写路径全部收在 {@link PlatformKbMapper}（跨租户写面集中一处，见其注释）。
 *
 * <h3>为什么先写 PENDING 再翻 READY/FAILED</h3>
 * 与租户导入一致：落一行 PENDING 让「正在导」可见，失败落 FAILED + 原因（管理界面直接显示给运营者）。
 * 失败时清掉本次写入的 Chunk 与向量——半成品不得进入检索。
 *
 * <h3>已知缺口：平台导入的嵌入消耗**不计量**</h3>
 * {@code usage_record.user_id} 是 NOT NULL（计量主体是租户），平台导入没有租户可归集。设计草案 §10.2
 * 的「平台侧用量口径」未定，此处置为缺口而非塞一个假租户名——假数据会污染用量看板。
 */
@Service
public class PlatformDocumentService {

    private static final Logger log = LoggerFactory.getLogger(PlatformDocumentService.class);

    /** 与 {@code DocumentIngestService} 的白名单一致；INTERVIEW = 面经 */
    private static final Set<String> SUPPORTED_DOC_TYPES = Set.of("MARKDOWN", "PLAIN_TEXT", "INTERVIEW");

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final PlatformKbMapper platformKbMapper;
    private final PlatformCompanyMapper companyMapper;
    private final ChunkSplitter chunkSplitter;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;
    private final RagProperties props;

    public PlatformDocumentService(PlatformKbMapper platformKbMapper,
                                   PlatformCompanyMapper companyMapper,
                                   ChunkSplitter chunkSplitter,
                                   EmbeddingPort embeddingPort,
                                   VectorStorePort vectorStore,
                                   RagProperties props) {
        this.platformKbMapper = platformKbMapper;
        this.companyMapper = companyMapper;
        this.chunkSplitter = chunkSplitter;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    /** 管理列表 */
    public List<KbDocumentEntity> list(String status, String companyId, int limit) {
        int clamped = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return platformKbMapper.selectPlatformDocuments(
                blankToNull(status), blankToNull(companyId), clamped);
    }

    public KbDocumentEntity require(String id) {
        KbDocumentEntity doc = platformKbMapper.selectPlatformDocument(id);
        if (doc == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "平台文档不存在：" + id);
        }
        return doc;
    }

    /**
     * 导入一篇平台文档并同步索引。
     *
     * @param companyId 关联公司（可空：公司介绍/通用考点不绑公司）；非空时必须是**在架**公司
     * @param sourceNote 来源/授权备注，<b>必填</b>（合规，设计草案 §8）
     */
    public KbDocumentEntity importDocument(String name, String docType, String companyId,
                                           String tags, String sourceNote, String content) {
        String docName = requireText(name, "文档名");
        String type = requireText(docType, "文档类型").toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_DOC_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "不支持的文档类型：" + docType + "，允许值：" + SUPPORTED_DOC_TYPES);
        }
        String note = requireText(sourceNote, "来源/授权备注"); // 合规强制，缺失即 400
        String company = requireActiveCompany(blankToNull(companyId));
        String text = requireText(content, "内容");

        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setId(UUID.randomUUID().toString());
        doc.setOwner("PLATFORM"); // user_id 由 PlatformKbMapper 的 INSERT 硬写 NULL
        doc.setCompanyId(company);
        doc.setName(docName);
        doc.setDocType(type);
        doc.setTags(blankToNull(tags));
        doc.setSourceNote(note);
        doc.setContent(text);
        doc.setStatus("PENDING");
        doc.setIndexVersion(1);
        doc.setChunkCount(0);
        platformKbMapper.insertPlatformDocument(doc);
        return indexNow(doc);
    }

    /** 重新索引（换嵌入模型后用）：重置为 PENDING → 重切重嵌。ARCHIVED 不可复活 */
    public KbDocumentEntity reindex(String id) {
        require(id); // 404 语义先行
        int updated = platformKbMapper.resetPlatformDocumentForReindex(id);
        if (updated != 1) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "已下架的文档不能重索引：" + id);
        }
        KbDocumentEntity doc = require(id);
        return indexNow(doc);
    }

    /**
     * 下架（一键）：status → ARCHIVED + 清 Chunk + 尽力清向量。
     * <p>
     * 顺序：**先翻终态**（此后检索不再召回它），再清理。向量清理失败只告警——残留向量由
     * 「检索回捞只认 READY 文档」兜底，召回不到；Chunk 清理失败则上报（照 {@code DocumentIngestService.delete}
     * 的取舍：不因向量临时不可达就让运营者下架不掉内容）。
     */
    public void archive(String id) {
        require(id);
        platformKbMapper.archivePlatformDocument(id);
        try {
            vectorStore.deleteByDocumentId(id);
        } catch (RuntimeException e) {
            log.warn("下架时向量清理失败（残留不可召回，检索只认 READY）documentId={}", id, e);
        }
        platformKbMapper.deletePlatformChunks(id);
    }

    // ── 同步索引 ────────────────────────────────────────────────

    private KbDocumentEntity indexNow(KbDocumentEntity doc) {
        try {
            List<ChunkPart> parts = chunkSplitter.split(
                    doc.getContent(), doc.getDocType(), props.chunkSize(), props.chunkOverlap());
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            // 幂等：重索引先清旧 Chunk / 向量，同 vector_id 的 upsert 覆盖，不留孤儿
            platformKbMapper.deletePlatformChunks(doc.getId());
            vectorStore.deleteByDocumentId(doc.getId());

            for (ChunkPart part : parts) {
                List<Double> vector = embeddingPort.embed(part.text());
                String vectorId = doc.getId() + "#" + part.seq() + "#" + doc.getIndexVersion();
                vectorStore.upsert(vectorId, vector, ChunkVectorMetadata.of(doc, "PLATFORM"));

                KbChunkEntity chunk = new KbChunkEntity();
                chunk.setVectorId(vectorId);
                chunk.setDocumentId(doc.getId());
                chunk.setOwner("PLATFORM");
                chunk.setDocName(doc.getName());
                chunk.setDocType(doc.getDocType());
                chunk.setSectionPath(part.sectionPath());
                chunk.setSeq(part.seq());
                chunk.setText(part.text());
                chunk.setCharStart(part.charStart());
                chunk.setCharEnd(part.charEnd());
                chunk.setIndexVersion(doc.getIndexVersion());
                platformKbMapper.insertPlatformChunk(chunk);
            }
            platformKbMapper.markPlatformDocumentReady(doc.getId(), parts.size());
            log.info("平台文档索引完成 documentId={} chunks={}", doc.getId(), parts.size());
        } catch (RuntimeException e) {
            log.warn("平台文档索引失败 documentId={}", doc.getId(), e);
            bestEffortCleanup(doc.getId());
            platformKbMapper.markPlatformDocumentFailed(doc.getId(), truncate(describe(e)));
        }
        return require(doc.getId());
    }

    /** 终态清场：半成品 Chunk 与孤儿向量都不留；清理失败只告警（检索有 READY 过滤兜底） */
    private void bestEffortCleanup(String documentId) {
        try {
            platformKbMapper.deletePlatformChunks(documentId);
            vectorStore.deleteByDocumentId(documentId);
        } catch (RuntimeException e) {
            log.warn("平台文档终态清理未完成 documentId={}", documentId, e);
        }
    }

    private String requireActiveCompany(String companyId) {
        if (companyId == null) {
            return null;
        }
        PlatformCompanyEntity company = companyMapper.selectById(companyId);
        if (company == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "公司不存在：" + companyId);
        }
        if (!"ACTIVE".equals(company.getStatus())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "公司已下架，不能导入其面经：" + company.getName());
        }
        return companyId;
    }

    private String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

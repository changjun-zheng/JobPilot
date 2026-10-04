package com.jobpilot.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.config.IngestProperties;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档导入（ARCHITECTURE.md §4.1 的可重试状态机，I-1c 起拆为两段）：
 * <ul>
 *   <li>{@link #enqueue}：请求线程只做校验并落一行 {@code PENDING}，立即返回——索引移出请求线程；</li>
 *   <li>{@link #process}：worker 对一个已认领（PROCESSING）的任务执行单次尝试，
 *       切分 → 逐 Chunk 嵌入 → Chroma upsert → Chunk 落库 → READY。</li>
 * </ul>
 * 失败分流（fail-loud 的另一半）：{@code IllegalArgumentException}（空白内容等确定性校验失败）
 * 直接 FAILED 不重试——重试不可能修好它；其余异常视为暂态（向量库/嵌入模型不可达等），
 * 按 {@code retry_count} 重排队并指数退避，超过上限才 FAILED。
 * 任一失败路径都会清掉本次写入的 Chunk：半成品不得进入检索，也不得残留到下一次重试（vector_id 会撞主键）。
 * <p>
 * {@code process} 必须在 worker 设置好 {@code UserContext} 后调用——
 * Chunk 写入依赖租户拦截器注入 user_id，这也是上下文显式传递约定的落地处。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ChunkSplitter chunkSplitter;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;
    private final RagProperties props;
    private final IngestProperties ingestProps;
    private final UsageRecorder usageRecorder;

    public DocumentIngestService(KbDocumentMapper documentMapper,
                                 KbChunkMapper chunkMapper,
                                 ChunkSplitter chunkSplitter,
                                 EmbeddingPort embeddingPort,
                                 VectorStorePort vectorStore,
                                 RagProperties props,
                                 IngestProperties ingestProps,
                                 UsageRecorder usageRecorder) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chunkSplitter = chunkSplitter;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
        this.ingestProps = ingestProps;
        this.usageRecorder = usageRecorder;
    }

    /**
     * 队列入队：校验通过后落一行 PENDING 即返回（202 语义），索引交给 worker。
     * <p>
     * 服务层命令保留 userId 只是为了让用例可脱离 HTTP/ThreadLocal 测试；
     * 真正的 Controller 已从 UserContext 派生身份（客户端不能传入）。
     * 这里再做一次一致性校验，防止未来新增调用方绕过 Controller 注入另一个租户。
     */
    public KbDocumentEntity enqueue(IngestCommand command) {
        validate(command);
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(command.userId())) {
            throw new com.jobpilot.common.UnauthorizedException("租户上下文与导入身份不一致");
        }
        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setUserId(command.userId());
        doc.setName(command.name());
        doc.setDocType(command.docType());
        doc.setTags(command.tags());
        doc.setContent(command.content());
        doc.setStatus("PENDING");
        doc.setIndexVersion(1);
        doc.setChunkCount(0);
        doc.setRetryCount(0);
        documentMapper.insert(doc);
        return doc;
    }

    /** 状态查询；不存在时抛 404 语义的 ApiException（跨租户同样表现为不存在） */
    public KbDocumentEntity document(String id) {
        KbDocumentEntity doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new com.jobpilot.common.ApiException(com.jobpilot.common.ErrorCode.NOT_FOUND, "文档不存在：" + id);
        }
        return doc;
    }

    /**
     * 重排既有文档（重导）：仅 READY / FAILED 可重排，进行中的任务拒绝。
     * index_version 不变——同 vector_id 的 upsert 天然覆盖，不产生孤儿向量；
     * 行重置为 PENDING 后由同一个队列 worker 执行，复用全部重试/接管语义。
     * 条件更新（status IN (READY, FAILED)）兜住「查询后被认领」的竞态：未命中即视为进行中。
     */
    public KbDocumentEntity reindex(String documentId) {
        document(documentId); // 404 语义（含租户隔离）先行
        int updated = documentMapper.update(null, new UpdateWrapper<KbDocumentEntity>()
                .eq("id", documentId)
                .in("status", "READY", "FAILED")
                .set("status", "PENDING")
                .set("retry_count", 0)
                .set("chunk_count", 0)
                .set("next_retry_at", null)
                .set("error_message", null));
        if (updated != 1) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "任务正在进行中，无法重排");
        }
        return document(documentId);
    }

    /**
     * 执行一次已认领任务的索引尝试。失败不抛出——任务的去向（READY / PENDING 重排队 / FAILED）
     * 全部落到行上，worker 循环不因单个任务中断。
     */
    public void process(KbDocumentEntity claimed) {
        if (!"PROCESSING".equals(claimed.getStatus())) {
            throw new IllegalStateException(
                    "process 只接受已认领（PROCESSING）的任务，收到：" + claimed.getStatus());
        }

        // 嵌入消耗的累计器：嵌入调用发生在 try 内的任意一步都可能中断，
        // 计数必须与方法同生命周期，失败路径才能如实记下「已经消耗掉的部分」
        int embedCalls = 0;
        int embedChars = 0;
        try {
            // 幂等清理（Chunk + 旧向量）放在 try 内：清理本身依赖向量库可用，失败同样走重试语义
            cleanupAttempt(claimed);
            String content = claimed.getContent();
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            List<ChunkPart> parts = chunkSplitter.split(
                    content, claimed.getDocType(), props.chunkSize(), props.chunkOverlap());
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            for (ChunkPart part : parts) {
                List<Double> vector = embeddingPort.embed(part.text());
                // 嵌入成功即为真实消耗，立刻累计（后续 upsert 失败也不会漏记这一条）
                embedCalls++;
                embedChars += part.text().codePointCount(0, part.text().length());
                indexChunk(claimed, part, vector);
            }
            claimed.setStatus("READY");
            claimed.setChunkCount(parts.size());
            claimed.setErrorMessage(null);
            claimed.setNextRetryAt(null);
            usageRecorder.recordEmbedding(claimed.getUserId(), UsageScenario.INGEST,
                    props.embeddingModel(), embedChars, embedCalls, true, claimed.getId());
            updateGuardedByProcessing(claimed);
            log.info("文档索引完成 documentId={} chunks={}", claimed.getId(), parts.size());
        } catch (IllegalArgumentException e) {
            failPermanently(claimed, e, embedCalls, embedChars);
        } catch (Exception e) {
            requeueOrFail(claimed, e, embedCalls, embedChars);
        }
    }

    private void failPermanently(KbDocumentEntity claimed, Exception e, int embedCalls, int embedChars) {
        log.warn("文档索引失败（确定性错误，不重试）documentId={}", claimed.getId(), e);
        recordPartialEmbedding(claimed, embedCalls, embedChars);
        claimed.setStatus("FAILED");
        claimed.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        claimed.setNextRetryAt(null);
        bestEffortCleanup(claimed); // 终态清场：不留半成品 Chunk 与孤儿向量；清理失败只告警，不改变终态
        updateGuardedByProcessing(claimed);
    }

    private void requeueOrFail(KbDocumentEntity claimed, Exception e, int embedCalls, int embedChars) {
        recordPartialEmbedding(claimed, embedCalls, embedChars);
        String reason = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        int retried = claimed.getRetryCount() == null ? 1 : claimed.getRetryCount() + 1;
        if (retried > ingestProps.maxRetries()) {
            log.warn("文档索引失败且重试耗尽 documentId={} retryCount={}", claimed.getId(), retried, e);
            bestEffortCleanup(claimed); // 终态 FAILED：当场清掉本次尝试的残留
            claimed.setStatus("FAILED");
            claimed.setErrorMessage(reason);
            claimed.setNextRetryAt(null);
            claimed.setRetryCount(retried);
        } else {
            // 重排队：本次尝试的残留由下次尝试开头的幂等清理负责，这里不重复删
            Duration backoff = ingestProps.retryBackoff().multipliedBy(1L << (retried - 1));
            claimed.setStatus("PENDING");
            claimed.setErrorMessage(reason);
            claimed.setRetryCount(retried);
            claimed.setNextRetryAt(LocalDateTime.now().plus(backoff));
            log.warn("文档索引失败，第 {} 次重排队 documentId={} 退避={}s 原因={}",
                    retried, claimed.getId(), backoff.toSeconds(), reason);
        }
        updateGuardedByProcessing(claimed);
    }

    /** 终态写入以 status=PROCESSING 为前置条件：行若被并发改走（如接管），本次结果不覆盖他人状态 */
    private void updateGuardedByProcessing(KbDocumentEntity doc) {
        int updated = documentMapper.update(doc, new UpdateWrapper<KbDocumentEntity>()
                .eq("id", doc.getId())
                .eq("status", "PROCESSING"));
        if (updated != 1) {
            log.error("任务状态写入未命中（行状态已被并发修改，结果丢弃）documentId={} 期望落成 {}",
                    doc.getId(), doc.getStatus());
        }
    }

    /** 认领后的幂等清场：删同文档旧 Chunk + 旧向量，重试才不会撞主键、终态才不会留孤儿 */
    private void cleanupAttempt(KbDocumentEntity doc) {
        chunkMapper.delete(new QueryWrapper<KbChunkEntity>().eq("document_id", doc.getId()));
        vectorStore.deleteByDocumentId(doc.getId());
    }

    /** 终态清场的尽力而为版：清理失败只告警——向量残留由 READY 过滤兜底（检索不会命中），不值得为它改变终态 */
    private void bestEffortCleanup(KbDocumentEntity doc) {
        try {
            cleanupAttempt(doc);
        } catch (RuntimeException e) {
            log.warn("终态清理未完成（检索有 READY 过滤兜底，孤儿向量可由下次重排清掉）documentId={}",
                    doc.getId(), e);
        }
    }

    /** 部分成功的嵌入同样是真实消耗：失败/重排路径只要有已发生的调用，就如实记一行 FAILED + 已消耗的量 */
    private void recordPartialEmbedding(KbDocumentEntity claimed, int embedCalls, int embedChars) {
        if (embedCalls > 0) {
            usageRecorder.recordEmbedding(claimed.getUserId(), UsageScenario.INGEST,
                    props.embeddingModel(), embedChars, embedCalls, false, claimed.getId());
        }
    }

    private void indexChunk(KbDocumentEntity doc, ChunkPart part, List<Double> vector) {
        String vectorId = doc.getId() + "#" + part.seq() + "#" + doc.getIndexVersion();

        // 1.先写 Chroma（upsert 幂等：同 vector_id 重复执行是覆盖而非新增）
        vectorStore.upsert(vectorId, vector, Map.of(
                "user_id", doc.getUserId(),
                "document_id", doc.getId(),
                "doc_type", doc.getDocType(),
                "index_version", doc.getIndexVersion()));

        KbChunkEntity chunk = new KbChunkEntity();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId(doc.getId());
        chunk.setUserId(doc.getUserId());
        chunk.setDocName(doc.getName());
        chunk.setDocType(doc.getDocType());
        chunk.setSectionPath(part.sectionPath());
        chunk.setSeq(part.seq());
        chunk.setText(part.text());
        chunk.setCharStart(part.charStart());
        chunk.setCharEnd(part.charEnd());
        chunk.setIndexVersion(doc.getIndexVersion());

        // 2.再写 MySQL（process 开头已清同文档旧 Chunk，重试不会撞主键）
        chunkMapper.insert(chunk);
    }

    private void validate(IngestCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("文档名称不能为空");
        }
        if (!"MARKDOWN".equals(command.docType()) && !"PLAIN_TEXT".equals(command.docType())) {
            throw new IllegalArgumentException("M-1 仅支持 MARKDOWN / PLAIN_TEXT，收到：" + command.docType());
        }
    }

    private String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}

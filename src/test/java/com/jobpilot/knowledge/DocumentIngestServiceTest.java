package com.jobpilot.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.IngestProperties;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestServiceTest {

    private static final IngestProperties INGEST_PROPS = new IngestProperties(
            true, Duration.ofSeconds(2), 2, 3, Duration.ofSeconds(30), 2, Duration.ofMinutes(10));

    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private EmbeddingPort embeddingPort;
    private VectorStorePort vectorStore;
    private UsageRecorder usageRecorder;
    private DocumentIngestService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        embeddingPort = mock(EmbeddingPort.class);
        vectorStore = mock(VectorStorePort.class);
        usageRecorder = mock(UsageRecorder.class);
        RagProperties props = new RagProperties(
                "http://localhost:11434", "bge-m3", "qwen2.5:3b",
                "http://localhost:8000", "jobpilot_chunks", null, 500, 100, 5, 0.45, 2);
        service = new DocumentIngestService(
                documentMapper, chunkMapper, new ChunkSplitter(), embeddingPort, vectorStore, props,
                INGEST_PROPS, usageRecorder);

        // 模拟 MyBatis-Plus ASSIGN_UUID：insert 时补齐文档 ID
        doAnswer(invocation -> {
            KbDocumentEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId("doc-test");
            }
            return 1;
        }).when(documentMapper).insert(any(KbDocumentEntity.class));
        when(documentMapper.update(any(KbDocumentEntity.class), any(Wrapper.class))).thenReturn(1);
        when(chunkMapper.delete(any(Wrapper.class))).thenReturn(0);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1, 0.2));
    }

    @Test
    void enqueueLandsPendingRowWithOriginalContent() {
        KbDocumentEntity doc = service.enqueue(new IngestCommand(
                "u1", "简历.md", "MARKDOWN", null, "# 技能\n\nJava\n"));

        ArgumentCaptor<KbDocumentEntity> inserted = ArgumentCaptor.forClass(KbDocumentEntity.class);
        verify(documentMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(inserted.getValue().getContent()).isEqualTo("# 技能\n\nJava\n");
        assertThat(inserted.getValue().getRetryCount()).isZero();
        assertThat(doc.getId()).isEqualTo("doc-test");
        // 入队不做任何索引动作
        verify(vectorStore, never()).upsert(anyString(), any(), anyMap());
        verify(chunkMapper, never()).insert(any(KbChunkEntity.class));
    }

    @Test
    void processMarksReadyAndWritesIdempotentVectorIds() {
        KbDocumentEntity claimed = claimedTask(0, "# 技能\n\nJava\n");

        service.process(claimed);

        ArgumentCaptor<KbDocumentEntity> saved = savedDocument();
        assertThat(saved.getValue().getStatus()).isEqualTo("READY");
        assertThat(saved.getValue().getChunkCount()).isPositive();
        assertThat(saved.getValue().getErrorMessage()).isNull();
        assertThat(saved.getValue().getNextRetryAt()).isNull();

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(vectorStore, atLeastOnce()).upsert(ids.capture(), any(), anyMap());
        // 向量 ID 形如 docId#seq#indexVersion，重试重建走 upsert 幂等
        assertThat(ids.getAllValues()).allMatch(id -> id.startsWith("doc-test#") && id.endsWith("#1"));

        // FP-10：成功导入按文档聚合记一行（call_count = Chunk 数，char_count = 码点总数）
        org.mockito.Mockito.verify(usageRecorder).recordEmbedding(
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq(UsageScenario.INGEST),
                org.mockito.ArgumentMatchers.eq("bge-m3"),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("doc-test"));
    }

    @Test
    void transientFailureRequeuesWithExponentialBackoff() {
        doThrow(new IllegalStateException("Ollama 离线")).when(embeddingPort).embed(any());
        KbDocumentEntity claimed = claimedTask(0, "# 技能\n\nJava\n");

        service.process(claimed);

        ArgumentCaptor<KbDocumentEntity> saved = savedDocument();
        assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().getRetryCount()).isEqualTo(1);
        assertThat(saved.getValue().getErrorMessage()).contains("Ollama 离线");
        // 首次重排队退避 = retryBackoff × 2^0 = 30s
        assertThat(saved.getValue().getNextRetryAt())
                .isCloseTo(LocalDateTime.now().plusSeconds(30), within(Duration.ofSeconds(5)));
        // 幂等清理只发生一次（attempt 开头，Chunk + 旧向量）；重排队路径不再重复删
        verify(chunkMapper).delete(any(Wrapper.class));
        verify(vectorStore).deleteByDocumentId("doc-test");
        // 嵌入在第一个 Chunk 就失败：没有已发生的消耗，不记计量行
        org.mockito.Mockito.verify(usageRecorder, never()).recordEmbedding(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void exhaustedRetriesLandFailed() {
        doThrow(new IllegalStateException("Chroma 不可达")).when(vectorStore).upsert(anyString(), any(), anyMap());
        KbDocumentEntity claimed = claimedTask(3, "# 技能\n\nJava\n"); // 已重排 3 次，本次再失败即耗尽

        service.process(claimed);

        ArgumentCaptor<KbDocumentEntity> saved = savedDocument();
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getRetryCount()).isEqualTo(4);
        assertThat(saved.getValue().getNextRetryAt()).isNull();
        assertThat(saved.getValue().getErrorMessage()).contains("Chroma 不可达");
        // 开头的幂等清理 + 终态 FAILED 的残留清理
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.times(2)).delete(any(Wrapper.class));
        // 部分消耗也如实记录：嵌入已发生但 upsert 失败 → FAILED 聚合行
        org.mockito.Mockito.verify(usageRecorder).recordEmbedding(
                org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq(UsageScenario.INGEST),
                org.mockito.ArgumentMatchers.eq("bge-m3"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("doc-test"));
    }

    @Test
    void blankContentFailsPermanentlyWithoutRetry() {
        // 确定性校验失败重试也不可能修好：直接 FAILED，不消耗重试语义
        KbDocumentEntity claimed = claimedTask(0, "   ");

        service.process(claimed);

        ArgumentCaptor<KbDocumentEntity> saved = savedDocument();
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getRetryCount()).isZero();
        assertThat(saved.getValue().getNextRetryAt()).isNull();
        assertThat(saved.getValue().getErrorMessage()).contains("提取文本为空");
        verify(chunkMapper, never()).insert(any(KbChunkEntity.class));
        // 校验失败发生在任何嵌入之前：没有消耗，也没有计量行
        org.mockito.Mockito.verify(usageRecorder, never()).recordEmbedding(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void processRejectsUnclaimedTask() {
        KbDocumentEntity pending = claimedTask(0, "# 技能");
        pending.setStatus("PENDING");

        assertThatThrownBy(() -> service.process(pending))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROCESSING");
    }

    @Test
    void reindexRequeuesReadyDocument() {
        KbDocumentEntity ready = claimedTask(0, null);
        ready.setStatus("READY");
        ready.setChunkCount(3);
        ready.setErrorMessage("旧错误");
        when(documentMapper.selectById("doc-test")).thenReturn(ready);
        when(documentMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(1);

        KbDocumentEntity result = service.reindex("doc-test");

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<KbDocumentEntity>> wrapper =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(documentMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapper.capture());
        // 条件更新只对 READY/FAILED 生效，进行中任务不可重排（目标 SQL 是占位符，实际值在参数表里）
        assertThat(wrapper.getValue().getTargetSql()).contains("status IN");
        assertThat(wrapper.getValue().getParamNameValuePairs().values())
                .contains("READY", "FAILED", "PENDING");
    }

    @Test
    void reindexRejectsInFlightDocument() {
        when(documentMapper.selectById("doc-test")).thenReturn(claimedTask(0, null)); // PROCESSING
        when(documentMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.reindex("doc-test"))
                .isInstanceOf(com.jobpilot.common.ApiException.class)
                .hasMessageContaining("无法重排");
    }

    // ── 列表与删除（I-4 知识库页）─────────────────────────────

    @Test
    void listReturnsMapperResult() {
        when(documentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(document("doc-1")));

        assertThat(service.list(null, 10)).extracting(KbDocumentEntity::getId).containsExactly("doc-1");
    }

    @Test
    void deleteRemovesVectorsThenChunksThenDocumentRow() {
        when(documentMapper.selectById("doc-1")).thenReturn(document("doc-1"));

        service.delete("doc-1");

        org.mockito.InOrder order = inOrder(vectorStore, chunkMapper, documentMapper);
        order.verify(vectorStore).deleteByDocumentId("doc-1");
        order.verify(chunkMapper).delete(any(Wrapper.class));
        order.verify(documentMapper).deleteById("doc-1");
    }

    @Test
    void deleteStillSucceedsWhenVectorCleanupFails() {
        when(documentMapper.selectById("doc-1")).thenReturn(document("doc-1"));
        doThrow(new RuntimeException("Chroma down")).when(vectorStore).deleteByDocumentId("doc-1");

        service.delete("doc-1"); // 向量清理尽力而为，不因向量库不可达就让用户删不掉

        // 残留向量不可召回（检索只读 MySQL Chunk），Chunk 与文档行照删
        verify(chunkMapper).delete(any(Wrapper.class));
        verify(documentMapper).deleteById("doc-1");
    }

    @Test
    void deleteOnMissingDocumentIsNotFoundAndTouchesNothing() {
        when(documentMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.delete("ghost"))
                .isInstanceOf(com.jobpilot.common.ApiException.class)
                .hasMessageContaining("文档不存在");
        verify(vectorStore, never()).deleteByDocumentId(anyString());
    }

    private KbDocumentEntity document(String id) {
        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setId(id);
        doc.setUserId("u1");
        return doc;
    }

    @Test
    void interviewDocTypeIsAccepted() {
        // 面经（I-4 面试模拟官）：显式类型，无法从扩展名判断，由导入方指定
        KbDocumentEntity doc = service.enqueue(new IngestCommand(
                "u1", "字节面经.md", "INTERVIEW", null, "面经正文"));

        assertThat(doc.getDocType()).isEqualTo("INTERVIEW");
        assertThat(doc.getStatus()).isEqualTo("PENDING");
    }

    private KbDocumentEntity claimedTask(int retryCount, String content) {
        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setId("doc-test");
        doc.setUserId("u1");
        doc.setName("简历.md");
        doc.setDocType("MARKDOWN");
        doc.setStatus("PROCESSING");
        doc.setIndexVersion(1);
        doc.setChunkCount(0);
        doc.setRetryCount(retryCount);
        doc.setContent(content);
        return doc;
    }

    private ArgumentCaptor<KbDocumentEntity> savedDocument() {
        ArgumentCaptor<KbDocumentEntity> captor = ArgumentCaptor.forClass(KbDocumentEntity.class);
        verify(documentMapper).update(captor.capture(), any(Wrapper.class));
        return captor;
    }
}

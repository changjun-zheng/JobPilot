package com.jobpilot.knowledge;

import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.RagProperties;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private KbChunkMapper chunkMapper;
    private com.jobpilot.mapper.KbDocumentMapper documentMapper;
    private EmbeddingPort embeddingPort;
    private VectorStorePort vectorStore;
    private ChatPort chatPort;
    private UsageRecorder usageRecorder;
    private KnowledgeRetrievalService retrievalService;
    private RagAskService askService;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        documentMapper = mock(com.jobpilot.mapper.KbDocumentMapper.class);
        embeddingPort = mock(EmbeddingPort.class);
        vectorStore = mock(VectorStorePort.class);
        chatPort = mock(ChatPort.class);
        usageRecorder = mock(UsageRecorder.class);
        RagProperties props = new RagProperties(
                "http://localhost:11434", "bge-m3", "qwen2.5:3b",
                "http://localhost:8000", "jobpilot_chunks", null, 500, 100, 5, 0.45,2);
        retrievalService = new KnowledgeRetrievalService(
                chunkMapper, documentMapper, embeddingPort, vectorStore, props, usageRecorder);
        askService = new RagAskService(retrievalService, chatPort, usageRecorder);
    }

    @Test
    void vectorHitAboveThresholdReturnsChunk() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch("doc1#0#1", 0.8)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk("doc1#0#1")));
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "会用 RAG 吗", 5, null), UsageScenario.SEARCH);

        assertThat(result.searchMode()).isEqualTo(com.jobpilot.ai.SearchMode.VECTOR);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).score()).isEqualTo(0.8);
        // FP-10：嵌入成功即计一行，字符数按码点计
        verify(usageRecorder).recordEmbedding(eq("u1"), eq(UsageScenario.SEARCH), eq("bge-m3"),
                eq("会用 RAG 吗".codePointCount(0, "会用 RAG 吗".length())), eq(1), eq(true), isNull());
    }

    @Test
    void belowThresholdReturnsEmptyWithoutLLM() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch("doc1#0#1", 0.2)));

        RagAskService.AskAnswer answer = askService.ask("u1", "完全无关的问题", 5, null);

        assertThat(answer.retrieval().items()).isEmpty();
        assertThat(answer.answer()).contains("缺少足够依据");
        verifyNoInteractions(chatPort);
    }

    @Test
    void chromaOutageDegradesToKeywordSearch() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("Chroma 不可用"));
        // 同时命中 "RAG" 与 "经验" 两个关键词，达到 keywordMinHits = 2
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("doc1#0#1", "熟悉 RAG 开发，有 3 年经验")));
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));
        when(chatPort.chat(any())).thenReturn(new com.jobpilot.ai.ChatCompletion(
                "回答", List.of(), com.jobpilot.ai.FinishReason.STOP, null, "test", "test-model"));

        RagAskService.AskAnswer answer = askService.ask("u1", "RAG 经验", 5, null);

        assertThat(answer.retrieval().degraded()).isTrue();
        assertThat(answer.retrieval().searchMode())
                .isEqualTo(com.jobpilot.ai.SearchMode.KEYWORD_FALLBACK);
        assertThat(answer.retrieval().items()).hasSize(1);
        // 降级命中且过闸门后仍会走生成，并带上降级证据
        verify(chatPort).chat(any());
    }

    @Test
    void emptyReadyDocumentSetSkipsChunkQueryDuringKeywordFallback() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("Chroma 不可用"));
        when(documentMapper.selectList(any())).thenReturn(List.of());

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "RAG 经验", 5, null), UsageScenario.SEARCH);

        assertThat(result.degraded()).isTrue();
        assertThat(result.searchMode()).isEqualTo(com.jobpilot.ai.SearchMode.KEYWORD_FALLBACK);
        assertThat(result.items()).isEmpty();
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void keywordFallbackBelowMinHitsRefusesWithoutLLM() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("Chroma 不可用"));
        // chunk 只命中 "RAG" 一个关键词，低于 keywordMinHits = 2 → 不算证据
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("doc1#0#1")));

        RagAskService.AskAnswer answer = askService.ask("u1", "RAG 经验", 5, null);

        assertThat(answer.retrieval().degraded()).isTrue();
        assertThat(answer.retrieval().items()).isEmpty();
        assertThat(answer.answer()).contains("没有检索到相关证据");
        verifyNoInteractions(chatPort);
    }

    @Test
    void fullVectorOutageAlsoDegrades() {
        when(embeddingPort.embed(any())).thenThrow(new IllegalStateException("Ollama 离线"));
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "查询", 0, null), UsageScenario.SEARCH);

        assertThat(result.degraded()).isTrue();
        // 嵌入调用本身失败：没有可归集的消耗，不记计量行
        verifyNoInteractions(usageRecorder);
    }

    @Test
    void keywordExtractionHandlesChineseAndEnglish() {
        List<String> keywords = retrievalService.extractKeywords("3年 Java 经验，熟悉 Spring Boot");

        assertThat(keywords).contains("3年", "Java", "经验", "熟悉");
        assertThat(keywords.size()).isLessThanOrEqualTo(12);
    }

    /** 候选池：候选按 topK 的 3 倍过取（阈值截断与 READY 过滤会吃掉一部分），最终仍只返回 topK 条 */
    @Test
    void vectorSearchOverFetchesCandidatesThenTruncatesToTopK() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap())).thenReturn(List.of(
                new VectorStorePort.VectorMatch("doc1#0#1", 0.9),
                new VectorStorePort.VectorMatch("doc1#0#2", 0.8),
                new VectorStorePort.VectorMatch("doc1#0#3", 0.7),
                new VectorStorePort.VectorMatch("doc1#0#4", 0.6),
                new VectorStorePort.VectorMatch("doc1#0#5", 0.5),
                new VectorStorePort.VectorMatch("doc1#0#6", 0.4)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(
                chunk("doc1#0#1"), chunk("doc1#0#2"), chunk("doc1#0#3"),
                chunk("doc1#0#4"), chunk("doc1#0#5"), chunk("doc1#0#6")));
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "会用 RAG 吗", 2, null), UsageScenario.SEARCH);

        // 用户要 2 条，候选池里 6 条都过阈值——截断后应是分数最高的 2 条，且顺序保持
        assertThat(result.items()).extracting(RetrievedChunk::chunkId)
                .containsExactly("doc1#0#1", "doc1#0#2");
        verify(vectorStore).search(any(), eq(6), anyMap()); // topK(2) × CANDIDATE_POOL_FACTOR(3)
    }

    /** 汉字长 token 用 2 字窗口——原 3 字窗口在中文里几乎切不出词 */
    @Test
    void chineseLongTokenUsesTwoCharacterWindows() {
        List<String> keywords = retrievalService.extractKeywords("垃圾回收机制");

        assertThat(keywords).containsExactly("垃圾", "圾回", "回收", "收机", "机制");
    }

    /** 拉丁长 token 保持 3 字窗口，总数仍截断在 12 */
    @Test
    void latinLongTokenKeepsThreeCharacterWindowsAndStopsAtTwelve() {
        List<String> keywords = retrievalService.extractKeywords("transformation");

        assertThat(keywords).hasSize(12);
        assertThat(keywords).allSatisfy(keyword -> assertThat(keyword).hasSize(3));
        assertThat(keywords).startsWith("tra", "ran", "ans");
    }

    /**
     * 窗口按码点滑动，绝不切进代理对。
     * <p>
     * 用例是扩展 B 区汉字（U+20000 起，UTF-16 里各占两个 char）：按 char 算偏移会在第二个窗口
     * 就切出「以低代理项开头」的非法串，关键词在 LIKE 里永远匹配不上——正是 {@code ChunkSplitter}
     * 踩过的同一类坑。
     */
    @Test
    void windowsNeverSplitSurrogatePairs() {
        // U+20000 起六个汉字，每个由一对代理项组成；写成转义形式以免源码编码把字面量弄坏
        String supplementaryHan =
                "𠀀𠀁𠀂𠀃𠀄𠀅";

        List<String> keywords = retrievalService.extractKeywords(supplementaryHan);

        assertThat(keywords).isNotEmpty();
        // 断言每个关键词都是完整的多码点窗口：若 token 被拆成单字，这里会变成 1 码点而暴露
        assertThat(keywords).allSatisfy(keyword -> assertThat(keyword.codePointCount(0, keyword.length()))
                .as("窗口未按码点滑动：%s", keyword)
                .isGreaterThan(1));
        assertThat(keywords).allSatisfy(keyword -> {
            assertThat(Character.isLowSurrogate(keyword.charAt(0)))
                    .as("关键词以低代理项开头，说明切进了代理对：%s", keyword).isFalse();
            assertThat(Character.isHighSurrogate(keyword.charAt(keyword.length() - 1)))
                    .as("关键词以高代理项结尾，说明切进了代理对：%s", keyword).isFalse();
        });
    }

    private com.jobpilot.domain.KbDocumentEntity readyDoc() {
        com.jobpilot.domain.KbDocumentEntity doc = new com.jobpilot.domain.KbDocumentEntity();
        doc.setId("doc1");
        doc.setUserId("u1");
        doc.setStatus("READY");
        return doc;
    }

    private com.jobpilot.domain.KbChunkEntity chunk(String vectorId) {
        return chunk(vectorId, "熟悉 RAG 与 Agent 开发");
    }

    private com.jobpilot.domain.KbChunkEntity chunk(String vectorId, String text) {
        com.jobpilot.domain.KbChunkEntity chunk = new com.jobpilot.domain.KbChunkEntity();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId("doc1");
        chunk.setUserId("u1");
        chunk.setDocName("简历.md");
        chunk.setDocType("MARKDOWN");
        chunk.setSectionPath("技能");
        chunk.setSeq(0);
        chunk.setText(text);
        chunk.setCharStart(0);
        chunk.setCharEnd(10);
        chunk.setIndexVersion(1);
        return chunk;
    }
}

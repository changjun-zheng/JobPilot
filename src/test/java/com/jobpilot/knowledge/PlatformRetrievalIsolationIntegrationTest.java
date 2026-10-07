package com.jobpilot.knowledge;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台内容并入检索的**隔离证明**（本切片的核心验收）。
 * <p>
 * 播种 A、B、平台各一篇 doc+chunk（`JdbcTemplate` 绕过拦截器）。向量层被 mock 成「**把三篇都返回**」
 * （模拟 where 最坏情况：连别人的也带回来了），然后验证：
 * <ul>
 *   <li>A 检索 → **自己的 + 平台的**，**绝不含 B**（回捞的租户过滤挡住）；</li>
 *   <li>B 检索 → 自己的 + 平台，绝不含 A；</li>
 *   <li>用户**自己没有文档**时，平台内容**仍能**被检索到。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class PlatformRetrievalIsolationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private KnowledgeRetrievalService retrievalService;
    @Autowired
    private EmbeddingPort embeddingPort;
    @Autowired
    private VectorStorePort vectorStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private String chunkA;
    private String chunkB;
    private String chunkP;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        String docP = UUID.randomUUID().toString();
        chunkA = docA + "#0#1";
        chunkB = docB + "#0#1";
        chunkP = docP + "#0#1";

        seedDocument(docA, tenantA, "A-resume", "USER", "a-private-keyword");
        seedDocument(docB, tenantB, "B-resume", "USER", "b-private-keyword");
        seedDocument(docP, null, "字节面试面经", "PLATFORM", "p-platform-keyword");
        seedChunk(chunkA, docA, tenantA, "USER", "a-private-keyword");
        seedChunk(chunkB, docB, tenantB, "USER", "b-private-keyword");
        seedChunk(chunkP, docP, null, "PLATFORM", "p-platform-keyword");

        reset(embeddingPort, vectorStore);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void tenantSeesOwnAndPlatformButNeverOtherTenants() {
        // 最坏情况：向量层把三篇（含 B 的）全带回来
        mockVectorHits(chunkA, chunkB, chunkP);

        UserContext.set(tenantA);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(tenantA, "keyword", 5, null), UsageScenario.SEARCH);

        assertThat(result.items()).extracting(RetrievedChunk::chunkId)
                .contains(chunkA, chunkP)     // 自己的 + 平台的
                .doesNotContain(chunkB);      // 别人的——被回捞的租户过滤挡住

        // 并且确实要求「并入平台」
        ArgumentCaptor<Boolean> includePlatform = ArgumentCaptor.forClass(Boolean.class);
        verify(vectorStore).search(any(), anyInt(), anyMap(), includePlatform.capture());
        assertThat(includePlatform.getValue()).isTrue();
    }

    @Test
    void anotherTenantSeesOwnAndPlatformButNeverA() {
        mockVectorHits(chunkA, chunkB, chunkP);

        UserContext.set(tenantB);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(tenantB, "keyword", 5, null), UsageScenario.SEARCH);

        assertThat(result.items()).extracting(RetrievedChunk::chunkId)
                .contains(chunkB, chunkP)
                .doesNotContain(chunkA);
    }

    @Test
    void platformContentIsVisibleEvenWhenTenantHasNoOwnDocuments() {
        mockVectorHits(chunkP); // 只命中平台

        UserContext.set(tenantA);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(tenantA, "keyword", 5, null), UsageScenario.SEARCH);

        assertThat(result.items()).extracting(RetrievedChunk::chunkId).containsExactly(chunkP);
    }

    private void mockVectorHits(String... vectorIds) {
        when(vectorStore.search(any(), anyInt(), anyMap(), anyBoolean()))
                .thenReturn(java.util.Arrays.stream(vectorIds)
                        .map(id -> new VectorStorePort.VectorMatch(id, 0.9))
                        .toList());
    }

    private void seedDocument(String id, String userId, String name, String owner, String content) {
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, owner, name, doc_type, status, index_version, chunk_count, content) "
                        + "VALUES (?, ?, ?, ?, 'PLAIN_TEXT', 'READY', 1, 1, ?)",
                id, userId, owner, name, content);
    }

    private void seedChunk(String vectorId, String docId, String userId, String owner, String text) {
        jdbcTemplate.update("INSERT INTO kb_chunk "
                        + "(vector_id, document_id, user_id, owner, doc_name, doc_type, section_path, seq, text, "
                        + "char_start, char_end, index_version) "
                        + "VALUES (?, ?, ?, ?, ?, 'PLAIN_TEXT', '/', 0, ?, 0, 10, 1)",
                vectorId, docId, userId, owner, docId + "-doc", text);
    }
}

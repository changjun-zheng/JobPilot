package com.jobpilot.knowledge;

import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.security.JwtService;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 降级路径（KEYWORD_FALLBACK）的边界回归（ROADMAP §4.1.2「空知识库关键词降级」）。
 * 锁三件事——
 * 1. 租户视角无 READY 文档时早返回，绝不把空集合拼进 IN ()：MyBatis-Plus 对空集合
 *    in 会生成非法 SQL，此保护一旦回归，表现为 500 而非 200；
 * 2. 非 READY 文档（半成品）的 Chunk 不得进入降级检索（ARCHITECTURE.md §7.3）；
 * 3. 纯符号 query 提取不出关键词时同样空返回，不触碰数据库。
 * 向量端口固定抛异常强制走降级，因此不依赖 Chroma/Ollama，CI 可跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(classes = KeywordFallbackDegradationIntegrationTest.TestPorts.class)
@Transactional
class KeywordFallbackDegradationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private VectorStorePort vectorStore;
    @Autowired
    private EmbeddingPort embeddingPort;

    @BeforeEach
    void forceVectorPathOffline() {
        reset(vectorStore, embeddingPort);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.collectionInfo()).thenReturn(Optional.empty());
        when(vectorStore.search(any(), any(Integer.class), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("offline"));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void emptyKnowledgeBaseDegradesToEmptyResultRatherThanSqlError() throws Exception {
        String tenantA = "tenant-a-" + UUID.randomUUID();
        String tenantB = "tenant-b-" + UUID.randomUUID();
        String documentA = UUID.randomUUID().toString();
        // A 的 READY 文档与命中关键词的 Chunk 真实存在：B 的空结果必须来自租户过滤，而非库真空
        insertDocument(documentA, tenantA, "A-ready-document", "READY");
        insertChunk(documentA + "#0#1", documentA, tenantA, "alpha beta corpus");

        String tokenB = jwtService.issue(tenantB);
        mockMvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        // 请求体携带他人身份也不得生效：身份只来自令牌
                        .content("{\"query\":\"alpha beta\",\"topK\":5,\"userId\":\"" + tenantA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.searchMode").value("KEYWORD_FALLBACK"))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(content().string(not(containsString("alpha beta"))));
    }

    @Test
    void nonReadyDocumentDoesNotEnterDegradedSearch() throws Exception {
        String tenantA = "tenant-a-" + UUID.randomUUID();
        String documentA = UUID.randomUUID().toString();
        insertDocument(documentA, tenantA, "A-failed-document", "FAILED");
        // Chunk 行真实存在且恰好过闸门（2 个关键词全命中），只要 READY 过滤失效就会漏出
        insertChunk(documentA + "#0#1", documentA, tenantA, "alpha beta remnant");

        String tokenA = jwtService.issue(tenantA);
        mockMvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"query\":\"alpha beta\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.searchMode").value("KEYWORD_FALLBACK"))
                .andExpect(jsonPath("$.data.degraded").value(true));
    }

    @Test
    void symbolOnlyQueryDegradesToEmptyResult() throws Exception {
        String tokenA = jwtService.issue("tenant-a-" + UUID.randomUUID());
        mockMvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"query\":\"!!!@@@\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.searchMode").value("KEYWORD_FALLBACK"))
                .andExpect(jsonPath("$.data.degraded").value(true));
    }

    private void insertDocument(String id, String userId, String name, String status) {
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, status, index_version, chunk_count, error_message) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', ?, 1, 1, ?)",
                id, userId, name, status, "READY".equals(status) ? null : "测试种子：非 READY 状态");
    }

    private void insertChunk(String vectorId, String documentId, String userId, String text) {
        jdbcTemplate.update("INSERT INTO kb_chunk "
                        + "(vector_id, document_id, user_id, doc_name, doc_type, section_path, seq, text, "
                        + "char_start, char_end, index_version) "
                        + "VALUES (?, ?, ?, ?, 'PLAIN_TEXT', '/', 0, ?, 0, 10, 1)",
                vectorId, documentId, userId, userId + "-document", text);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPorts {

        @Bean
        @Primary
        EmbeddingPort embeddingPort() {
            return org.mockito.Mockito.mock(EmbeddingPort.class);
        }

        @Bean
        @Primary
        VectorStorePort vectorStorePort() {
            return org.mockito.Mockito.mock(VectorStorePort.class);
        }

        @Bean
        @Primary
        ChatPort chatPort() {
            return org.mockito.Mockito.mock(ChatPort.class);
        }
    }
}

package com.jobpilot.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(classes = TenantIsolationIntegrationTest.TestPorts.class)
@Transactional
class TenantIsolationIntegrationTest extends MySqlIntegrationTestBase {

    private static final String TENANT_A = "tenant-a-";
    private static final String TENANT_B = "tenant-b-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private KbDocumentMapper documentMapper;
    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private VectorStorePort vectorStore;
    @Autowired
    private EmbeddingPort embeddingPort;

    private String tenantA;
    private String tenantB;
    private String documentA;
    private String documentB;
    private String vectorA;
    private String vectorB;

    @BeforeEach
    void setUp() {
        tenantA = TENANT_A + UUID.randomUUID();
        tenantB = TENANT_B + UUID.randomUUID();
        documentA = UUID.randomUUID().toString();
        documentB = UUID.randomUUID().toString();
        vectorA = documentA + "#0#1";
        vectorB = documentB + "#0#1";
        insertDocument(documentA, tenantA, "A-private-resume");
        insertDocument(documentB, tenantB, "B-private-resume");
        insertChunk(vectorA, documentA, tenantA, "A private keyword");
        insertChunk(vectorB, documentB, tenantB, "B private keyword");
        reset(vectorStore, embeddingPort);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.collectionInfo()).thenReturn(Optional.empty());
        when(vectorStore.search(any(), any(Integer.class), any(), anyBoolean())).thenThrow(new IllegalStateException("offline"));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void tenantLineFiltersSelectByIdSelectByIdsAndSelectList() {
        UserContext.set(tenantA);

        assertThat(documentMapper.selectById(documentA).getId()).isEqualTo(documentA);
        assertThat(documentMapper.selectById(documentB)).isNull();
        assertThat(chunkMapper.selectByIds(List.of(vectorA, vectorB)))
                .extracting(KbChunkEntity::getVectorId)
                .containsExactly(vectorA);
        assertThat(documentMapper.selectList(new QueryWrapper<>()))
                .extracting(KbDocumentEntity::getId)
                .containsExactly(documentA);
    }

    @Test
    void tenantLineFailsClosedWithoutContext() {
        assertThatThrownBy(() -> documentMapper.selectById(documentA))
                .hasRootCauseInstanceOf(com.jobpilot.common.UnauthorizedException.class);
    }

    @Test
    void authenticatedTenantCannotReadOrSearchAnotherTenant() throws Exception {
        String tokenA = jwtService.issue(tenantA);

        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":{\"id\":\"" + documentA + "\"}}"));
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("B-private-resume"))));
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content("{\"query\":\"private keyword\",\"topK\":5,\"userId\":\"" + tenantB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A private keyword")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("B private keyword"))));
        assertThat(UserContext.get()).isNull();

        String tokenB = jwtService.issue(tenantB);
        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void invalidAndMissingTokensDoNotLeaveContextBehind() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentA))
                .andExpect(status().isUnauthorized());
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentA)
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(UserContext.get()).isNull();
    }

    private void insertDocument(String id, String userId, String name) {
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, status, index_version, chunk_count) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', 'READY', 1, 1)",
                id, userId, name);
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

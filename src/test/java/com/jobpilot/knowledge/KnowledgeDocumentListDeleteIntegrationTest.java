package com.jobpilot.knowledge;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库文档「列表 / 删除」的真实 SQL 行为与跨租户隔离（PRD-FP-6）。
 * <p>
 * 播种用 {@code JdbcTemplate} 直接写表（**故意绕过租户拦截器**），才能造出两个租户的数据互相验证。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class KnowledgeDocumentListDeleteIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private DocumentIngestService ingestService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private String docA;
    private String docB;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
        docA = UUID.randomUUID().toString();
        docB = UUID.randomUUID().toString();
        insertDocument(docA, tenantA, "A-resume");
        insertChunk(docA + "#0#1", docA, tenantA);
        insertDocument(docB, tenantB, "B-resume");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listOnlyReturnsOwnDocuments() {
        UserContext.set(tenantA);
        assertThat(ingestService.list(null, 50)).extracting(KbDocumentEntity::getId)
                .contains(docA).doesNotContain(docB);

        UserContext.set(tenantB);
        assertThat(ingestService.list(null, 50)).extracting(KbDocumentEntity::getId)
                .contains(docB).doesNotContain(docA);
    }

    @Test
    void deleteRemovesDocumentAndItsChunks() {
        UserContext.set(tenantA);

        ingestService.delete(docA);

        assertThat(count("SELECT COUNT(*) FROM kb_document WHERE id = ?", docA)).isZero();
        assertThat(count("SELECT COUNT(*) FROM kb_chunk WHERE document_id = ?", docA)).isZero();
    }

    @Test
    void crossTenantDeleteIsNotFoundAndLeavesDocumentIntact() {
        UserContext.set(tenantB);

        assertThatThrownBy(() -> ingestService.delete(docA)).isInstanceOf(ApiException.class);

        // A 的文档仍在（B 删不动别人的）
        UserContext.set(tenantA);
        assertThat(ingestService.document(docA).getId()).isEqualTo(docA);
    }

    private int count(String sql, String id) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private void insertDocument(String id, String userId, String name) {
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, status, index_version, chunk_count) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', 'READY', 1, 1)",
                id, userId, name);
    }

    private void insertChunk(String vectorId, String documentId, String userId) {
        jdbcTemplate.update("INSERT INTO kb_chunk "
                        + "(vector_id, document_id, user_id, doc_name, doc_type, section_path, seq, text, "
                        + "char_start, char_end, index_version) "
                        + "VALUES (?, ?, ?, ?, 'PLAIN_TEXT', '/', 0, 'hi', 0, 2, 1)",
                vectorId, documentId, userId, userId + "-doc");
    }
}

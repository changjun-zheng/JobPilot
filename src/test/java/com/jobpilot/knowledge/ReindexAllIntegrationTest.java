package com.jobpilot.knowledge;

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

/**
 * 全量重建索引的真实 SQL 行为与跨租户隔离：只把**本租户**的 READY/FAILED 重置为 PENDING。
 * 租户条件由拦截器注入 UPDATE（本测试正是来确认它**确实改写了 UPDATE**的）。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class ReindexAllIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private DocumentIngestService ingestService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private String docAReady;
    private String docAFailed;
    private String docBReady;

    @BeforeEach
    void setUp() {
        tenantA = "tenant-a-" + UUID.randomUUID();
        tenantB = "tenant-b-" + UUID.randomUUID();
        docAReady = insert("READY", tenantA);
        docAFailed = insert("FAILED", tenantA);
        docBReady = insert("READY", tenantB);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void resetsOnlyCallerTenantReadyAndFailedDocs() {
        UserContext.set(tenantA);

        int reset = ingestService.reindexAll();

        assertThat(reset).isEqualTo(2); // A 的 READY + FAILED，各一
        assertThat(status(docAReady)).isEqualTo("PENDING");
        assertThat(status(docAFailed)).isEqualTo("PENDING");
        // 别的租户不受影响
        assertThat(status(docBReady)).isEqualTo("READY");
    }

    private String insert(String status, String userId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, status, index_version, chunk_count) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', ?, 1, 1)",
                id, userId, userId + "-doc", status);
        return id;
    }

    private String status(String id) {
        return jdbcTemplate.queryForObject("SELECT status FROM kb_document WHERE id = ?", String.class, id);
    }
}

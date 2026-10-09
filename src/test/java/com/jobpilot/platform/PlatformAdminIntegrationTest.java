package com.jobpilot.platform;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端写入口（公司/岗位/面经）+ 平台面经的同步索引与下架，走**真实 MySQL**。
 * <p>
 * 端口（嵌入/向量）被 mock：本类证明的是**写库与状态机**——公司落 platform_* 表、
 * 面经落 {@code kb_document}/{@code kb_chunk} 的 PLATFORM 行、下架清 Chunk、以及
 * 「写进去的平台面经**确实能被租户检索到**」这条端到端闭环。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
class PlatformAdminIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private PlatformAdminService adminService;
    @Autowired
    private PlatformDocumentService documentService;
    @Autowired
    private PlatformCatalogService catalogService;
    @Autowired
    private KnowledgeRetrievalService retrievalService;
    @Autowired
    private EmbeddingPort embeddingPort;
    @Autowired
    private VectorStorePort vectorStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenant;

    @BeforeEach
    void setUp() {
        tenant = "tenant-" + UUID.randomUUID();
        reset(embeddingPort, vectorStore);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1, 0.2, 0.3));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ── 目录写入口 ──────────────────────────────────────────────

    @Test
    void createdCompanyIsVisibleToUsersAndArchiveHidesIt() {
        var company = adminService.createCompany("某新公司", "MID_TECH", "金融", "中厂");

        // 用户侧目录（只读、ACTIVE 过滤）能查到
        assertThat(catalogService.companies(null, null))
                .extracting(c -> c.getId()).contains(company.getId());
        assertThat(catalogService.activeCompaniesByIds(List.of(company.getId()))).hasSize(1);

        adminService.archiveCompany(company.getId());

        // 下架后：用户目录查不到、面试选司（activeCompaniesByIds）也拿不到；管理列表仍可见
        assertThat(catalogService.activeCompaniesByIds(List.of(company.getId()))).isEmpty();
        assertThat(adminService.listCompanies())
                .extracting(c -> c.getId()).contains(company.getId());
    }

    @Test
    void unknownTierIsRejectedAtWriteTime() {
        // 挡在写入端：否则用户选中这家公司起面试时才 400（坏数据留给用户发现）
        assertThatThrownBy(() -> adminService.createCompany("坏档位公司", "NO_SUCH_TIER", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知公司档位");
    }

    @Test
    void positionLinkDrivesCompanyByPositionLookup() {
        var company = adminService.createCompany("关联测试公司", "STARTUP", null, null);
        var position = adminService.createPosition("测试岗位-" + UUID.randomUUID());

        assertThat(catalogService.companies(position.getName(), null)).isEmpty();

        adminService.attachPosition(company.getId(), position.getId());

        assertThat(catalogService.companies(position.getName(), null))
                .extracting(c -> c.getId()).contains(company.getId());
        assertThat(adminService.positionIdsOf(company.getId())).contains(position.getId());

        adminService.detachPosition(company.getId(), position.getId());
        assertThat(catalogService.companies(position.getName(), null)).isEmpty();
    }

    // ── 面经导入 → 可检索 → 下架 ────────────────────────────────

    @Test
    void importedInterviewExperienceBecomesRetrievableByTenantThenDisappearsOnArchive() {
        var company = adminService.createCompany("面经测试公司", "BIG_TECH", null, null);

        KbDocumentEntity doc = documentService.importDocument(
                "面经测试公司 · 后端一面", "INTERVIEW", company.getId(), "后端",
                "牛客网授权转载（运营备注）",
                "## 一面\n\n面试官问了 JVM 内存模型与垃圾回收。\n\n## 二面\n\n聊了并发与锁。");

        assertThat(doc.getStatus()).isEqualTo("READY");
        assertThat(doc.getChunkCount()).isGreaterThan(0);

        // 落库校验：平台行 user_id=NULL、owner=PLATFORM、source_note 已存
        String owner = jdbcTemplate.queryForObject(
                "SELECT owner FROM kb_document WHERE id = ?", String.class, doc.getId());
        String userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM kb_document WHERE id = ?", String.class, doc.getId());
        String sourceNote = jdbcTemplate.queryForObject(
                "SELECT source_note FROM kb_document WHERE id = ?", String.class, doc.getId());
        assertThat(owner).isEqualTo("PLATFORM");
        assertThat(userId).isNull();
        assertThat(sourceNote).contains("授权转载");

        Integer platformChunks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND owner = 'PLATFORM'",
                Integer.class, doc.getId());
        assertThat(platformChunks).isPositive();

        // 端到端：向量层（mock）返回该文档的 chunk → 租户按公司收窄检索能拿到它
        String vectorId = jdbcTemplate.queryForObject(
                "SELECT vector_id FROM kb_chunk WHERE document_id = ? ORDER BY seq LIMIT 1",
                String.class, doc.getId());
        when(vectorStore.search(any(), anyInt(), anyMap(), anyBoolean()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch(vectorId, 0.95)));

        UserContext.set(tenant);
        RetrievalResult hit = retrievalService.search(
                new RetrievalQuery(tenant, "垃圾回收 并发", 5, "INTERVIEW", List.of(company.getId())),
                UsageScenario.SEARCH);
        assertThat(hit.items()).extracting(RetrievedChunk::chunkId).contains(vectorId);

        // 下架 → Chunk 清掉、状态 ARCHIVED、向量清理被调用；检索不再命中
        documentService.archive(doc.getId());

        assertThat(documentService.require(doc.getId()).getStatus()).isEqualTo("ARCHIVED");
        Integer chunksAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ?", Integer.class, doc.getId());
        assertThat(chunksAfter).isZero();
        // 索引前的幂等清理也会调一次；只断言下架这次也清了向量
        verify(vectorStore, org.mockito.Mockito.atLeast(2)).deleteByDocumentId(doc.getId());

        RetrievalResult afterArchive = retrievalService.search(
                new RetrievalQuery(tenant, "垃圾回收 并发", 5, "INTERVIEW", List.of(company.getId())),
                UsageScenario.SEARCH);
        assertThat(afterArchive.items()).isEmpty();
    }

    @Test
    void importRequiresSourceNoteAndKnownActiveCompany() {
        // 来源/授权备注缺失 → 合规拒绝
        assertThatThrownBy(() -> documentService.importDocument(
                "无备注面经", "INTERVIEW", null, null, "  ", "正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源");

        // 公司不存在 → 404
        assertThatThrownBy(() -> documentService.importDocument(
                "面经", "INTERVIEW", "no-such-company", null, "备注", "正文"))
                .isInstanceOf(ApiException.class);

        // 公司已下架 → 400
        var company = adminService.createCompany("已下架公司", "STARTUP", null, null);
        adminService.archiveCompany(company.getId());
        assertThatThrownBy(() -> documentService.importDocument(
                "面经", "INTERVIEW", company.getId(), null, "备注", "正文"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已下架");
    }

    @Test
    void reindexRebuildsChunksAndArchivedDocumentCannotBeRevived() {
        var company = adminService.createCompany("重索引公司", "MID_TECH", null, null);
        KbDocumentEntity doc = documentService.importDocument(
                "重索引面经", "INTERVIEW", company.getId(), null, "备注", "## 标题\n\n正文内容若干。");
        Integer firstChunks = doc.getChunkCount();

        KbDocumentEntity reindexed = documentService.reindex(doc.getId());
        assertThat(reindexed.getStatus()).isEqualTo("READY");
        assertThat(reindexed.getChunkCount()).isEqualTo(firstChunks);

        documentService.archive(doc.getId());
        assertThatThrownBy(() -> documentService.reindex(doc.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已下架");
    }
}

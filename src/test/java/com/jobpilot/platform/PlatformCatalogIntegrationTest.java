package com.jobpilot.platform;

import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台公司/岗位目录的只读查询。种子由 {@code V11__platform_corpus.sql} 写入——
 * 平台表**无租户**、所有用户可读。
 */
@SpringBootTest
@ActiveProfiles("local")
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
@Transactional
class PlatformCatalogIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private PlatformCatalogService catalogService;

    @Test
    void listsAllPositions() {
        assertThat(catalogService.positions()).extracting(p -> p.getName())
                .contains("后端开发", "算法工程师", "前端开发");
    }

    @Test
    void findsCompaniesThatHireAGivenPosition() {
        // 「按岗位反查公司」——你描述的筛选入口
        assertThat(catalogService.companies("后端开发", null))
                .extracting(PlatformCompanyEntity::getName)
                .contains("字节跳动", "美团", "小米")
                .doesNotContain("某 AI 初创"); // 它只招算法
    }

    @Test
    void findsCompaniesByKeyword() {
        assertThat(catalogService.companies(null, "字节"))
                .extracting(PlatformCompanyEntity::getName).containsExactly("字节跳动");
    }

    @Test
    void unknownPositionYieldsEmpty() {
        assertThat(catalogService.companies("不存在的岗位", null)).isEmpty();
    }

    @Test
    void activeCompaniesByIdsReturnsOnlyRequestedActiveOnes() {
        // 命中数 == 去重后的请求数 才说明请求的 ID 都有效——调用方据此拒绝「无效/已下架」
        assertThat(catalogService.activeCompaniesByIds(List.of("comp-bytedance", "no-such-id")))
                .extracting(PlatformCompanyEntity::getId)
                .containsExactly("comp-bytedance");
    }
}

package com.jobpilot.memory;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.UserMemoryEntity;
import com.jobpilot.mapper.UserMemoryMapper;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 长期记忆的**跨租户隔离**。
 * <p>
 * 数据用 {@link JdbcTemplate} 播种：<b>故意绕过租户拦截器</b>，让两个租户的行都真实存在，
 * 才能证明「读不到 / 改不动」是拦截器的功劳，而不是库里本来就没有。
 * <p>
 * 记忆是第四个业务对象（前三个：文档、投递、审批草稿）。它比前几个更需要这条测试——
 * 记忆装的是求职偏好与面试弱点，泄漏的观感损失比一条投递记录严重。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class MemoryTenantIsolationIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private UserMemoryMapper memoryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private String memoryOfA;
    private String memoryOfB;

    @BeforeEach
    void setUp() {
        tenantA = "mem-a-" + UUID.randomUUID();
        tenantB = "mem-b-" + UUID.randomUUID();
        memoryOfA = UUID.randomUUID().toString();
        memoryOfB = UUID.randomUUID().toString();
        seed(memoryOfA, tenantA, "A 的弱点");
        seed(memoryOfB, tenantB, "B 的弱点");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void tenantCannotReadAnotherTenantsMemory() {
        UserContext.set(tenantA);

        assertThat(memoryService.get(memoryOfA).getContent()).isEqualTo("A 的弱点");
        // 不存在与跨租户对外不可区分
        assertThatThrownBy(() -> memoryService.get(memoryOfB))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记忆不存在");
    }

    @Test
    void listReturnsOnlyTheCurrentTenantsMemories() {
        UserContext.set(tenantA);

        List<UserMemoryEntity> memories = memoryService.list(null, null, 0);

        assertThat(memories).extracting(UserMemoryEntity::getId).containsExactly(memoryOfA);
    }

    @Test
    void typeAndStatusFiltersCannotLeakAcrossTenants() {
        // 给 B 造一条 A 没有的类型，过滤若失效就会漏出来
        jdbcTemplate.update("UPDATE user_memory SET type = 'PREPARATION_PLAN' WHERE id = ?", memoryOfB);
        UserContext.set(tenantA);

        assertThat(memoryService.list("PREPARATION_PLAN", "ACTIVE", 0)).isEmpty();
    }

    @Test
    void tenantCannotEditAnotherTenantsMemory() {
        UserContext.set(tenantA);

        assertThatThrownBy(() -> memoryService.update(memoryOfB,
                new MemoryService.Patch("被篡改", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记忆不存在");

        // 切到 B 的视角复核：一个字段都没被动过
        UserContext.set(tenantB);
        assertThat(memoryService.get(memoryOfB).getContent()).isEqualTo("B 的弱点");
    }

    @Test
    void updateAffectsZeroRowsWhenTheRowIsOutOfTenantScope() {
        UserContext.set(tenantA);

        // 绕过服务层的先读，直接打 mapper：证明「影响 0 行」是数据库层的事实
        int affected = memoryMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserMemoryEntity>()
                        .eq("id", memoryOfB)
                        .set("content", "被篡改"));

        assertThat(affected).isZero();
        UserContext.set(tenantB);
        assertThat(memoryService.get(memoryOfB).getContent()).isEqualTo("B 的弱点");
    }

    @Test
    void tenantCannotDeleteAnotherTenantsMemory() {
        UserContext.set(tenantA);

        assertThatThrownBy(() -> memoryService.delete(memoryOfB))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记忆不存在");

        UserContext.set(tenantB);
        assertThat(memoryService.get(memoryOfB)).isNotNull();
    }

    @Test
    void deleteAffectsZeroRowsWhenTheRowIsOutOfTenantScope() {
        UserContext.set(tenantA);

        int affected = memoryMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserMemoryEntity>()
                        .eq("id", memoryOfB));

        assertThat(affected).isZero();
        UserContext.set(tenantB);
        assertThat(memoryService.get(memoryOfB)).isNotNull();
    }

    @Test
    void findIdsBySourceDraftCannotSeeAnotherTenantsDraft() {
        jdbcTemplate.update("UPDATE user_memory SET source_draft_id = 'draft-x' WHERE id = ?", memoryOfB);
        UserContext.set(tenantA);

        // 按草稿回查也走同一条被拦截的查询路径，不会成为跨租户的旁路
        assertThat(memoryService.findIdsBySourceDraft("draft-x")).isEmpty();
    }

    /** 故意走原生 JDBC：绕过租户拦截器，让两个租户的行都真实落库 */
    private void seed(String id, String userId, String content) {
        jdbcTemplate.update("INSERT INTO user_memory "
                        + "(id, user_id, type, content, source, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'INTERVIEW_WEAKNESS', ?, '面试评估', 'ACTIVE', NOW(3), NOW(3))",
                id, userId, content);
    }
}

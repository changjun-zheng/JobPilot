package com.jobpilot.memory;

import com.jobpilot.common.ApiException;
import com.jobpilot.domain.UserMemoryEntity;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记忆查看 / 编辑 / 删除的语义（PRD-FP-4 给用户的三项权限）。
 * <p>
 * 跑真实 MySQL：部分更新的**三态约定**（缺省 = 不修改、空串 = 清空）与「来源字段不可改」
 * 这两条，只有落到数据库上才能证明「列真的没被动」或「真的变成了 NULL」。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class MemoryCrudIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TENANT = "crud-tenant";

    private String memoryId;

    @BeforeEach
    void setUp() {
        UserContext.set(TENANT);
        memoryId = UUID.randomUUID().toString();
        // 走 JDBC 播种：这些用例只关心读写语义，不必经审批链路
        jdbcTemplate.update("INSERT INTO user_memory "
                        + "(id, user_id, type, content, source, source_draft_id, confidence, note, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INTERVIEW_WEAKNESS', '原内容', '面试评估', ?, 0.80, '原说明', "
                        + "'ACTIVE', NOW(3), NOW(3))",
                memoryId, TENANT, UUID.randomUUID().toString());
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void patchChangesOnlyTheFieldsThatWereProvided() {
        memoryService.update(memoryId, new MemoryService.Patch("改后的内容", null, null, null));

        UserMemoryEntity memory = memoryService.get(memoryId);
        assertThat(memory.getContent()).isEqualTo("改后的内容");
        // 未提供的字段一个都不该动
        assertThat(memory.getNote()).isEqualTo("原说明");
        assertThat(memory.getType()).isEqualTo("INTERVIEW_WEAKNESS");
        assertThat(memory.getStatus()).isEqualTo("ACTIVE");
        assertThat(memory.getConfidence()).isNotNull();
    }

    @Test
    void emptyStringClearsTheNote() {
        memoryService.update(memoryId, new MemoryService.Patch(null, null, "", null));

        // 必须真的是 NULL——实体更新默认不写 NULL，靠缺省值是清不掉的
        assertThat(memoryService.get(memoryId).getNote()).isNull();
    }

    @Test
    void patchingToArchivedKeepsTheRow() {
        memoryService.update(memoryId, new MemoryService.Patch(null, null, null, "ARCHIVED"));

        // 归档 ≠ 删除：记录还在，只是不再视为当前有效
        UserMemoryEntity memory = memoryService.get(memoryId);
        assertThat(memory.getStatus()).isEqualTo("ARCHIVED");
        assertThat(memoryService.list(null, "ARCHIVED", 0)).extracting(UserMemoryEntity::getId)
                .containsExactly(memoryId);
    }

    @Test
    void unknownTypeOnPatchIsRejected() {
        assertThatThrownBy(() -> memoryService.update(memoryId,
                new MemoryService.Patch(null, "NOT_A_TYPE", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知记忆类型");

        assertThat(memoryService.get(memoryId).getType()).isEqualTo("INTERVIEW_WEAKNESS");
    }

    @Test
    void unknownStatusOnPatchIsRejected() {
        assertThatThrownBy(() -> memoryService.update(memoryId,
                new MemoryService.Patch(null, null, null, "DELETED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知记忆状态");
    }

    @Test
    void overlengthContentOnPatchIsRejectedRatherThanTruncated() {
        assertThatThrownBy(() -> memoryService.update(memoryId,
                new MemoryService.Patch("长".repeat(MemoryService.MAX_CONTENT + 1), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过");

        assertThat(memoryService.get(memoryId).getContent()).isEqualTo("原内容");
    }

    @Test
    void sourceAndConfidenceSurviveAnEdit() {
        memoryService.update(memoryId, new MemoryService.Patch("新内容", null, "新说明", null));

        // 来源与置信度记录「这条从哪来、当时多确信」，请求体里根本没有这两个字段
        UserMemoryEntity memory = memoryService.get(memoryId);
        assertThat(memory.getSource()).isEqualTo("面试评估");
        assertThat(memory.getSourceDraftId()).isNotNull();
        assertThat(memory.getConfidence()).isNotNull();
    }

    @Test
    void deleteRemovesTheRow() {
        memoryService.delete(memoryId);

        assertThatThrownBy(() -> memoryService.get(memoryId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记忆不存在");
    }

    @Test
    void unknownTypeOnListIsRejectedRatherThanReturningEmpty() {
        // 返回空列表会让调用方以为「用户没有这类记忆」，实际是参数拼错了
        assertThatThrownBy(() -> memoryService.list("BOGUS", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知记忆类型");
    }

    @Test
    void emptyStringFiltersAreTreatedAsAbsent() {
        // 查询参数传空串是客户端常见行为，应等价于「不过滤」，而不是去匹配空类型
        assertThat(memoryService.list("", "", 0)).extracting(UserMemoryEntity::getId)
                .containsExactly(memoryId);
    }
}

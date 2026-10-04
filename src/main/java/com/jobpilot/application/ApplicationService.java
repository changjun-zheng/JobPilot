package com.jobpilot.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.domain.ApplicationStatus;
import com.jobpilot.mapper.ApplicationMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递记录读写（PRD-FP-7）。
 *
 * <h3>租户隔离靠什么</h3>
 * 每条 SQL 都由 {@code TenantLineInnerInterceptor} 注入 {@code user_id}，所以：
 * 读别人的记录 = 查不到；改/删别人的记录 = 影响 0 行。业务代码里没有一处手写租户条件。
 *
 * <h3>两条必须写对的规则</h3>
 * <ol>
 *   <li><b>写入时 {@code userId} 由服务层显式设置</b>，且与 {@code UserContext} 比对。
 *       拦截器的 {@code ignoreInsert} 检测到插入列里已有 {@code user_id} 就跳过、<b>不覆盖</b>，
 *       所以「租户键强制注入」在写入侧并不成立——必须靠这里；</li>
 *   <li><b>改/删必须检查影响行数</b>。0 行意味着目标不存在或不属于本租户，
 *       不检查就会向调用方谎报「已更新」而数据一字未动。</li>
 * </ol>
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /** 与 DDL 的列宽一致；超长显式拒绝，不静默截断用户内容 */
    private static final int MAX_COMPANY = 255;
    private static final int MAX_POSITION = 255;
    private static final int MAX_SOURCE = 512;
    private static final int MAX_NOTES = 2000;

    /** 列表一次的返回上限；无分页拦截器（见 MybatisPlusConfig 注释），用 LIMIT 钳制 */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ApplicationMapper applicationMapper;
    private final KbDocumentMapper documentMapper;

    public ApplicationService(ApplicationMapper applicationMapper, KbDocumentMapper documentMapper) {
        this.applicationMapper = applicationMapper;
        this.documentMapper = documentMapper;
    }

    /** 创建命令；{@code userId} 由 Controller 从 {@code UserContext} 取 */
    public record CreateCommand(
            String userId,
            String company,
            String position,
            String status,
            LocalDate appliedAt,
            String source,
            String jdDocumentId,
            String notes
    ) {
    }

    /**
     * 部分更新。
     * <p>
     * 三态约定：{@code null} = 不修改；空串 = 清空（仅可空的字符串字段）；其他 = 设为该值。
     * 没有这条约定，写错的 {@code jdDocumentId} 就永远删不掉——实体更新默认的
     * {@code FieldStrategy.NOT_NULL} 不写 NULL。{@code appliedAt} 是日期，无「空串」态，只能设不能清。
     */
    public record Patch(
            String company,
            String position,
            String status,
            LocalDate appliedAt,
            String source,
            String jdDocumentId,
            String notes
    ) {
    }

    /** 列表查询；字段均为可选 */
    public record Query(
            ApplicationStatus status,
            LocalDate from,
            LocalDate to,
            int limit
    ) {
    }

    /** 统计结果：总数与各状态数量 */
    public record Stats(long total, Map<String, Long> byStatus) {
    }

    // ── 写 ──────────────────────────────────────────────────────

    public ApplicationEntity create(CreateCommand command) {
        requireConsistentTenant(command.userId());
        ApplicationEntity entity = new ApplicationEntity();
        // 租户键必须在这里显式设置：拦截器不会覆盖实体已带的 user_id（ignoreInsert 会跳过）
        entity.setUserId(command.userId());
        entity.setCompany(requireText(command.company(), "公司名称", MAX_COMPANY));
        entity.setPosition(requireText(command.position(), "岗位名称", MAX_POSITION));
        ApplicationStatus status = ApplicationStatus.parse(command.status());
        entity.setStatus(status.name());
        entity.setAppliedAt(command.appliedAt());
        entity.setSource(optionalText(command.source(), "来源/链接", MAX_SOURCE));
        entity.setNotes(optionalText(command.notes(), "备注", MAX_NOTES));
        entity.setJdDocumentId(validateJdDocument(command.jdDocumentId()));
        validateStatusDatePair(status, entity.getAppliedAt());

        applicationMapper.insert(entity);
        return entity;
    }

    /**
     * 部分更新：先读（租户范围内）确认存在，合并补丁，再写。
     * <p>
     * 先读的原因不只是校验：状态与投递日期的联动校验要看<b>合并后</b>的完整状态，
     * 而不是只看补丁里出现的字段。
     */
    @Transactional
    public ApplicationEntity update(String id, Patch patch) {
        ApplicationEntity existing = applicationMapper.selectById(id);
        if (existing == null) {
            // 不存在与跨租户对外不可区分——这是有意的
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在：" + id);
        }
        ApplicationEntity merged = merge(existing, patch);
        ApplicationStatus status = ApplicationStatus.parse(merged.getStatus());
        validateStatusDatePair(status, merged.getAppliedAt());

        UpdateWrapper<ApplicationEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("company", merged.getCompany())
                .set("position", merged.getPosition())
                .set("status", merged.getStatus())
                .set("applied_at", merged.getAppliedAt())
                .set("source", merged.getSource())
                .set("jd_document_id", merged.getJdDocumentId())
                .set("notes", merged.getNotes());
        int affected = applicationMapper.update(null, wrapper);
        if (affected == 0) {
            // 走到这里说明刚读到的行在本次事务内被删了（或拦截器把它过滤掉了）。
            // 无论哪种，都不能让调用方以为更新成功。
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在：" + id);
        }
        return applicationMapper.selectById(id);
    }

    /** 删除；影响 0 行即不存在或跨租户 */
    public void delete(String id) {
        int affected = applicationMapper.delete(new QueryWrapper<ApplicationEntity>().eq("id", id));
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在：" + id);
        }
    }

    // ── 读 ──────────────────────────────────────────────────────

    public ApplicationEntity get(String id) {
        ApplicationEntity entity = applicationMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在：" + id);
        }
        return entity;
    }

    public List<ApplicationEntity> query(Query query) {
        QueryWrapper<ApplicationEntity> wrapper = new QueryWrapper<>();
        if (query.status() != null) {
            wrapper.eq("status", query.status().name());
        }
        // applied_at 是 DATE；闭区间两端都取，同一天能查到
        if (query.from() != null) {
            wrapper.ge("applied_at", query.from());
        }
        if (query.to() != null) {
            wrapper.le("applied_at", query.to());
        }
        wrapper.orderByDesc("applied_at").orderByDesc("created_at");
        // limit 已解析为 int 并夹紧，无注入面；项目无分页拦截器，只能这样钳制
        wrapper.last("LIMIT " + clampLimit(query.limit()));
        return applicationMapper.selectList(wrapper);
    }

    /**
     * 按状态统计。
     * <p>
     * <b>七种状态全部零填充</b>，且 {@code total} 取自各分组之和而非另发一次 count——
     * 两次查询的条件若不一致，会出现「总数与明细对不上」而没人发现。
     * 库里出现未识别的状态值（正常不可达，写入已校验）时归入 {@code UNKNOWN} 桶并告警，
     * 而不是丢弃，以保证各桶之和恒等于 {@code total}。
     */
    @Transactional(readOnly = true)
    public Stats stats(LocalDate from, LocalDate to) {
        QueryWrapper<ApplicationEntity> wrapper = new QueryWrapper<>();
        wrapper.select("status", "count(*) as cnt").groupBy("status");
        if (from != null) {
            wrapper.ge("applied_at", from);
        }
        if (to != null) {
            wrapper.le("applied_at", to);
        }
        // 注意：不要把 user_id 加进 GROUP BY——租户条件由拦截器注入 WHERE，
        // 过滤发生在分组之前，每个分组本来就只含当前租户。
        List<Map<String, Object>> rows = applicationMapper.selectMaps(wrapper);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        long total = 0;
        for (Map<String, Object> row : rows) {
            Object rawStatus = row.get("status");
            String key = rawStatus == null ? null : String.valueOf(rawStatus);
            long count = row.get("cnt") instanceof Number number ? number.longValue() : 0L;
            total += count;
            if (key != null && byStatus.containsKey(key)) {
                byStatus.merge(key, count, Long::sum);
            } else {
                log.warn("统计遇到未识别的投递状态，归入 UNKNOWN：{}", key);
                byStatus.merge("UNKNOWN", count, Long::sum);
            }
        }
        return new Stats(total, byStatus);
    }

    // ── 校验 ────────────────────────────────────────────────────

    /**
     * 租户一致性：防止未来新增调用方绕过 Controller 注入别的租户。
     * <p>
     * 与 {@code DocumentIngestService.enqueue} 同一约定——命令保留 {@code userId} 是为了让用例
     * 能脱离 ThreadLocal 测试，但真入口必须与上下文一致。
     */
    private void requireConsistentTenant(String commandUserId) {
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(commandUserId)) {
            throw new com.jobpilot.common.UnauthorizedException("租户上下文与记录归属不一致");
        }
    }

    /** 「已投递但没日期」语义不自洽：非 WISHLIST 必须给出投递日期 */
    private void validateStatusDatePair(ApplicationStatus status, LocalDate appliedAt) {
        if (status != ApplicationStatus.WISHLIST && appliedAt == null) {
            throw new IllegalArgumentException("状态为 " + status.name() + " 时必须提供投递日期");
        }
    }

    /** 关联的 JD 必须在当前租户内可见；查不到（含他人的文档）即拒绝 */
    private String validateJdDocument(String jdDocumentId) {
        if (jdDocumentId == null || jdDocumentId.isBlank()) {
            return null;
        }
        String trimmed = jdDocumentId.strip();
        if (documentMapper.selectById(trimmed) == null) {
            throw new IllegalArgumentException("关联的 JD 文档不存在：" + trimmed);
        }
        return trimmed;
    }

    private ApplicationEntity merge(ApplicationEntity existing, Patch patch) {
        ApplicationEntity merged = new ApplicationEntity();
        merged.setId(existing.getId());
        merged.setUserId(existing.getUserId());
        merged.setCompany(patch.company() == null
                ? existing.getCompany()
                : requireText(patch.company(), "公司名称", MAX_COMPANY));
        merged.setPosition(patch.position() == null
                ? existing.getPosition()
                : requireText(patch.position(), "岗位名称", MAX_POSITION));
        merged.setStatus(patch.status() == null ? existing.getStatus() : patch.status());
        merged.setAppliedAt(patch.appliedAt() == null ? existing.getAppliedAt() : patch.appliedAt());
        merged.setSource(patch.source() == null
                ? existing.getSource()
                : emptyToNull(patch.source(), "来源/链接", MAX_SOURCE));
        merged.setNotes(patch.notes() == null
                ? existing.getNotes()
                : emptyToNull(patch.notes(), "备注", MAX_NOTES));
        merged.setJdDocumentId(patch.jdDocumentId() == null
                ? existing.getJdDocumentId()
                : validateJdDocument(emptyToNull(patch.jdDocumentId(), "关联 JD", 64)));
        return merged;
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return checkLength(value.strip(), field, maxLength);
    }

    private String optionalText(String value, String field, int maxLength) {
        return value == null || value.isBlank() ? null : checkLength(value.strip(), field, maxLength);
    }

    /** 空串 = 清空（见 {@link Patch} 的三态约定） */
    private String emptyToNull(String value, String field, int maxLength) {
        return value.isBlank() ? null : checkLength(value.strip(), field, maxLength);
    }

    private String checkLength(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "超过长度上限 " + maxLength + "（当前 " + value.length() + "）");
        }
        return value;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}

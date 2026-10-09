package com.jobpilot.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.config.InterviewProperties;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.domain.PlatformCompanyPositionEntity;
import com.jobpilot.domain.PlatformPositionEntity;
import com.jobpilot.mapper.PlatformCompanyMapper;
import com.jobpilot.mapper.PlatformCompanyPositionMapper;
import com.jobpilot.mapper.PlatformPositionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 平台目录的**管理写入口**（管理端专属；用户侧 {@link PlatformCatalogService} 仍只读）。
 * <p>
 * 全部走 platform_* 表（无租户），要求管理令牌（由 {@code AdminAuthInterceptor} 把关）。
 * 「删除」一律用**下架**（status → ARCHIVED）：目录数据被历史面试的关联行引用
 * （快照了公司名/档位），物理删除会让审计断链；下架行对用户不可见（只读查询恒带 status='ACTIVE'）。
 */
@Service
public class PlatformAdminService {

    private final PlatformCompanyMapper companyMapper;
    private final PlatformPositionMapper positionMapper;
    private final PlatformCompanyPositionMapper companyPositionMapper;
    private final InterviewProperties interviewProps;

    public PlatformAdminService(PlatformCompanyMapper companyMapper,
                                PlatformPositionMapper positionMapper,
                                PlatformCompanyPositionMapper companyPositionMapper,
                                InterviewProperties interviewProps) {
        this.companyMapper = companyMapper;
        this.positionMapper = positionMapper;
        this.companyPositionMapper = companyPositionMapper;
        this.interviewProps = interviewProps;
    }

    // ── 公司 ────────────────────────────────────────────────────

    /** 管理列表：**含已下架**（用户侧目录恒只返回 ACTIVE）；按名称排序 */
    public List<PlatformCompanyEntity> listCompanies() {
        return companyMapper.selectList(new QueryWrapper<PlatformCompanyEntity>().orderByAsc("name"));
    }

    /** 某公司已关联的岗位 id（管理界面回显用） */
    public List<String> positionIdsOf(String companyId) {
        requireCompany(companyId);
        return companyPositionMapper.selectList(new QueryWrapper<PlatformCompanyPositionEntity>()
                        .eq("company_id", companyId))
                .stream()
                .map(PlatformCompanyPositionEntity::getPositionId)
                .toList();
    }

    @Transactional
    public PlatformCompanyEntity createCompany(String name, String tier, String industry, String tags) {
        String trimmed = requireText(name, "公司名");
        PlatformCompanyEntity company = new PlatformCompanyEntity();
        company.setId(UUID.randomUUID().toString());
        company.setName(trimmed);
        company.setTier(requireKnownTier(tier));
        company.setIndustry(blankToNull(industry));
        company.setTags(blankToNull(tags));
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    @Transactional
    public PlatformCompanyEntity updateCompany(String id, String name, String tier,
                                               String industry, String tags) {
        PlatformCompanyEntity company = requireCompany(id);
        // 仅覆盖传了的字段：管理界面的表单未必每次全量提交
        if (name != null && !name.isBlank()) {
            company.setName(name.strip());
        }
        if (tier != null && !tier.isBlank()) {
            company.setTier(requireKnownTier(tier));
        }
        if (industry != null) {
            company.setIndustry(blankToNull(industry));
        }
        if (tags != null) {
            company.setTags(blankToNull(tags));
        }
        companyMapper.updateById(company);
        return company;
    }

    /** 下架公司：用户侧目录与面试选司立刻不可见；历史面试靠快照照常可读 */
    @Transactional
    public void archiveCompany(String id) {
        PlatformCompanyEntity company = requireCompany(id);
        company.setStatus("ARCHIVED");
        companyMapper.updateById(company);
    }

    /** 重新上架 */
    @Transactional
    public PlatformCompanyEntity activateCompany(String id) {
        PlatformCompanyEntity company = requireCompany(id);
        company.setStatus("ACTIVE");
        companyMapper.updateById(company);
        return company;
    }

    // ── 岗位 ────────────────────────────────────────────────────

    @Transactional
    public PlatformPositionEntity createPosition(String name) {
        String trimmed = requireText(name, "岗位名");
        Long exists = positionMapper.selectCount(new QueryWrapper<PlatformPositionEntity>()
                .eq("name", trimmed));
        if (exists != null && exists > 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "岗位已存在：" + trimmed);
        }
        PlatformPositionEntity position = new PlatformPositionEntity();
        position.setId(UUID.randomUUID().toString());
        position.setName(trimmed);
        positionMapper.insert(position);
        return position;
    }

    // ── 公司×岗位关联 ───────────────────────────────────────────

    @Transactional
    public void attachPosition(String companyId, String positionId) {
        requireCompany(companyId);
        PlatformPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "岗位不存在：" + positionId);
        }
        PlatformCompanyPositionEntity link = new PlatformCompanyPositionEntity();
        link.setCompanyId(companyId);
        link.setPositionId(positionId);
        try {
            companyPositionMapper.insert(link);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 幂等：重复关联按成功处理（管理界面可能重复提交）
        }
    }

    @Transactional
    public void detachPosition(String companyId, String positionId) {
        companyPositionMapper.delete(new QueryWrapper<PlatformCompanyPositionEntity>()
                .eq("company_id", companyId)
                .eq("position_id", positionId));
    }

    // ── 内部 ────────────────────────────────────────────────────

    private PlatformCompanyEntity requireCompany(String id) {
        PlatformCompanyEntity company = companyMapper.selectById(id);
        if (company == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "公司不存在：" + id);
        }
        return company;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.strip();
    }

    /**
     * 档位必须是 {@code jobpilot.interview.tiers} 的键——否则用户选中这家公司起面试时，
     * {@code InterviewService.resolveHardestTier} 会当场 400。**在写入端拦住，不把坏数据留给用户发现。**
     */
    private String requireKnownTier(String tier) {
        String key = requireText(tier, "难度档位").toUpperCase(Locale.ROOT);
        interviewProps.tier(key); // 未知档位抛 IllegalArgumentException → 400
        return key;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

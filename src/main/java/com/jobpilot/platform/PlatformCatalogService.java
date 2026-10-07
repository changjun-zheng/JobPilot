package com.jobpilot.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.domain.PlatformPositionEntity;
import com.jobpilot.mapper.PlatformCompanyMapper;
import com.jobpilot.mapper.PlatformPositionMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 平台公司/岗位目录查询（全局数据，无租户）。
 * <p>
 * 平台表在 {@code TENANT_EXEMPT_TABLES} 里——它们没有 {@code user_id}，是所有用户共享的数据。
 * 本切片**只读**：写入入口属管理端（后续）。
 */
@Service
public class PlatformCatalogService {

    private final PlatformCompanyMapper companyMapper;
    private final PlatformPositionMapper positionMapper;

    public PlatformCatalogService(PlatformCompanyMapper companyMapper,
                                  PlatformPositionMapper positionMapper) {
        this.companyMapper = companyMapper;
        this.positionMapper = positionMapper;
    }

    /**
     * 查公司。{@code positionName} 非空 → 只返回**招这个岗位**的公司（按岗位反查）；
     * 否则按 {@code keyword} 模糊搜公司名（关键词为空则返回全部上架公司）。
     */
    public List<PlatformCompanyEntity> companies(String positionName, String keyword) {
        if (positionName != null && !positionName.isBlank()) {
            PlatformPositionEntity position = positionMapper.selectOne(
                    new QueryWrapper<PlatformPositionEntity>().eq("name", positionName.strip()));
            if (position == null) {
                return List.of();
            }
            return companyMapper.selectByPositionId(position.getId());
        }
        QueryWrapper<PlatformCompanyEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "ACTIVE");
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like("name", keyword.strip());
        }
        wrapper.orderByAsc("name");
        return companyMapper.selectList(wrapper);
    }

    /** 全部岗位（供界面下拉） */
    public List<PlatformPositionEntity> positions() {
        return positionMapper.selectList(
                new QueryWrapper<PlatformPositionEntity>().orderByAsc("name"));
    }

    /**
     * 按 ID 批量取**在架**公司（面试选公司用）。
     * <p>
     * 只返回 {@code status='ACTIVE'} 的行——调用方据此校验「请求的 ID 是否都有效」：
     * 返回条数少于去重后的请求 ID 数，说明存在无效 ID 或已下架公司，应当拒绝而不是静默丢弃。
     * 结果按公司名升序（确定顺序，便于快照与展示）。
     */
    public List<PlatformCompanyEntity> activeCompaniesByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return companyMapper.selectList(new QueryWrapper<PlatformCompanyEntity>()
                .in("id", ids)
                .eq("status", "ACTIVE")
                .orderByAsc("name"));
    }
}

package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.PlatformCompanyEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台公司目录。
 * <p>
 * {@code platform_company} 在 {@code TENANT_EXEMPT_TABLES} 里——它**没有 {@code user_id}**，
 * 是全局数据（所有用户可见），因此这里**没有也不需要**任何租户条件。
 */
public interface PlatformCompanyMapper extends BaseMapper<PlatformCompanyEntity> {

    /** 招某个岗位的公司（联 {@code platform_company_position}）——支撑「按岗位反查公司」 */
    @Select("""
            SELECT c.* FROM platform_company c
            JOIN platform_company_position cp ON cp.company_id = c.id
            WHERE cp.position_id = #{positionId} AND c.status = 'ACTIVE'
            ORDER BY c.name
            """)
    List<PlatformCompanyEntity> selectByPositionId(@Param("positionId") String positionId);
}

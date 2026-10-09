package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 公司×岗位关联（全局，**无租户**）。复合主键 ({@code companyId}, {@code positionId})。
 */
@TableName("platform_company_position")
public class PlatformCompanyPositionEntity {

    private String companyId;
    private String positionId;

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getPositionId() {
        return positionId;
    }

    public void setPositionId(String positionId) {
        this.positionId = positionId;
    }
}

package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 面试会话 × 平台公司关联（多家混合成一套题，BRD US-3）。
 * <p>
 * 会话实体上另有**展示用快照**（{@code company} = 公司名顿号连接）；本表存结构化的「关联了哪几家」，
 * 且每行都带 {@code companyName} / {@code tier} 快照——公司在平台库里改名或下架后，历史面试仍可读。
 * <p>
 * 租户表：写入时 {@code setUserId} 显式设置（拦截器不覆盖实体已带的 user_id）。
 */
@TableName("interview_session_company")
public class InterviewSessionCompanyEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String sessionId;
    /** 引用 platform_company.id */
    private String companyId;
    /** 创建时快照的公司名 */
    private String companyName;
    /** 创建时快照的公司档位（InterviewProperties.tiers 的键） */
    private String tier;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

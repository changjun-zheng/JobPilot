package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 平台公司目录（全局，**无租户**）。
 * <p>
 * 该表在 {@code TENANT_EXEMPT_TABLES} 里——它没有 {@code user_id}，且**所有用户可见**。
 * 写入入口属管理端（不在当前切片），本切片只有只读查询。
 */
@TableName("platform_company")
public class PlatformCompanyEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    /** 难度档位：对应 jobpilot.interview.tiers 的键（BIG_TECH / MID_TECH / STARTUP） */
    private String tier;
    private String industry;
    private String tags;
    /** ACTIVE / ARCHIVED（下架） */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

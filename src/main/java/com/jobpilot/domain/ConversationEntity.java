package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一次连续对话（PRD-FP-2.1）。1:N 关系下挂 {@link ConversationMessageEntity}。
 * <p>
 * 由首轮 run 服务端新建；{@code title} 由首条用户消息派生（PRD 契约未定义该字段，本实现补齐，
 * 仅供历史列表显示）。
 */
@TableName("conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String title;
    private LocalDateTime createdAt;
    /** 最近活跃时间；每次追加消息时 touch，列表按它倒序 */
    private LocalDateTime updatedAt;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

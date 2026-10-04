package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆条目（PRD-FP-4）。
 * <p>
 * 短小、结构化、用户级的偏好或弱点，<b>不替代 RAG 文档库</b>：正文上限 512 字就是这条分工的强制点。
 */
@TableName("user_memory")
public class UserMemoryEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器不会覆盖实体已带的 user_id */
    private String userId;
    /** MemoryType 名称 */
    private String type;
    private String content;
    /** 来源标签，如「面试评估」 */
    private String source;
    /** 产生该记忆的审批草稿 ID；回查「哪些候选被写入」用它 */
    private String sourceDraftId;
    private BigDecimal confidence;
    private String note;
    /** MemoryStatus 名称 */
    private String status;
    private LocalDateTime createdAt;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceDraftId() {
        return sourceDraftId;
    }

    public void setSourceDraftId(String sourceDraftId) {
        this.sourceDraftId = sourceDraftId;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

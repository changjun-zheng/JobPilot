package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("kb_document")
public class KbDocumentEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    /** USER / PLATFORM。平台行（PLATFORM）的 userId 为 null；DB 用 CHECK 约束保证「USER 行必有 userId」 */
    private String owner;
    /** 关联的平台公司（面经用；可选，仅平台行有意义） */
    private String companyId;
    private String name;
    /** MARKDOWN / PLAIN_TEXT */
    private String docType;
    private String tags;
    /** 来源/授权备注（合规，设计草案 §8）；平台导入强制填写，租户导入为 NULL */
    private String sourceNote;
    /** DocumentStatus 名称 */
    private String status;
    private Integer indexVersion;
    private Integer chunkCount;
    /** 导入原文（I-1c 起落库供 worker 异步索引）。select=false：状态查询与检索回捞不拖大字段，认领走原生 SQL 仍会带上 */
    @TableField(select = false)
    private String content;
    /** ALWAYS：终态写入要能把 error_message 置回 NULL（成功路径） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    /** ALWAYS：重排队后回到 PENDING 要能把 next_retry_at 置回 NULL（READY/FAILED 终态） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;
    private Integer retryCount;
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

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getSourceNote() {
        return sourceNote;
    }

    public void setSourceNote(String sourceNote) {
        this.sourceNote = sourceNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(Integer indexVersion) {
        this.indexVersion = indexVersion;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
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

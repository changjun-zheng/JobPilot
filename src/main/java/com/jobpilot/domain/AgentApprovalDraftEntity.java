package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * HITL 审批草稿（ARCHITECTURE.md §4.4）。
 * <p>
 * 写入类工具不直接执行副作用，而是落一条本记录并返回 {@code PENDING_APPROVAL}；
 * 用户经独立接口审批后才真正写入。模型<b>没有</b>绕过审批的备用工具。
 */
@TableName("agent_approval_draft")
public class AgentApprovalDraftEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String traceId;
    private String conversationId;
    private String toolName;
    /** 待执行的写入载荷；审批通过后才被消费 */
    private String payloadJson;
    /** PENDING / APPROVED / REJECTED（EXPIRED 为 PRD 预留，本期不实现过期） */
    private String status;
    private LocalDateTime decidedAt;
    /** 审批者 user_id；不变量：恒等于 userId */
    private String decidedBy;
    /** sha256(traceId|toolName|conversationId|规范化载荷)；配合唯一键防重复落库 */
    private String idempotencyKey;
    /** 审批通过并执行后产生的资源 ID（如新建文档 ID） */
    private String resultRef;
    /**
     * 部分审批时用户勾选的候选 ID 数组（JSON，意图）。
     * <p>
     * 与「实际写入的行」（按 {@code user_memory.source_draft_id} 回查）分开存：用户删掉某条记忆后，
     * 效果侧的痕迹就没了，而**审批记录必须留存**——这正是 HITL 审计的意义。
     * 整批通过或单条工具为 null。
     */
    private String approvalSelection;
    private LocalDateTime executedAt;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getResultRef() {
        return resultRef;
    }

    public void setResultRef(String resultRef) {
        this.resultRef = resultRef;
    }

    public String getApprovalSelection() {
        return approvalSelection;
    }

    public void setApprovalSelection(String approvalSelection) {
        this.approvalSelection = approvalSelection;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }
    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
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

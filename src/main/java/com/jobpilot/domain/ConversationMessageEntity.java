package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 会话中的一条消息（PRD-FP-2.1）。
 * <p>
 * 只落 USER / ASSISTANT 两种角色（见 {@link MessageRole}）。助手行携带 {@code stepsJson}
 * （工具步骤摘要，供对话页展示）与 {@code traceId}（可回放到 {@code agent_trace}）。
 * <p>
 * {@code stepsJson} 是 JSON 列，实体字段用 {@code String}——与 {@code AgentApprovalDraftEntity.payloadJson}
 * 同一写法（MySQL 会把字符串按 JSON 校验后存入）。
 */
@TableName("conversation_message")
public class ConversationMessageEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String conversationId;
    /** {@link MessageRole} 名称：USER / ASSISTANT */
    private String role;
    private String content;
    /** 助手消息的工具步骤摘要（JSON 数组）；用户消息为 null */
    private String stepsJson;
    private String traceId;
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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStepsJson() {
        return stepsJson;
    }

    public void setStepsJson(String stepsJson) {
        this.stepsJson = stepsJson;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一条面试消息（面试官提问 / 候选人作答）。见 {@code interview_message} 表注释。
 */
@TableName("interview_message")
public class InterviewMessageEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String sessionId;
    /** InterviewRole 名称：INTERVIEWER / CANDIDATE */
    private String role;
    /** 该消息所属阶段（InterviewPhase 名称） */
    private String phase;
    private Integer roundNo;
    /** 会话内单调序号——created_at 同毫秒也不乱序 */
    private Integer seq;
    private String content;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

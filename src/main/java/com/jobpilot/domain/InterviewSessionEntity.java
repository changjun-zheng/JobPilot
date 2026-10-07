package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 模拟面试会话（BRD US-3）。
 * <p>
 * 阶段推进由服务端状态机裁决（{@code InterviewStateMachine}），本实体只是状态的持久化载体：
 * {@code currentPhase} / {@code currentRound} / {@code questionsInRound} 三者共同决定下一步。
 * <p>
 * {@code resolvedDifficulty} 是**创建时的快照**——改配置不改写历史会话。
 */
@TableName("interview_session")
public class InterviewSessionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 租户键。写入时必须由服务层显式设置——拦截器<b>不会</b>覆盖实体已带的 user_id */
    private String userId;
    private String company;
    private String position;
    /** 公司档位（InterviewProperties.tiers 的键） */
    private String tier;
    /** 用户覆盖难度；空表示用档位预设 */
    private String difficultyOverride;
    /** 创建时快照的难度（Difficulty 名称） */
    private String resolvedDifficulty;
    /** InterviewPhase 名称 */
    private String currentPhase;
    private Integer currentRound;
    private Integer totalRounds;
    private Integer questionsInRound;
    /** InterviewStatus 名称 */
    private String status;
    /** 评估报告（JSON） */
    private String reportJson;
    /** 弱点候选的审批草稿 ID */
    private String draftId;
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

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getDifficultyOverride() {
        return difficultyOverride;
    }

    public void setDifficultyOverride(String difficultyOverride) {
        this.difficultyOverride = difficultyOverride;
    }

    public String getResolvedDifficulty() {
        return resolvedDifficulty;
    }

    public void setResolvedDifficulty(String resolvedDifficulty) {
        this.resolvedDifficulty = resolvedDifficulty;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    public Integer getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(Integer currentRound) {
        this.currentRound = currentRound;
    }

    public Integer getTotalRounds() {
        return totalRounds;
    }

    public void setTotalRounds(Integer totalRounds) {
        this.totalRounds = totalRounds;
    }

    public Integer getQuestionsInRound() {
        return questionsInRound;
    }

    public void setQuestionsInRound(Integer questionsInRound) {
        this.questionsInRound = questionsInRound;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public String getDraftId() {
        return draftId;
    }

    public void setDraftId(String draftId) {
        this.draftId = draftId;
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

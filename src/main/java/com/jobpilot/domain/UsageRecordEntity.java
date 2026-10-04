package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * FP-10 用量计量行（I-3c 第一阶段只归集、不结算）。
 * <p>
 * 各维度的专属字段允许为 NULL：一张表承载三个维度（PRD 的 UsageRecord 是同一个对象），
 * 维度无关的列就是 NULL——用 JSON 列装「通用数值」会把聚合 SQL 变成字符串处理，不做。
 * 字段语义见 {@code V8__usage_record.sql} 与 {@code UsageRecorder} 的口径注释。
 */
@TableName("usage_record")
public class UsageRecordEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String dimension;
    private String scenario;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer charCount;
    private Integer callCount;
    private Integer iterations;
    private Integer toolCalls;
    private String status;
    private String traceId;
    private String documentId;
    private String requestId;

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

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getCallCount() {
        return callCount;
    }

    public void setCallCount(Integer callCount) {
        this.callCount = callCount;
    }

    public Integer getIterations() {
        return iterations;
    }

    public void setIterations(Integer iterations) {
        this.iterations = iterations;
    }

    public Integer getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(Integer toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}

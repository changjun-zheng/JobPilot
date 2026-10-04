package com.jobpilot.usage;

/**
 * {@code UsageRecordMapper.aggregate} 的投影行（MyBatis 按列别名自动映射）。
 * 数值聚合结果一律非 null（SQL 里已 COALESCE）。
 */
public class UsageAggregateRow {

    private String dimension;
    private String scenario;
    private long callCount;
    private long charCount;
    private long promptTokens;
    private long completionTokens;
    private long tokenUnavailable;
    private long iterations;
    private long toolCalls;

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

    public long getCallCount() {
        return callCount;
    }

    public void setCallCount(long callCount) {
        this.callCount = callCount;
    }

    public long getCharCount() {
        return charCount;
    }

    public void setCharCount(long charCount) {
        this.charCount = charCount;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public long getTokenUnavailable() {
        return tokenUnavailable;
    }

    public void setTokenUnavailable(long tokenUnavailable) {
        this.tokenUnavailable = tokenUnavailable;
    }

    public long getIterations() {
        return iterations;
    }

    public void setIterations(long iterations) {
        this.iterations = iterations;
    }

    public long getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(long toolCalls) {
        this.toolCalls = toolCalls;
    }
}

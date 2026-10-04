package com.jobpilot.usage;

/**
 * {@code KbDocumentMapper.selectStorageUsage} 的投影行（MyBatis 按列别名自动映射）。
 */
public class StorageUsageRow {

    private long documentCount;
    private long charCount;

    public long getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(long documentCount) {
        this.documentCount = documentCount;
    }

    public long getCharCount() {
        return charCount;
    }

    public void setCharCount(long charCount) {
        this.charCount = charCount;
    }
}

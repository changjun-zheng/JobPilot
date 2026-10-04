package com.jobpilot.usage;

/**
 * 计量的业务场景（消耗由谁发起）。
 * <p>
 * {@code KnowledgeRetrievalService.search} 要求调用方显式传入场景——编译器强迫每个调用点
 * 声明归属，不存在「默认场景」：默认值会把归属错误静默吞掉，正是可归集性要防的事。
 */
public enum UsageScenario {

    /** 引用问答（RagAskService：一次检索嵌入 + 一次生成） */
    ASK,

    /** 纯检索（POST /knowledge/search：一次查询嵌入） */
    SEARCH,

    /** Agent 链路（模型调用与 knowledge_search / job_description_analyze 的检索） */
    AGENT,

    /** 文档导入（worker 索引：逐 Chunk 嵌入，按文档聚合计量） */
    INGEST,

    /** 评测集执行（RetrievalEvalRunner）。与真实流量分开——校准配额阈值时只看真实场景 */
    EVAL
}

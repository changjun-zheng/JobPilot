-- I-3c 用量计量第一阶段（PRD-FP-10「可归集」）：按租户归集的计量事件行。
-- 配额与限流后置（ROADMAP §6.0）：阈值需要真实用量数据校准，先把埋点跑起来。
-- 口径决策（PRD §12.11 待决项的落地，完整版见 UsageRecorder 类注释）：
--   1. 本地路径零边际成本但仍计量——切换云端时归集口径不变；
--   2. LLM 输入/输出 token 分开存，供应商未返回时为 NULL，禁止估算或填 0；
--   3. 存储用量（文档数/字符数）不入事件行：它是当前态不是事件流，由汇总接口对 kb_document 现查。
CREATE TABLE IF NOT EXISTS usage_record (
    id                VARCHAR(36)  NOT NULL COMMENT '计量行 ID（UUID）',
    user_id           VARCHAR(64)  NOT NULL COMMENT '租户键——计量的归集主体',
    dimension         VARCHAR(32)  NOT NULL COMMENT 'EMBEDDING / LLM_TOKEN / AGENT_RUN（封闭枚举 UsageDimension）',
    scenario          VARCHAR(32)  NOT NULL COMMENT 'ASK / SEARCH / AGENT / INGEST / EVAL（封闭枚举 UsageScenario）',
    model             VARCHAR(128) NULL COMMENT '产生消耗的模型名；调用失败取不到实际值时为 NULL',
    prompt_tokens     INT          NULL COMMENT 'LLM 输入 token；供应商未返回为 NULL（不得估算）',
    completion_tokens INT          NULL COMMENT 'LLM 输出 token；同上',
    char_count        INT          NULL COMMENT 'EMBEDDING：文本字符数（按码点计）',
    call_count        INT          NOT NULL DEFAULT 1 COMMENT '本行覆盖的供应商调用次数（INGEST 按文档聚合，其余为 1）',
    iterations        INT          NULL COMMENT 'AGENT_RUN：迭代数',
    tool_calls        INT          NULL COMMENT 'AGENT_RUN：工具调用次数',
    status            VARCHAR(16)  NOT NULL COMMENT 'OK / FAILED（由 UsageRecorder 写入，不接受外部任意值）',
    trace_id          VARCHAR(64)  NULL COMMENT '关联 agent_trace（AGENT 场景），用于定位异常消耗',
    document_id       VARCHAR(36)  NULL COMMENT 'INGEST 场景关联的文档',
    request_id        VARCHAR(64)  NULL COMMENT 'RequestIdFilter 的 requestId，与访问日志对账',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_usage_record_user_time (user_id, created_at),
    KEY idx_usage_record_user_dim (user_id, dimension, scenario)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '用量计量（FP-10 第一阶段：可归集）';

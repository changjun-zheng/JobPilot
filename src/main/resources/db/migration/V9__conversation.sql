-- I-4 前置：会话与消息持久化（PRD-FP-2.1 对话上下文；PRD-FP-6 对话页）
-- 两表都带 user_id，因此**不**加入 TENANT_EXEMPT_TABLES、**不**使用 @InterceptorIgnore：
-- 读写都发生在请求线程上，UserContext 由认证拦截器就位，租户条件照常自动注入。
--
-- 与 agent_trace / agent_trace_step 的分工：trace 按 run 记每次模型/工具调用的细节，是排查用的旁路；
-- 本表记「用户看到的对话」（USER / ASSISTANT 文本），是产品数据。二者用 conversation_id / trace_id 互相关联。
--
-- 只存 USER / ASSISTANT 两种角色：TOOL 消息的细节在 agent_trace_step，不在这里重复。助手行附
-- steps_json（工具步骤摘要，供对话页展示）与 trace_id（可回放）。
--
-- 不加外键（与 kb_/agent_/job_/user_memory 一致）：message 的归属由唯一写入路径 + user_id 保证。
--
-- 排序与 seq：不设 seq 列。一条用户消息在 run **开始时**提交、助手消息在模型返回后提交，相隔至少一次
-- 模型调用（秒级），(created_at, id) 足以定序；id 作同毫秒的 tiebreak。

CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(36)  NOT NULL COMMENT '会话 ID（UUID）；首轮 run 由服务端新建',
    user_id    VARCHAR(64)  NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    title      VARCHAR(100) NOT NULL COMMENT '历史列表显示名；由首条用户消息派生（PRD 契约未定义该字段，本实现补齐）',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近活跃时间；每次追加消息时 touch，列表按它倒序',
    PRIMARY KEY (id),
    KEY idx_conversation_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'Agent 对话会话';

CREATE TABLE IF NOT EXISTS conversation_message (
    id              VARCHAR(36) NOT NULL COMMENT '消息 ID（UUID）',
    user_id         VARCHAR(64) NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    conversation_id VARCHAR(36) NOT NULL COMMENT '所属会话',
    role            VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT（封闭枚举 MessageRole，未知值显式拒绝）',
    content         TEXT        NOT NULL COMMENT '消息文本；TEXT 而非 VARCHAR——答案与粘贴的 JD 都很长',
    steps_json      JSON        NULL COMMENT '助手消息的工具步骤摘要（AgentRunner.Step 列表）；用户消息为 NULL',
    trace_id        VARCHAR(36) NULL COMMENT '产生该助手回复的 run trace，便于回放；用户消息为 NULL',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 索引以 user_id 开头：拦截器会前置 user_id，只建 (conversation_id, ...) 的索引用不上
    KEY idx_message_user_conversation (user_id, conversation_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'Agent 对话消息（USER/ASSISTANT 文本）';

-- 面试模拟官（BRD US-3）：有状态的 AI 模拟面试
-- 两表都带 user_id，因此**不**加入 TENANT_EXEMPT_TABLES、**不**使用 @InterceptorIgnore：
-- 读写都在请求线程上，UserContext 由认证拦截器就位，租户条件照常自动注入。
--
-- 为什么不复用 conversation / conversation_message：
--   ① conversation_message 的 role 只有 USER/ASSISTANT，面试是 INTERVIEWER/CANDIDATE；
--   ② 面试消息挂在会话（interview_session）上，不是聊天会话，且会漏进 ConversationService.list；
--   ③ 面试需要 phase / round_no / seq 这些聊天消息没有的列。
--
-- 不加外键（与全项目一致）：消息归属由唯一写入路径 + user_id 保证。

CREATE TABLE IF NOT EXISTS interview_session (
    id                  VARCHAR(36)  NOT NULL COMMENT '面试会话 ID（UUID）',
    user_id             VARCHAR(64)  NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    company             VARCHAR(128) NOT NULL COMMENT '目标公司',
    position            VARCHAR(128) NULL COMMENT '目标岗位（可选）',
    tier                VARCHAR(32)  NOT NULL COMMENT '公司档位（jobpilot.interview.tiers 的键，如 BIG_TECH）',
    difficulty_override VARCHAR(32)  NULL COMMENT '用户覆盖难度（EASY/MEDIUM/HARD）；空则用档位预设',
    resolved_difficulty VARCHAR(32)  NOT NULL COMMENT '创建时**快照**的难度；改配置不改写历史会话',
    current_phase       VARCHAR(32)  NOT NULL COMMENT 'BASIC / PROJECT_DEEP_DIVE / PRESSURE',
    current_round       INT          NOT NULL COMMENT '当前第几轮（从 1 开始）',
    total_rounds        INT          NOT NULL COMMENT '总轮数（三阶段弧线）',
    questions_in_round  INT          NOT NULL DEFAULT 0 COMMENT '本轮已出题数',
    status              VARCHAR(32)  NOT NULL COMMENT 'IN_PROGRESS / REPORT_PENDING / COMPLETED',
    report_json         JSON         NULL COMMENT '评估报告（收尾后写入）',
    draft_id            VARCHAR(36)  NULL COMMENT '弱点候选的审批草稿 ID（收尾后写入；走 memory_candidate_create 审批）',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_interview_session_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 模拟面试会话（BRD US-3）';

CREATE TABLE IF NOT EXISTS interview_message (
    id         VARCHAR(36) NOT NULL COMMENT '消息 ID（UUID）',
    user_id    VARCHAR(64) NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    session_id VARCHAR(36) NOT NULL COMMENT '所属面试会话',
    role       VARCHAR(16) NOT NULL COMMENT 'INTERVIEWER / CANDIDATE',
    phase      VARCHAR(32) NOT NULL COMMENT '该消息所属阶段（BASIC / PROJECT_DEEP_DIVE / PRESSURE）',
    round_no   INT         NOT NULL COMMENT '该消息所属轮次',
    seq        INT         NOT NULL COMMENT '会话内单调序号（决定顺序；created_at 同毫秒也不乱序）',
    content    TEXT        NOT NULL COMMENT '消息文本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 索引以 user_id 开头：拦截器会前置 user_id
    KEY idx_interview_message_user_session (user_id, session_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 模拟面试消息（INTERVIEWER/CANDIDATE）';

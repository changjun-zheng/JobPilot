-- I-3b 长期记忆：经审批落库的短小结构化条目（PRD-FP-4）
-- user_memory 带 user_id，因此**不**加入 TENANT_EXEMPT_TABLES、**不**使用 @InterceptorIgnore：
-- 记忆的读写全在请求线程上，UserContext 已由认证拦截器就位，租户条件自动注入。
--
-- 与 RAG 的分工：长篇 / 需语义检索的材料进 kb_document，本表只放偏好、弱点、已确认的准备计划。
-- **content 的 512 字上限就是这条分工的强制点**——「长篇文档不允许写入记忆」不靠提示词，靠列宽 + 服务层校验。
--
-- 「未经确认的模型猜测」不进本表，靠**结构**而非内容识别：创建只有 HITL 一条路径（无用户直建接口），
-- 模型只能生成候选草稿，人勾选后才落库——人的确认本身就是那道过滤。
-- 「其他用户数据」则靠 user_id 只取自认证上下文来杜绝。
--
-- 不加外键（与 kb_document / job_application 一致）：source_draft_id 的归属由唯一写入路径保证。

CREATE TABLE IF NOT EXISTS user_memory (
    id              VARCHAR(36)   NOT NULL COMMENT '记忆 ID（UUID）',
    user_id         VARCHAR(64)   NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    type            VARCHAR(32)   NOT NULL COMMENT 'MemoryType 名称：JOB_PREFERENCE/INTERVIEW_WEAKNESS/PREPARATION_PLAN/EXPRESSION_ISSUE；未知值显式拒绝',
    content         VARCHAR(512)  NOT NULL COMMENT '记忆正文；短小条目，长篇材料走 kb_document',
    source          VARCHAR(64)   NOT NULL COMMENT '来源标签，如「面试评估」；写入路径必填',
    source_draft_id VARCHAR(36)   NULL COMMENT '产生该记忆的审批草稿 ID；回查「哪些候选被写入」的索引',
    confidence      DECIMAL(3,2)  NULL COMMENT '置信度 0.00~1.00；仅模型候选携带，用户编辑后可保留',
    note            VARCHAR(255)  NULL COMMENT '补充说明或依据摘要（PRD-FP-4 的「置信/说明字段」）',
    status          VARCHAR(32)   NOT NULL COMMENT 'ACTIVE/ARCHIVED。**不表达审批状态**——行存在即已获批，审批记录在草稿上',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_memory_user_type (user_id, type),
    KEY idx_user_memory_user_status (user_id, status),
    KEY idx_user_memory_source_draft (user_id, source_draft_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '长期记忆（经确认的短小结构化条目）';

-- 部分审批（PRD-FP-3.2「整批，可部分选择」）：
--   status 增加 PARTIALLY_APPROVED；
--   approval_selection 记「用户勾了哪些候选」（意图），与按 source_draft_id 回查的 user_memory 行
--   （效果）分开存。**不是冗余**：用户删掉某条记忆后，效果侧的痕迹就没了，而审批记录必须留存；
--   且有了它，测试能直接断言「只写入选中的子集」，不必重新解析载荷。
--   result_ref 是 VARCHAR(64)、装不下列表，因此批量草稿保持 NULL（硬塞会 DataTooLong 并回滚整个审批）。
ALTER TABLE agent_approval_draft
    MODIFY COLUMN status VARCHAR(32) NOT NULL
        COMMENT 'PENDING/APPROVED/PARTIALLY_APPROVED/REJECTED（EXPIRED 为 PRD 预留，本期不实现过期）',
    ADD COLUMN approval_selection JSON NULL
        COMMENT '部分审批时用户勾选的候选 ID 数组（意图）；整批通过或单条工具为 NULL' AFTER result_ref;

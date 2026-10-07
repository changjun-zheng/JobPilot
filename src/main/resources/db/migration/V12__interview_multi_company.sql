-- 面试「多家公司混合成一套题」（设计草案 v3 §4）：一次面试可关联 1..N 家平台公司。
--
-- 【为什么另起一张表，而不是把公司拼成一个字段】
--   interview_session 上仍保留**展示用快照**：company = 公司名顿号连接（如「字节跳动、小米」）、
--   tier = 综合档位。本表存**结构化的「这次关联了哪几家」**——复核、复盘、将来「重面这几家」
--   都靠它。文本快照保住历史可读（公司在库中改名/下架后旧会话照样显示），关联行保真。
--
-- 【本表带 user_id，是租户表】
--   不加进 TENANT_EXEMPT_TABLES（不变量 #1）。session_id 已隐含租户，但业务表一律带租户键、
--   由拦截器强制注入——结构保证优先于「反正查得到」的自觉。写入时 setUserId 显式设置。

CREATE TABLE IF NOT EXISTS interview_session_company (
    id           VARCHAR(36)  NOT NULL COMMENT '关联行 ID（UUID）',
    user_id      VARCHAR(64)  NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    session_id   VARCHAR(36)  NOT NULL COMMENT '所属面试会话',
    company_id   VARCHAR(36)  NOT NULL COMMENT '平台公司 ID（platform_company.id）',
    company_name VARCHAR(128) NOT NULL COMMENT '创建时快照的公司名（改名后历史面试仍可读）',
    tier         VARCHAR(32)  NOT NULL COMMENT '创建时快照的公司档位',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_session_company (session_id, company_id),
    -- 索引以 user_id 开头：拦截器会前置 user_id
    KEY idx_interview_session_company_user (user_id, session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '面试会话×平台公司关联（多家混合成一套题）';

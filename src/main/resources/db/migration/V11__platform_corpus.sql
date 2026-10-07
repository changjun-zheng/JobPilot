-- 平台内容库（最小切片）：所有用户共享的全局数据，与租户私有的 kb_* 并存。
--
-- 【为什么三张 platform_* 表必须加进 TENANT_EXEMPT_TABLES】
-- 它们**没有 user_id**——本就无租户语义（所有用户可见）。而租户拦截器**不检查表结构**，
-- 会给每张非豁免表盲加 `user_id = ?`；表没这列就直接报 Unknown column。
-- 这是豁免表的**第二种用途**（第一种是 user_account/user_credential 这类认证表天然跨租户）——
-- 两种理由不同，别混。
--
-- 【kb_document / kb_chunk 仍是租户表，靠 owner 区分归属】
--   owner='USER'     行 → user_id = 某个租户（现状不变）
--   owner='PLATFORM' 行 → user_id = NULL（平台内容，所有用户可读）
-- 并用 CHECK 约束把「用户行必须有 user_id」从「应用层自觉」升级为**数据库保证**：
-- 想漏掉租户键，DB 先不答应（平台行是唯一例外，且被 owner 显式标记）。
--
-- 【平台文档（面经）的正式写入口属管理端，不在本切片】；测试用脚本播种。

CREATE TABLE IF NOT EXISTS platform_company (
    id         VARCHAR(36)  NOT NULL COMMENT '公司 ID',
    name       VARCHAR(128) NOT NULL COMMENT '公司名称',
    tier       VARCHAR(32)  NOT NULL COMMENT '难度档位（对应 jobpilot.interview.tiers 的键，如 BIG_TECH）',
    industry   VARCHAR(64)  NULL COMMENT '行业（可选）',
    tags       VARCHAR(255) NULL COMMENT '标签，逗号分隔（可选）',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED（下架）',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_platform_company_tier (tier)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '平台公司目录（全局，无租户）';

CREATE TABLE IF NOT EXISTS platform_position (
    id         VARCHAR(36)  NOT NULL COMMENT '岗位 ID',
    name       VARCHAR(128) NOT NULL COMMENT '岗位名称，如「后端开发」',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_position_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '平台岗位目录（全局，无租户）';

CREATE TABLE IF NOT EXISTS platform_company_position (
    company_id  VARCHAR(36) NOT NULL COMMENT '公司 ID',
    position_id VARCHAR(36) NOT NULL COMMENT '岗位 ID',
    PRIMARY KEY (company_id, position_id),
    KEY idx_platform_company_position_pos (position_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '公司×岗位关联（全局，无租户）——支撑「按岗位反查公司」';

-- 加 owner / company_id，user_id 改可空；再用 CHECK 约束把不变量交给数据库
ALTER TABLE kb_document
    ADD COLUMN owner VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER / PLATFORM' AFTER user_id,
    ADD COLUMN company_id VARCHAR(36) NULL COMMENT '关联的平台公司（面经用；可选）' AFTER owner,
    MODIFY COLUMN user_id VARCHAR(64) NULL COMMENT '租户键；平台行（owner=PLATFORM）为 NULL',
    ADD CONSTRAINT chk_kb_document_owner CHECK (owner = 'PLATFORM' OR user_id IS NOT NULL);

ALTER TABLE kb_chunk
    ADD COLUMN owner VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER / PLATFORM' AFTER user_id,
    MODIFY COLUMN user_id VARCHAR(64) NULL COMMENT '租户键；平台行（owner=PLATFORM）为 NULL',
    ADD CONSTRAINT chk_kb_chunk_owner CHECK (owner = 'PLATFORM' OR user_id IS NOT NULL);

-- 种子：让只读的公司目录 API 一上来就有东西可返（本切片无管理端写入口）
INSERT INTO platform_position (id, name) VALUES
    ('pos-backend', '后端开发'),
    ('pos-algo', '算法工程师'),
    ('pos-frontend', '前端开发');

INSERT INTO platform_company (id, name, tier, industry, tags) VALUES
    ('comp-bytedance', '字节跳动', 'BIG_TECH', '互联网', '大厂,高难度'),
    ('comp-meituan', '美团', 'BIG_TECH', '互联网', '大厂'),
    ('comp-xiaomi', '小米', 'MID_TECH', '硬件 / 互联网', '中厂'),
    ('comp-startup-x', '某 AI 初创', 'STARTUP', '人工智能', '初创');

INSERT INTO platform_company_position (company_id, position_id) VALUES
    ('comp-bytedance', 'pos-backend'),
    ('comp-bytedance', 'pos-algo'),
    ('comp-meituan', 'pos-backend'),
    ('comp-xiaomi', 'pos-backend'),
    ('comp-xiaomi', 'pos-frontend'),
    ('comp-startup-x', 'pos-algo');

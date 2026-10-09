-- 管理端（双认证轴）+ 平台面经写入口的最小切片。
--
-- 【没有新增管理表】管理员是**配置态单账号**（jobpilot.admin.username/password，.env 注入）：
-- 单运营者阶段不建 admin_account / 不做多管理员——「多管理员与操作留痕」在设计草案 §10.3 明确后置。
--
-- 【source_note：合规列（设计草案 §8）】平台面经的来源/授权备注，管理端导入**强制填写**；
-- 租户自己的导入不填（NULL）——合规约束只落在平台内容上。
-- 一键下架复用 status=ARCHIVED + 清向量/Chunk（行保留，审计可查），不加新表。

ALTER TABLE kb_document
    ADD COLUMN source_note VARCHAR(255) NULL COMMENT '来源/授权备注（平台导入强制；合规审计用）' AFTER tags,
    ADD KEY idx_kb_document_platform (owner, status);

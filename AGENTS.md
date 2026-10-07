# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

> **本文件只放「每次改动都要遵守的规则 + 导航 + 命令」，保持在 ~120 行以内。**
> 实现细节 / 运维开关 / API 逐条 → [`docs/agents-reference.md`](./docs/agents-reference.md)
> 设计取舍 → [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)　·　进度状态 → [`docs/ROADMAP.md`](./docs/ROADMAP.md)
> 新增内容优先写上面三个文件；本文件只增收「规则」，不收细节。Claude Code 经 `CLAUDE.md` 的 `@AGENTS.md` 读本文件。

## 信息优先级

代码实际行为 > `AGENTS.md` > `docs/ARCHITECTURE.md` > 其他 docs。`ROADMAP.md` 是**进度**唯一事实来源，但它是方向不是现状——别把「将来要做」读成「已有」。

## 项目快照

- JobPilot：面向求职流程的个人 Copilot **后端**（Java 21 / Spring Boot 4.0 / Maven / MyBatis-Plus + MySQL / Redis / Spring AI 边界 / 模型 **本地 Ollama 或云端 API（OpenAI 兼容）** + Chroma）。前端（I-4 起）：**Vue 3 + Vite + TS + Element Plus**，独立 `frontend/` SPA。
- **进度**：I-0 ~ I-3 已完成；I-4 进行中（会话持久化前置已完成，页面未开始）。以 `ROADMAP.md` 为准。
- **产品定位：多租户 SaaS**（2026-10-01 起）。**租户 = 一个自然人用户，无权限可分配。**
- 根目录 `README.md` 仍停留在骨架阶段，与代码不符；实际能力以 `src/main/java` 为准。

## 常用命令

```bash
mvn test                                  # 全部测试（需本机 MySQL，见下）
mvn test -Dtest=ChunkSplitterTest         # 单个测试类
mvn test -Dtest=ChunkSplitterTest#plainTextSplitsBySizeWithOverlapOnLongLines   # 单个用例
mvn spring-boot:run                       # 启动
bash scripts/check-arch.sh                # 架构约束检查（改动 ai/ 或 service/ 后、提交前跑）
```

- **`mvn test` 需要可用的 MySQL**（全量上下文要 Flyway + 真实 DataSource，连不上直接 BUILD FAILURE）。仅 `ChunkSplitterTest` / `DocumentIngestServiceTest` / `KnowledgeRetrievalServiceTest` 可脱离基础设施单独跑。
- **开发默认不用 Docker**：集成测试走本机 MySQL，不设环境变量就完全不连 Docker 引擎（本机启动 Docker Desktop 很卡）。
- 本机默认 JDK 不是 21 时用 `run-java21.cmd test` / `run-java21.cmd spring-boot:run`（只给当前 Maven 进程设 `JAVA_HOME`）。
- 细节（Boot 4 坑、模型名两个入口、Chroma collection-id、测试 mock 约定、check-arch 实现注记）见 [`docs/agents-reference.md`](./docs/agents-reference.md) §1 / §5。

## 关键不变量（改代码时别破）

1. **租户隔离**：业务表读写必须带租户归属，由 `TenantLineInnerInterceptor` 强制注入；**写入侧必须显式 `setUserId`**（拦截器对已带 `user_id` 的插入是跳过、不覆盖）。新增业务表必须带 `user_id`，**不加进 `TENANT_EXEMPT_TABLES`**（**唯一例外是平台内容表** `platform_*`：它们本就无租户、所有用户可读——见 [`agents-reference` §2](./docs/agents-reference.md) 的「平台内容库」）。功能可延期，隔离不能妥协。
2. **端口/适配器边界**：Spring AI 与供应商 HTTP/SDK 类型**只能在 `ai.adapter`**；业务层只依赖 `ChatPort` / `EmbeddingPort` / `VectorStorePort` + JobPilot 自定义 record。
3. **降级是硬约束**：向量路径不可达必须降级到关键词检索，且标记 `degraded` **一路透传到响应**；无证据直接拒答、不调 LLM。
4. **HITL**：会写入长期记忆或知识库的工具必须走 HITL（落草稿 + 幂等审批），模型不得有绕过审批的备用工具。审批三条规矩（校验全部前置 / 幂等返回持久化事实 / 空与未知选择拒绝）见 reference §3.5。
5. **Agent 三条不变量**：工具异常绝不外抛（转 FAILED 回填）；租户只来自 `ToolExecutionContext`；HITL 不在环上等。**`AgentRunner` 不知道表结构**——会话持久化在 `AgentChatService` 编排（该编排层**不加事务**）。
6. **不引入**：消息队列 / 第二个数据库（尤其作为降级目标）/ Elasticsearch / Spring Cloud·Nacos·网关·Feign·Seata / LangGraph4j。异步化用「数据库当队列」，**不用 `@Async`**。
7. **多租户 ≠ RBAC**，不做组织/成员层级权限模型。
8. **可扩展性按「接缝设计」**（只做四件事），不做空接口/空 adapter、插件系统/SPI、通用工作流引擎。
9. **不提前创建空 port/adapter**；只在真正实现某能力时创建（`security` 包与 `UserContext` 为例外）。

> 各条的论证与出处见 [`docs/agents-reference.md` §4](./docs/agents-reference.md) 与 `ARCHITECTURE.md`。

## 修改联动

| 改动 | 需要一起改 |
|---|---|
| 新增/改业务表 | 迁移 + 实体 + mapper + **`user_id`**；`MySqlIntegrationTestBase` 的 DROP 清单 |
| 新增/改 API | [`docs/agents-reference.md` §2](./docs/agents-reference.md)；涉及规则时同步本文件 |
| 改 `ai/` 或 `service/` 层 | 跑 `bash scripts/check-arch.sh` |
| 前端页面 / 路由 / API 调用 | `frontend/`（见 `frontend/README.md`） |
| 新增 agent 工具 | `AgentRunner.SYSTEM_PROMPT`（弱提示下模型不调工具）+ 工具测试（伪造 `userId` 被忽略） |
| 新增迁移 | **已应用的迁移不可再改**（Flyway 校验和会导致整套集成测试起不来） |
| 完成一个功能 | 交接记录 + `ROADMAP.md` + `docs/进度清单.html`（见「Git 与收尾」） |

## 验证路径

| 场景 | 命令 |
|---|---|
| 全量（需本机 MySQL） | `mvn test` |
| 纯单元（不需基础设施） | `mvn test -Dtest=KnowledgeRetrievalServiceTest` |
| 架构门禁 | `bash scripts/check-arch.sh` |
| 真机 Agent 闭环 | `AGENT_E2E=true mvn test -Dtest=AgentE2EIT`（需模型 provider 与 Chroma 在线） |

## 导航

- 检索 / 导入 / 问答：`knowledge/`（`DocumentIngestService` / `KnowledgeRetrievalService` / `RagAskService` / `ChunkSplitter`）
- Agent：`agent/`（`AgentRunner` / `AgentChatService` / `AgentToolRegistry` / `ApprovalExecutionService`）
- 计量 `usage/`　·　记忆 `memory/`　·　投递 `application/`　·　会话 `conversation/`
- 边界 `ai/` + `ai/adapter/`　·　租户 `security/UserContext` + `config/MybatisPlusConfig`
- 实体/领域 `domain/`　·　mapper `mapper/`　·　迁移 `src/main/resources/db/migration/`
- 前端 `frontend/`（Vue 3 + Vite + TS + Element Plus 独立 SPA；`pnpm dev` 走 Vite 代理到 `:8080`；见 `frontend/README.md`）

## 已知边界

- 未交付：I-4 前端四个页面、云端双路径适配器（I-5）、配额/限流（I-5）、SSE（I-5）。
- 本地路径需手动启动 Ollama / Chroma；**云端路径只需 `.env` 里的 key**（默认 `provider-path=ollama`，无 key 可起）。`mvn test` 需本机 MySQL。

## Git 与收尾

- **不自动 commit / push**（红线操作，需逐次明确要求）。`docs/学习/`（`交接/` 窗口流水、`精华/` 教学笔记）与 `.workbuddy/` 已 gitignore，不入库。
- **完成一个功能或收尾一轮时**（「只有人知道什么时候算收尾」，不是每次编辑）：
  1. 写交接记录 `docs/学习/交接/YYYY-MM-DD.md` 的开发窗口段（说「写交接」触发 `/write-handoff`，它处理格式与追加）；**必含两节**——「改了哪些模块」（学习窗口靠它判断已讲内容是否失效）与「推荐学习点 1~3 个 + 教材来源」：
     | 教材来源 | 学什么 |
     |---|---|
     | 本项目 `src/main/java/...` | 项目的取舍与真实落地 |
     | `D:\Workspace\github_repos` | 通用机制的成熟实现 |
     | 官方文档 | 概念与标准用法 |
     | `D:\Workspace\Code_Test` | 需动手实验 / 复现故障时 |
     并写明**本次不需要学什么**，避免范围失控。详见 `docs/学习/交接/TEMPLATE.md`。
  2. 同步 `docs/ROADMAP.md`（状态）与 `docs/进度清单.html`（给人看的看板）。

## 维护约定

形成稳定协作规则时补进本文件，**保持精简**；实现细节写 `docs/agents-reference.md`，进度写 `ROADMAP.md`，设计写 `ARCHITECTURE.md`。

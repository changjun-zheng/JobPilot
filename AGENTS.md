# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## 项目

JobPilot 是面向求职流程的个人 Copilot 后端（Java 21 / Spring Boot 4.0 / Maven / MyBatis-Plus + MySQL / Redis / Spring AI 边界 / Ollama + Chroma）。

**当前进度：I-0、I-1、I-2 已完成；I-3 进行中（I-3a 投递记录已完成，I-3b 长期记忆与 I-3c 用量计量未开始）。** **进度状态的唯一事实来源是 [`docs/ROADMAP.md`](./docs/ROADMAP.md)**——「做了哪些、还有哪些没做、当前阻塞什么、下一步做什么」一律以该文件为准，不要依赖本句或任何文档里的零散描述。本句只作概览，可能滞后。

仓库根目录 `README.md` 仍停留在骨架阶段的描述，与代码不符；设计与实施计划以 `docs/ARCHITECTURE.md` 为准，实际能力以 `src/main/java` 为准。

**产品定位：多租户 SaaS（2026-10-01 起）。** 此前是单用户自用工具，v0.6/v0.5 起转向面向求职者群体的多租户产品。**租户隔离是架构不变量**：任何业务数据读写都必须带租户归属，隔离由数据访问层强制保证（`MyBatis-Plus` 的 `TenantLineInnerInterceptor`），不依赖调用方自觉。功能可以延期，隔离不能妥协——详见 `docs/ARCHITECTURE.md` §1.7。**在 I-1 完成前不对外开放注册。**

## 常用命令

```bash
mvn test                                  # 全部测试
mvn test -Dtest=ChunkSplitterTest         # 单个测试类
mvn test -Dtest=ChunkSplitterTest#plainTextSplitsBySizeWithOverlapOnLongLines   # 单个用例
mvn spring-boot:run                       # 启动
bash scripts/check-arch.sh                # 架构约束检查（见下）
```

`scripts/check-arch.sh` 把本文「端口/适配器边界」「架构约束」里的约定变成可执行检查（Spring AI 类型是否越界、是否引入被禁依赖、是否 `printStackTrace`、是否裸 `new Thread`）。退出码 0 = 全部通过。**改动 `ai/` 或 `service/` 层后、提交前应跑一次。**

仓库同时带了一个 pre-commit hook，但它的启用方式是本地 git 配置（`core.hooksPath`，每个 clone 手动执行一次，见 README「提交前架构检查」），**不在版本控制里，别假定它已生效**——要保证检查跑到，显式调脚本。

- 单条规则：`bash scripts/check-arch.sh boundary`（可选 `deps` / `quality`）
- 依赖：`rg` 必需；`ast-grep` 可选（缺了会跳过 AST 类规则，不会报错）

> **实现注记**：查 import 路径用 `rg` 而非 ast-grep——Java 的 import 是嵌套 `scoped_identifier`，ast-grep 的 `$$$` 不匹配路径段（实测：`import org.springframework.ai.$$$;` 返回 0 行，而 `rg` 能查到）。ast-grep 只用在能发挥 AST 优势处（如区分代码里的 `new Thread` 与注释/字符串里的）。

本机默认 JDK 不是 21 时用根目录包装脚本（只为当前 Maven 进程设置 `JAVA_HOME`，默认 `D:\develop\Java\jdk-21`，不修改系统环境变量）：

```cmd
run-java21.cmd test
run-java21.cmd spring-boot:run
```

**`mvn test` 需要可用的 MySQL**，不是「无基础设施也能跑」：`JobPilotApplicationTests` 是全量 `@SpringBootTest`，会启动完整上下文（Flyway 迁移 + 真实 DataSource），数据库连不上直接 BUILD FAILURE。只 mock 不依赖基础设施的是 `ChunkSplitterTest` / `DocumentIngestServiceTest` / `KnowledgeRetrievalServiceTest` 三个类，可以单独跑：

```bash
mvn test -Dtest=KnowledgeRetrievalServiceTest   # 不需要 MySQL / Chroma / Ollama
```

三个单元的 mock 约定：mapper 与 port（`EmbeddingPort` / `VectorStorePort` / `ChatPort`）全部 mock 掉。`DocumentIngestServiceTest` 用 `doAnswer` 模拟 MyBatis-Plus 的 `ASSIGN_UUID` 补主键，新增依赖主键生成的用例需保持这个 stub。构造 `RagProperties` 用全参构造器（11 个字段）。

**集成测试默认不碰 Docker**：`MySqlIntegrationTestBase` 走本机 MySQL（`application-local.yml`），**不设环境变量就完全不连接 Docker 引擎**。本机启动 Docker Desktop 非常卡，不要为了跑测试去开它。只有需要验证 Testcontainers 容器分支时才设 `JOBPILOT_TEST_DOCKER=true`（`ci.yml` 里有），此时需要一个可用的 Docker 引擎。

## 运行时的基础设施开关（重要）

`application.yml` 默认 profile 为 `local`，MySQL/Redis 连接信息全部放在 `application-local.yml`（gitignored，需从 `application-local.yml.example` 复制）。**没有 local profile 时应用起不来**：`@MapperScan` 需要 `SqlSessionFactory`，没有 DataSource 就报 `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`。所以「不接数据库也能启动」不成立，不要依赖。

RAG 闭环需要同时具备：MySQL（Flyway 建表）、Ollama（`bge-m3` 嵌入 + `qwen2.5:3b` 生成）、本地 Chroma（`chroma/` 目录，`.gitignore` 已忽略，非 Docker）。

**Boot 4 的坑：** 自动配置类被拆到独立 artifact 和包名。`spring-boot-autoconfigure` 现在只剩 core，DataSource/Flyway/Redis 分别在 `spring-boot-jdbc` / `spring-boot-flyway` / `spring-boot-data-redis` 里，包名是 `org.springframework.boot.<tech>.autoconfigure.*`。所以引 Flyway 必须用 `spring-boot-starter-flyway`——只引裸 `flyway-core` 时自动配置根本不在 classpath 上，Flyway 会静默不执行。同理 `spring.autoconfigure.exclude` 里写旧包名会被静默忽略（日志 conditions report 的 `Exclusions: None` 是唯一线索），别照抄 Boot 3 的 FQCN。

配置全部集中在 `jobpilot.rag.*`（`RagProperties`），端口/适配层只读这里，业务层不感知 Ollama/Chroma。

**模型名有两个入口，改一个不够**（2026-10-03 真机验证踩过）：`jobpilot.rag.*` 是给**业务层**读的（端口、Chroma 维度自检），而注入的 `ChatModel` / `EmbeddingModel` 只认 **`spring.ai.ollama.*`**。两处必须指向同一个模型，否则会出现「配置写着 bge-m3、实际请求 mxbai-embed-large」这类 404。Spring AI 的默认值（embedding 是 `mxbai-embed-large`、chat 是 `mistral`）本机都没装，所以**不显式配置就会在嵌入/对话时 404**。

`jobpilot.agent.*`（`AgentProperties`）是 I-2 的 runner 预算与超时。其中 `agent.model` **留空是有意的**——留空即回落到 `spring.ai.ollama.chat.options.model`，刻意不在配置里第三次写模型名。

## API

- `GET /api/v1/health`、`GET /actuator/health`（exposure 仅 `health,info`，`show-details: never`）
- `POST /api/v1/knowledge/documents` 提交导入（**I-1c 已异步化**）：落 `PENDING` 后立即返回 `202` + `documentId`，由 DB 队列 worker（`IngestWorker`，`jobpilot.ingest.*` 配置）在后台索引；重试退避 / 每租户上限 / 僵死接管见 `IngestProperties` 与 `docs/ROADMAP.md` §4.3
- `GET /api/v1/knowledge/documents/{id}` 索引状态查询——异步化后这是跟踪进度的**必需**接口（202 只代表入队成功）
- `POST /api/v1/knowledge/documents/{id}/reindex` 重排既有文档：仅 `READY` / `FAILED` 可重排（进行中拒绝），重置为 `PENDING` 后交同一个 worker
- `POST /api/v1/knowledge/search` 纯检索，响应带 `searchMode` / `degraded`
- `POST /api/v1/knowledge/ask` 引用问答，响应带 `answer` + `citations` + `searchMode` / `degraded`
- `POST /api/v1/agent/run` 发起一次 Agent 对话（**I-2**）：`AgentRunner` 决定是否调用 `knowledge_search` / `job_description_analyze`；响应带 `traceId` + `steps` + `draftIds`
- `POST /api/v1/agent/approvals/{draftId}/approve` / `reject`、`GET /api/v1/agent/approvals/{draftId}` —— HITL 审批。**审批幂等**：重复 approve 只执行一次副作用；跨租户访问与「不存在」对外不可区分（均为 404）
- `POST /api/v1/applications`、`GET /{id}`、`PATCH /{id}`、`DELETE /{id}`、`GET ?status=&from=&to=&limit=`、`GET /stats?from=&to=` —— 投递记录（**I-3a**）。状态取值见 `ApplicationStatus`（七个枚举，**未知值显式拒绝**，不静默降级）。`applied_at` 是 DATE，时间范围过滤含当天两端。
  - **REST 有 DELETE，agent 没有对应的 `application_delete` 工具**——PRD-FP-2.2 的工具清单里没有它，删除不该由模型发起。它与「撤回投递」（状态置 `WITHDRAWN`）是两件事。
  - PATCH 的三态约定：字段**缺省** = 不修改；**空串** = 清空（仅可空字符串字段）。`appliedAt` 是日期，只能设不能清。
- `GET /api/v1/memories`（`?type=&status=&limit=`）、`GET /{id}`、`PATCH /{id}`、`DELETE /{id}` —— 长期记忆（**I-3b**）。类型取值见 `MemoryType`（四个枚举，**未知值显式拒绝**）。
  - **没有 POST**：PRD-FP-4 只给用户查看/编辑/删除，记忆的唯一创建路径是 Agent 生成候选 → 审批。加直建接口等于多一条绕过审批的写入。
  - `source` / `sourceDraftId` / `confidence` **不可改**——记录「从哪来、当时多确信」，事后修改等于伪造出处。
  - 正文上限 512 字：**列宽就是「记忆不是知识库」的强制点**，长篇材料走文档导入。

所有响应（含失败）都带 `requestId`：`RequestIdFilter` 生成、写入 MDC 与 `X-Request-Id` 响应头，用户报障时凭它对齐服务端日志。

`userId` **已于 I-1a 从请求体移除**：身份从 JWT 解析、经 `UserContext` 传递，Controller 签名不接受 `userId`（当初的请求体形态是安全缺陷，见 `docs/ARCHITECTURE.md` §1.7）。`docType` 不传时按文件名后缀猜（`.md`/`.markdown` → `MARKDOWN`，否则 `PLAIN_TEXT`）。**空白 content 不是 400**：按 PRD-FP-1.1 入队后由 worker 判为 `FAILED`（确定性错误不重试）。

## 架构要点

### 端口/适配器边界（`com.jobpilot.ai`）

业务层只依赖 `ChatPort`、`EmbeddingPort`、`VectorStorePort` 和 JobPilot 自定义的 record（`Citation`、`RetrievalQuery/Result`、`RetrievedChunk`、`SearchMode`）。**Spring AI 与供应商 HTTP/SDK 类型只能出现在 `ai.adapter`**，实现细节（如 Chroma 的 `1 - distance` 换算、Ollama 请求体构造）不得泄漏到 service 层。

三个适配器的实现方式**不同**，别当成同一套写法：`OllamaChatAdapter` / `OllamaEmbeddingAdapter` 包装 Spring AI 的 `ChatModel` / `EmbeddingModel`（不自己发 HTTP），只有 `ChromaVectorStoreAdapter` 用 `HttpClientConfig` 提供的 `ClientHttpRequestFactory` 构造 RestClient（连接超时 3 秒以便快速触发降级，读超时 120 秒容忍本地模型冷启动）。Chroma 之所以不用 Spring AI 的 VectorStore 抽象，是因为 UUID、metadata 过滤、距离换算和启动自检需要自己掌控（ARCHITECTURE.md §1.4）。

### 导入链路（`knowledge.DocumentIngestService`）

同步执行的状态机：`PROCESSING` → 切分 → 逐 Chunk `embed` → Chroma `upsert` → MySQL `insert` → `READY`；任一步异常则 `FAILED` 并写入 `error_message`，**半成品不得进入检索**。

- **向量 ID = `docId#seq#indexVersion`**，同时也是 `kb_chunk` 表主键。重导同一文档靠 Chroma upsert 幂等，但 `kb_chunk` 是 `insert`，重复执行会撞主键——当前用「整文档重导」代替部分重试。
- `chunk_id` 的语义就是 `vector_id`（`KbChunkEntity.vectorId` 即是它），引用与检索都以此为准。
- 切分在 `ChunkSplitter`：Markdown 按标题分节并维护章节路径（`H1/H2`，分隔符 `/`），纯文本整篇一节；超长单行走字符滑窗 + overlap。`charStart/charEnd` 是**原文中的绝对码点偏移**，所有子串/取长操作必须用 `offsetByCodePoints`，不能按 `char` 或 `String.length()` 直接算。定位失败时 `charStart` 为 `-1`，属于弱化引用的兜底而非错误。

### 检索链路（`knowledge.KnowledgeRetrievalService`）

query 嵌入 → Chroma top-K（where 过滤 `user_id` / 可选 `doc_type`）→ 按 `similarityThreshold` 截断 → MySQL 回捞 Chunk 原文，**并二次过滤只保留 `status='READY'` 的文档**（`loadReadyChunks`，防止被删/失败文档经陈旧向量命中）→ 组装 `Citation`。

**降级是硬约束，不是可选优化**：`search()` 捕获向量路径的任何异常，改用 MySQL `LIKE` 关键词检索，返回值必须带 `searchMode=KEYWORD_FALLBACK` 和 `degraded=true`，且这个标记要一路透传到 Controller 响应。关键词检索有 `keywordMinHits` 闸门（默认 2），命中数太少不算证据，避免降级模式返回噪声。降级路径里的 `document_id` 子查询用 `sqlLiteral()` 手工转义拼 SQL——改这段时注意别引入注入。

### 问答（`knowledge.RagAskService`）

证据为空时**直接拒答，不调用 LLM**（有无降级两种不同话术）。有证据时：引用列表由服务端从命中的 Chunk 组装，模型只负责正文，prompt 里带编号的 `[n]` 证据块——目的是杜绝模型虚构文档名。

### Agent（`agent.AgentRunner`，I-2）

自研的**线性** ReAct 循环（ARCHITECTURE §4.3/§6）：`ChatPort.chat` 拿工具请求 → `AgentToolRegistry` 分发执行 → 结果回填 → 重复，直到模型给出答案或预算耗尽。三条不变量，改代码时别破：

1. **工具异常绝不外抛**——工具抛异常、模型请求不存在的工具名，都转成 `ToolExecutionResult(FAILED)` 回填给模型，由模型在预算内决定重试或说明；
2. **租户只来自 `ToolExecutionContext`**——模型参数里的 `userId` 是编造的输入，工具必须忽略（`KnowledgeSearchTool` 有回归测试锁定）；
3. **HITL 不在环上等**——写入类工具返回 `PENDING_APPROVAL` 即结束本轮 run，用户经独立接口审批。让 HTTP 请求挂起等人点确认会引入连接超时、租户占线程、暂停态存哪三个问题。

**审批语义另有三条规矩**（`ApprovalExecutionService`，改审批链路前先读）：

- **校验全部前置**——载荷校验、候选解析、工具白名单都放在 `claimForApproval` **之前**。草稿一旦被推到终态而副作用没执行，此后每次 approve 都是静默 no-op，用户再也推不动它；「已批准但什么都没发生」是不可恢复的。
- **幂等路径返回持久化的事实**，不回显本次请求的输入——重复审批带着另一套选择时那些选择被丢弃，把请求内容放进响应就是报告一件没发生的事。
- **空选择 / 未知候选 ID / 重复候选 ID 一律拒绝**，不静默丢弃：丢一个会让「我勾了三条」写出两行，而响应无法自证；空选择被接受则会得到「批了 0 条」的自相矛盾终态（要全弃请用 reject）。

**批量部分审批**（`memory_candidate_create`）：一份草稿装 N 条候选，`PARTIALLY_APPROVED` 表示只批了子集。用户勾选的**意图**记在草稿的 `approval_selection`，实际写入的**效果**按 `user_memory.source_draft_id` 回查——两者分开存不是冗余，用户删掉记忆后审批记录仍须留存。空选择按 `BAD_REQUEST` 拒绝（否则会得到「批了 0 条」的自相矛盾终态），未知/重复候选 ID 也拒绝而非静默丢弃。

**不用 Spring AI 的 `ToolCallingAdvisor` / `ToolCallingManager`**：它们会把工具执行关进适配器，而租户注入、预算计数、trace、HITL 短路全都在工具执行那一刻。适配器里的 `ToolCallback` 只提供定义，`call()` 是永不执行的存根（真被调到会抛错，用于暴露有人接上了 advisor）。

**`AgentRunner.SYSTEM_PROMPT` 是功能性的，不是文案**：实测 qwen2.5:3b 在弱提示下反问用户而不调工具，把「必须先调 `knowledge_search`」写死后才稳定触发。删掉或弱化它会让真机闭环失效——而 mock 单测发现不了（它们直接返回 tool_calls）。

**trace 独立成表**（`agent_trace` / `agent_trace_step`）是**结构保证**：检索只读 `kb_document`/`kb_chunk`，trace 不在其路径上，所以不需要过滤条件。改表结构时要保持这个前提。

### 启动自检（`knowledge.ChromaStartupCheck`）

`ApplicationRunner`（不能用 `@PostConstruct`，它要调 `VectorStorePort` 和 `EmbeddingPort` 两个 Bean）。三类失败语义**故意不同**，改这里前先想清楚属于哪类：

- **集合不存在** → 永久性配置错误，抛异常**阻断启动**（避免应用长期以「看似正常」的姿态跑在降级模式）；
- **向量库不可达** → 临时故障，仅告警并保留关键词降级能力；
- **维度不一致** → 换过 embedding 模型但没重建索引，永久性错误，同样**阻断启动**。

未配置 `chroma-collection-id` 时只告警不报错（首次运行尚未建集合属于正常）。

### 其他约定

- `user_id` 从 I-0 day1 起贯穿导入、Chunk、检索、删除全链路。**I-1 起不再由请求体传入**：身份从凭证解析，`UserContext` 承载当前租户，数据访问层强制注入租户条件。`KnowledgeController` 中「M-2/M-4 要由服务端覆盖而非信任入参」的注释即指此事，届时注释与实现一并更新。
- Chroma 0.6.x 的 REST 无法可靠地按名获取已有集合，409（集合已存在）时拿不到 id。因此 **`jobpilot.rag.chroma-collection-id` 必须固定配置**（`application-local.yml` 里的固定 UUID），否则重启后集合 id 变化会导致写不进/查不到。
- 统一响应为 `ApiResponse<T>`（`success/data/error`），失败走 `GlobalExceptionHandler`；可预期错误用 `ApiException(code, message)`，不要向外泄漏堆栈。
- 领域/接口注释是中文，保持这个风格。

## 架构约束（来自 `docs/ARCHITECTURE.md`，改动前先读）

> 其中前两条已由 `scripts/check-arch.sh` 机械检查（`no-reference-imports` / `no-elasticsearch`），以及 `ai/` 层的 Spring AI 边界（`spring-ai-boundary`）。**改完代码跑一次这个脚本**，比人工核对可靠。

- 不把参考项目 `paicli` / `PaiSmart` / `tianji` / `hm-dianping` / `sky-take-out` 加为 Maven、submodule 或源码依赖，也不复制其 `Agent` / `ToolRegistry` / `AgentOrchestrator`。借鉴矩阵与各项目取舍见 `docs/ARCHITECTURE.md` §1.5 / §2。
- 不引入 Elasticsearch 替代向量检索；不引入 Spring Cloud / Nacos / 网关 / Feign / Seata（单集群单体足以覆盖 NFR-7 容量目标）。
- **降级目标不能是第二个数据库**（易反复踩，详见 `docs/ARCHITECTURE.md` §8.1）。缓存的语义是「缓存挂了回源到库」，库是最后防线而非被绕过对象——「MySQL 挂了用 MongoDB 顶」方向是反的。红线理由：异步副本会让**用户已确认删除的私密材料复活**（NFR-6 / US-7），这比诚实返回「服务不可用」严重得多。降级目标只能是被明确设计为「可缺失 / 可过期」的层（Redis 缓存、关键词检索兜底）；要提高可用性走 MySQL 主从，不走第二套存储。**MongoDB 的引入触发条件是数据形状（字段高度可变、需嵌套查询且 MySQL JSON 列做不顺手），不是可用性。**
- **不引入消息队列（RabbitMQ / RocketMQ / Kafka / Redis Stream 队列）**。文档索引用「数据库当队列」：`PENDING/PROCESSING/READY/FAILED` 状态机的行就是消息，`SELECT ... FOR UPDATE SKIP LOCKED` 认领。**也不要为了异步化引入 `@Async`**——重启即丢在途任务。详见 `docs/ARCHITECTURE.md` §4.1。
- **不做独立库或独立 schema 的多租户模式，也不做组织/成员层级权限模型**——共享库共享表 + 租户键注入。
- **多租户 ≠ RBAC，别混为一谈**（易反复踩，详见 `docs/ARCHITECTURE.md` §1.7.1）。SaaS 是商业模式、RBAC 是授权模型，不在同一维度；多租户（数据隔离）与 RBAC（授权）是两个正交的轴。**本项目租户 = 一个自然人用户，一个租户一个用户，无权限可分配，因此不使用 RBAC。** 三轴是「隔离 / 认证 / 授权」，只做前两个。注意 `tianji` 里那套完整的 RBAC 是**平台运营侧**权限（管理后台谁能改课程），与租户隔离是两回事，看到它别推导出「多租户 = RBAC」。
- 不提前创建空 port/adapter 类；只在真正实现某能力时创建对应类型。例外：`security` 包与 `UserContext` 属 I-1 必建项，它们承载架构不变量，不算「提前创建」。
- **可扩展性按「接缝设计」处理，不按「提前抽象」处理。** 一个接缝值得现在留，当且仅当「以后补的成本远高于现在」**且**「触发概率足够高」。据此只做四件事（见 `docs/ARCHITECTURE.md` §14）：① Credential 表用 `(provider, identifier)` 而非 email 唯一键；② 租户键经 `TenantLineHandler` 抽象、不硬编码 `user_id` 过滤；③ 文档类型分发收拢一处且未知类型**显式拒绝**（现散在 `KnowledgeController:143` / `DocumentIngestService` 的 `validate()` / `ChunkSplitter:50`——前两处的白名单挡住了未知类型，但下次往白名单里加格式时 `ChunkSplitter` 会静默按纯文本切）；④ 后台任务用带 `type` 的通用 job 表。
  反向清单——**不要**以可扩展性为名做：空接口/空 adapter、插件系统或 SPI、通用工作流引擎、为将来拆微服务提前切 Maven 模块。
- 首版 Agent 是本项目内的小型同步 ReAct runner（默认最多 5 轮、工具调用上限 8 次/run、LLM 超时 30 秒重试 1 次），不提前抽取独立 `agent-kernel`。
- **不引入 LangGraph4j**（已评估，见 `docs/ARCHITECTURE.md` §1.4.1）。注意两个容易搞错的事实：它是**库不是框架**，且 **`langgraph4j-core` 不依赖 LangChain4j**（依赖仅 `async-generator`/`jspecify`/`slf4j-api`），官方还支持 Spring AI 2.0.1。暂缓的真实理由是「图编排 + checkpointing」与线性 ReAct 循环不匹配，且其最强能力「运行中 interrupt」恰是当前 HITL 草稿模型刻意规避的问题。触发条件见 §14.4，别用「框架会接管应用」当理由。
- 会写入长期记忆或知识库的工具必须走 HITL：返回 `PENDING_APPROVAL` 草稿，审批幂等，模型不得有绕过审批的备用工具。**注意 `paicli` 的 HITL 是阻塞式在环审批，与草稿模型不同源，不要按它的控制流照搬。**

## 测试约定

JUnit 5 + Mockito + AssertJ（`spring-boot-starter-test`）；技术栈已升到 Boot 4.0.7，测试 starter 为 `spring-boot-starter-webmvc-test`，测试侧自动配置注解是 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`（**不是** Boot 3 的 `org.springframework.boot.test.autoconfigure.web.servlet.*`）。mock 约定见上文「常用命令」。

## Git

不自动 commit / push。`docs/学习/`（`交接/` 放窗口间交接流水，`精华/` 放教学笔记）与 `.workbuddy/` 是个人的本地笔记目录，已 gitignore，不要入库。

### 收尾时写交接记录（开发窗口的义务）

**完成一个功能、或结束一轮工作收尾时，把本次改动追加到 `docs/学习/交接/YYYY-MM-DD.md` 的开发窗口段**。可以直接说「写交接」触发 `/write-handoff` skill，它处理了格式、追加 vs 覆盖、以及日期文件不存在时的模板复制。

开发窗口段**必须包含两节**：

1. **「改了哪些模块」**——学习窗口靠它判断已讲过的内容是否失效。只写"完成 X 功能"是不够的：若重构了 `KnowledgeRetrievalService`，学习窗口讲过的那一节就过时了，但它无从得知。
2. **「推荐学习点（1~3 个）+ 教材来源」**——这是学习窗口"学习批次"的输入。别把整个 diff 变成学习清单，只挑最有设计价值的内容，并指明**去哪学**：

| 教材来源 | 学什么 |
|---|---|
| 本项目 `src/main/java/...` | 项目的取舍与真实落地 |
| `D:\Workspace\github_repos` | 通用机制的成熟实现（含 langchain4j、spring-ai-alibaba、testcontainers-java 等） |
| 官方文档 | 概念与标准用法 |
| `D:\Workspace\Code_Test` | 需要动手实验 / 复现故障时 |

另需写明**本次不需要学什么**（样板代码、DTO、Controller 转发），避免学习范围失控。

> **为什么写在这里**：这条义务原先只写在 `~/.claude/skills/guided-learning/` 里，而那个 skill **只有学习窗口会加载**——开发窗口读不到，规则形同虚设。规则必须写在执行者能读到的地方。
>
> **为什么不是 hook**：hook 只能执行确定性命令，判断不了"这次是否算完成了一个功能"。每次编辑都提醒会变成狼来了。**只有人知道什么时候算收尾**——所以是 skill（模型可主动想起）+ 手动触发（可靠保底），不是 hook。


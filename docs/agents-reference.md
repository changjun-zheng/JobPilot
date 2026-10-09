# agents-reference.md · JobPilot 实现细节参考

> 本文件是 [`AGENTS.md`](./AGENTS.md) 的**伴随文档**：AGENTS.md 只放「每次改动都要遵守的规则 + 导航 + 命令」，
> 实现细节、运维开关、API 逐条说明放这里。设计与取舍见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)，
> 进度状态见 [`docs/ROADMAP.md`](./docs/ROADMAP.md)。**新增细节优先写进本文件，AGENTS.md 保持精简。**

---

## 1. 运行时与基础设施开关

`application.yml` 默认 profile 为 `local`，MySQL/Redis 连接信息全部放在 `application-local.yml`（gitignored，需从 `application-local.yml.example` 复制）。**没有 local profile 时应用起不来**：`@MapperScan` 需要 `SqlSessionFactory`，没有 DataSource 就报 `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`。所以「不接数据库也能启动」不成立，不要依赖。

RAG 闭环需要同时具备：MySQL（Flyway 建表）、Ollama（`bge-m3` 嵌入 + `qwen2.5:3b` 生成）、本地 Chroma（`chroma/` 目录，`.gitignore` 已忽略，非 Docker）。

**Boot 4 的坑：** 自动配置类被拆到独立 artifact 和包名。`spring-boot-autoconfigure` 现在只剩 core，DataSource/Flyway/Redis 分别在 `spring-boot-jdbc` / `spring-boot-flyway` / `spring-boot-data-redis` 里，包名是 `org.springframework.boot.<tech>.autoconfigure.*`。所以引 Flyway 必须用 `spring-boot-starter-flyway`——只引裸 `flyway-core` 时自动配置根本不在 classpath 上，Flyway 会静默不执行。同理 `spring.autoconfigure.exclude` 里写旧包名会被静默忽略（日志 conditions report 的 `Exclusions: None` 是唯一线索），别照抄 Boot 3 的 FQCN。

配置全部集中在 `jobpilot.rag.*`（`RagProperties`），端口/适配层只读这里，业务层不感知 Ollama/Chroma。

**模型名与凭据放 `.env`**（`application.yml` 经 `spring.config.import: optional:file:.env[.properties]` 读取）。**凭据只进 gitignored 的 `.env` / 环境变量，绝不进 tracked 文件或日志**；`.env.example` 是可入库的占位模板（含「怎么用」说明）。文件不存在时不报错——CI / 别人 clone 无 `.env` 也能起。

**provider 一处切换（三处同源）**：环境变量 `JOBPILOT_AGENT_PROVIDER_PATH`（`ollama` | `openai`）同时驱动 `jobpilot.agent.provider-path` 与 `spring.ai.model.chat` / `.embedding`。三处必须同源，**`AiProviderConsistencyCheck` 启动时断言**，防止有人只改一处导致「适配器以为 openai、注入的却是 Ollama 模型」这种静默错配。tracked yml 默认 `ollama`（无需 key），`.env` 设 `openai`。**未用的 OpenAI 模态**（audio/image/moderation）在 `spring.ai.model.*` 里显式设 `none`——它们不认 `spring.ai.model.chat`，不关掉会在无 key 时因 `OpenAiAudioSpeechModel` 直接启动失败。

**模型名有两个入口，改一个不够**（2026-10-03 真机验证踩过）：`jobpilot.rag.*` 给**业务层**读（端口、Chroma 维度自检、计量），而注入的 `ChatModel` / `EmbeddingModel` 只认 **`spring.ai.ollama.*`** 或 **`spring.ai.openai.*`**（取决于 provider）。两处必须指向同一个模型，否则会出现「配置写着 bge-m3、实际请求 mxbai-embed-large」这类 404。

**重排序（`jobpilot.rerank.*`，可选）**：SiliconFlow 的 `/rerank`（cross-encoder，`BAAI/bge-reranker-v2-m3`）。`enabled=false`（默认）时检索直接用向量分数排序。**失败不影响检索**——静默回退向量序且**不置 `degraded`**（那个标记专指向量→关键词降级）。

**管理端（`jobpilot.admin.*`，第二认证轴）**：`username` 留空 = **管理面禁用**（所有 `/api/v1/admin/**` 401，应用照常启动）。启用需三项：`username` / `password` / `jwt-secret`（≥32 字节）。`AdminJwtService` 用**独立密钥 + issuer `jobpilot-admin`**，与租户 `JwtService`（issuer `jobpilot`）分属两把钥匙——两类令牌在**验签层互不解析**，用户令牌打管理面或管理令牌打用户面都是 401。管理账号是**配置态单账号**（`.env` 注入，不落库）；密码校验是恒定时间比较（SHA-256 拉平长度）。管理令牌 TTL 更短（默认 `PT30M`）。配置进 `.env`：`JOBPILOT_ADMIN_USERNAME` / `_PASSWORD` / `_JWT_SECRET`。

`jobpilot.agent.*`（`AgentProperties`）是 runner 预算与超时。其中 `agent.model` **留空是有意的**——留空即回落到 `spring.ai.ollama.chat.options.model`，刻意不在配置里第三次写模型名。

**`jobpilot.rag.chroma-collection-id` 必须固定配置**（`application-local.yml` 里的固定 UUID）：Chroma 0.6.x 的 REST 无法可靠地按名获取已有集合，409（集合已存在）时拿不到 id，否则重启后集合 id 变化会导致写不进/查不到。

**集成测试默认不碰 Docker**：`MySqlIntegrationTestBase` 走本机 MySQL（`application-local.yml`），**不设环境变量就完全不连接 Docker 引擎**。本机启动 Docker Desktop 非常卡，不要为了跑测试去开它。只有需要验证 Testcontainers 容器分支时才设 `JOBPILOT_TEST_DOCKER=true`（`ci.yml` 里有），此时需要一个可用的 Docker 引擎。

---

## 2. API 逐条

- `GET /api/v1/health`、`GET /actuator/health`（exposure 仅 `health,info`，`show-details: never`）
- `POST /api/v1/knowledge/documents` 提交导入（**I-1c 已异步化**）：落 `PENDING` 后立即返回 `202` + `documentId`，由 DB 队列 worker（`IngestWorker`，`jobpilot.ingest.*` 配置）在后台索引；重试退避 / 每租户上限 / 僵死接管见 `IngestProperties` 与 `docs/ROADMAP.md` §4.3
- `GET /api/v1/knowledge/documents?status=&limit=` 文档列表（**I-4**）：按导入时间倒序，`status` 可选（PENDING/PROCESSING/READY/FAILED）；`content` 列不随列表返回（`@TableField(select=false)`）
- `GET /api/v1/knowledge/documents/{id}` 索引状态查询——异步化后这是跟踪进度的**必需**接口（202 只代表入队成功）
- `POST /api/v1/knowledge/documents/{id}/reindex` 重排既有文档：仅 `READY` / `FAILED` 可重排（进行中拒绝），重置为 `PENDING` 后交同一个 worker
- `POST /api/v1/knowledge/documents/reindex-all` 全量重建索引（**切换 embedding 模型后必须执行**）：把本租户 `READY`/`FAILED` 文档重置为 `PENDING`，交同一个 worker 用当前模型重嵌；`202 {resetCount}`。不同模型的向量语义不兼容（**即使维度相同**，如 bge-m3 → bge-large-zh 都是 1024），不重建会让检索静默劣化
- `DELETE /api/v1/knowledge/documents/{id}` 删除文档（**I-4**）：级联删除**向量 + Chunk + 文档行**（NFR-6）。**向量清理尽力而为**——向量库不可达时仍删 DB 行（文档行与 Chunk 没了，残留向量永远不会被召回；见 `DocumentIngestService.delete` 注释）
- `POST /api/v1/knowledge/search` 纯检索，响应带 `searchMode` / `degraded`
- `POST /api/v1/knowledge/ask` 引用问答，响应带 `answer` + `citations` + `searchMode` / `degraded`
- `POST /api/v1/agent/run` 发起一次 Agent 对话（**I-2**；**I-4 起会话落库**）：`AgentRunner` 决定是否调用工具；响应带 `traceId` + `conversationId` + `steps` + `draftIds`。**会话持久化由 `AgentChatService` 编排**（runner 仍不知道表结构）。**会话 id 契约**：首轮**不带** `conversationId`（服务端新建并返回），之后必须回传服务端给的 id，否则 404——刻意不做「未知 id 即创建」的 upsert。跨 run 上下文：带 id 的 run 会把该会话最近的消息回填给模型。
- `POST /api/v1/agent/approvals/{draftId}/approve` / `reject`、`GET /api/v1/agent/approvals/{draftId}` —— HITL 审批。**审批幂等**：重复 approve 只执行一次副作用；跨租户访问与「不存在」对外不可区分（均为 404）
- `POST /api/v1/applications`、`GET /{id}`、`PATCH /{id}`、`DELETE /{id}`、`GET ?status=&from=&to=&limit=`、`GET /stats?from=&to=` —— 投递记录（**I-3a**）。状态取值见 `ApplicationStatus`（七个枚举，**未知值显式拒绝**，不静默降级）。`applied_at` 是 DATE，时间范围过滤含当天两端。
  - **REST 有 DELETE，agent 没有对应的 `application_delete` 工具**——PRD-FP-2.2 的工具清单里没有它，删除不该由模型发起。它与「撤回投递」（状态置 `WITHDRAWN`）是两件事。
  - PATCH 的三态约定：字段**缺省** = 不修改；**空串** = 清空（仅可空字符串字段）。`appliedAt` 是日期，只能设不能清。
- `GET /api/v1/memories`（`?type=&status=&limit=`）、`GET /{id}`、`PATCH /{id}`、`DELETE /{id}` —— 长期记忆（**I-3b**）。类型取值见 `MemoryType`（四个枚举，**未知值显式拒绝**）。
  - **没有 POST**：PRD-FP-4 只给用户查看/编辑/删除，记忆的唯一创建路径是 Agent 生成候选 → 审批。加直建接口等于多一条绕过审批的写入。
  - `source` / `sourceDraftId` / `confidence` **不可改**——记录「从哪来、当时多确信」，事后修改等于伪造出处。
  - 正文上限 512 字：**列宽就是「记忆不是知识库」的强制点**，长篇材料走文档导入。
- `GET /api/v1/usage/summary?from=&to=` —— 用量汇总（**I-3c 可归集**）。三维度（EMBEDDING / LLM_TOKEN / AGENT_RUN）零填充 + 场景细分 + `tokenUnavailable`，存储维度对 `kb_document` 现查。**配额与限流不在本接口**，属 I-5。
- `GET /api/v1/conversations`（`?limit=`）、`GET /{id}`（详情含按序消息）、`DELETE /{id}` —— 会话（**I-4**）。**没有 POST**：会话在首轮 `/agent/run` 时由服务端新建。只落 **USER / ASSISTANT** 两种消息；助手行带 `steps`（工具步骤摘要）与 `traceId`（可回放 `agent_trace`）。工具调用细节存 `agent_trace_step`，本表不重复；**不含 TOOL 行、不做结构化引用**。
  - **`ERROR` 终态的 run 不写助手消息** → 该会话可能出现两条连续 USER 消息（用户重试），是有意的：罐头错误话不该当作助手的真实回答落库。
- `POST /api/v1/interview/sessions`（`{companyIds[], position?, difficultyOverride?}`）→ 开一场**模拟面试**（**BRD US-3**），返回 `{sessionId, phase, round, totalRounds, status, question}`
  - **目标公司来自平台目录**（`companyIds`，可多家），不接自由文本：**多家 = 混合成一套题**（不逐家分轮）。难度取选中公司档位中**难度最高**者，可 `difficultyOverride` 覆盖；含无效/已下架公司的请求 400
  - **检索按公司硬收窄**：面试的面经/材料两条查询都带 `RetrievalQuery.companyIds`——**平台内容**只留 `company_id ∈ 范围` 的文档（不带 `company_id` 的通用平台文档被排除），**用户自己的文档不受限**。收窄在回捞（`selectPlatformReadyDocumentIds`）与关键词降级（`selectPlatformChunksByKeywords`）两处落地；向量层仍过取（`company_id` 已写进 Chroma metadata，但嵌套 `$and/$or/$in` 待真机验证）
  - 会话上另存**展示快照**：`interview_session.company` = 公司名顿号连接；「关联了哪几家」在 `interview_session_company`（V12，带 `user_id` 的租户表）
- `POST /api/v1/interview/sessions/{id}/answers`（`{answer}`）→ 下一题，或 `finished=true`（此时去取报告）
- `POST /api/v1/interview/sessions/{id}/finish` → 提前收尾：出评估报告 + 落弱点草稿
- `GET /api/v1/interview/sessions/{id}/report` → `{report, draftId, candidateIds, status}`；未收尾 404
- `GET /api/v1/interview/sessions/{id}` 详情（状态 + 全部消息）　·　`GET /api/v1/interview/sessions` 列表
  - **面试不经过 `AgentRunner`**：走 `interview/InterviewService` 直接调 `ChatPort`——runner 的硬编码 `SYSTEM_PROMPT`（第 1 条强制先调 `knowledge_search`）与「单轮预算 + HITL 短路」都跟三轮面试冲突；**阶段推进由服务端 `InterviewStateMachine` 裁决**，模型只负责措辞
  - 难度 = 公司综合档位预设（`jobpilot.interview.tiers`）+ 可覆盖；**创建时快照**（改配置不改写历史会话）
  - 弱点走 **`memory_candidate_create`** 审批写记忆（复用批量/部分审批，`ApprovalExecutionService` **零改动**）；用 `sessionId` 顶替 traceId/conversationId 让草稿幂等键按会话稳定
  - **面经用 `doc_type=INTERVIEW`** 显式标记（无法从扩展名判断），面试官按此类型专门检索
- `GET /api/v1/companies?position=&q=` 平台公司目录（**全局，所有用户可读**）；`GET /api/v1/companies/positions` 岗位列表（**平台内容库最小切片**）
  - **这是系统第一处「不归任何租户」的业务数据**。`platform_*` 三表**无 `user_id`**，因此进了 `TENANT_EXEMPT_TABLES`——那是豁免表的**第二种用途**（拦截器不检查表结构，会盲加 `user_id = ?`，表没这列就报 `Unknown column`），与「认证表天然跨租户」不是一回事
  - 平台**文档**（面经）走 `kb_document.owner='PLATFORM'` + `user_id=NULL`；DB 用 `CHECK (owner='PLATFORM' OR user_id IS NOT NULL)` 把「用户行必有租户」升级为**数据库保证**
  - **检索合并**：向量 `where = $or[user_id=<t>, owner='PLATFORM']`（`VectorStorePort.search(..., includePlatform=true)`，**fail-closed 不放松**——另一支是「平台」不是「任意」）；回捞与关键词降级各**另读平台行**（`@InterceptorIgnore` + 硬写 `owner='PLATFORM'`，**只读平台行、永不返回任何租户的行**，改这几条 SQL 要重过该论证）
  - **隔离证明**：`PlatformRetrievalIsolationIntegrationTest`——播种 A/B/平台，向量层故意把 B 的也返回，断言 A 只拿到**自己 + 平台**、绝不拿到 B；用户**没有自己的文档**时平台内容**仍可见**
  - **写入口属管理端**：见下方 `/api/v1/admin/platform/**`（已实现）；V11 种子是初始数据，不再是唯一来源

**管理端 API（`/api/v1/admin/**`，第二认证轴，须管理令牌）**
- `POST /api/v1/admin/auth/login`（`{username,password}`）→ `{accessToken}`。**唯一免管理令牌的路径**（`WebMvcConfig` 的 `excludePathPatterns`）。失败统一 401
- 公司与岗位：`GET /admin/platform/companies`（**含已下架**）、`POST`（新建）、`PUT /{id}`（部分更新）、`POST /{id}/archive`｜`/activate`；`GET`｜`POST /admin/platform/positions`；`PUT`｜`DELETE /admin/platform/companies/{id}/positions/{positionId}`（关联/解除）
  - 公司档位在**写入端**用 `InterviewProperties.tier` 校验——否则用户选中它起面试时 `resolveHardestTier` 才 400（坏数据不给用户发现）
  - **下架不是删除**：目录被历史面试的关联行快照引用，物理删除会断审计链；下架行对用户侧只读查询（恒带 `status='ACTIVE'`）不可见
- 平台面经：`GET /admin/platform/documents?status=&companyId=`、`POST`（导入）、`POST /{id}/reindex`、`POST /{id}/archive`
  - **导入强制填 `sourceNote`（来源/授权备注，合规 §8）**；**同步索引**（切分 → 嵌入 → Chroma → Chunk → READY），失败落 `FAILED` + 原因。**不复用租户 DB 队列**——队列把行自带 `user_id` 写回 `UserContext`，平台行是 NULL 必撞 fail-closed
  - 写面全部收在 `PlatformKbMapper`：每条 SQL 硬写 `owner='PLATFORM'`，INSERT 连 `user_id` 都硬写 NULL——**造不出也改不到租户行**（跨租户写面，改任一条要重过论证）
  - **下架** = `status→ARCHIVED`（终态）+ 清 Chunk + 尽力清向量；向量清理失败只告警（检索只认 READY，残留不可召回）
  - 管理请求**不写 `UserContext`**（管理员不是租户）——管理代码误触租户表会被租户拦截器 fail-closed 炸掉

所有响应（含失败）都带 `requestId`：`RequestIdFilter` 生成、写入 MDC 与 `X-Request-Id` 响应头，用户报障时凭它对齐服务端日志。

`userId` **已于 I-1a 从请求体移除**：身份从 JWT 解析、经 `UserContext` 传递，Controller 签名不接受 `userId`（当初的请求体形态是安全缺陷，见 `docs/ARCHITECTURE.md` §1.7）。`docType` 不传时按文件名后缀猜（`.md`/`.markdown` → `MARKDOWN`，否则 `PLAIN_TEXT`）。**空白 content 不是 400**：按 PRD-FP-1.1 入队后由 worker 判为 `FAILED`（确定性错误不重试）。

---

## 3. 架构要点（实现细节）

### 3.1 端口/适配器边界（`com.jobpilot.ai`）

业务层只依赖 `ChatPort`、`EmbeddingPort`、`VectorStorePort` 和 JobPilot 自定义的 record（`Citation`、`RetrievalQuery/Result`、`RetrievedChunk`、`SearchMode`）。**Spring AI 与供应商 HTTP/SDK 类型只能出现在 `ai.adapter`**，实现细节（如 Chroma 的 `1 - distance` 换算、Ollama 请求体构造）不得泄漏到 service 层。

三个适配器的实现方式**不同**，别当成同一套写法：`OllamaChatAdapter` / `OllamaEmbeddingAdapter` 包装 Spring AI 的 `ChatModel` / `EmbeddingModel`（不自己发 HTTP），只有 `ChromaVectorStoreAdapter` 用 `HttpClientConfig` 提供的 `ClientHttpRequestFactory` 构造 RestClient（连接超时 3 秒以便快速触发降级，读超时 120 秒容忍本地模型冷启动）。Chroma 之所以不用 Spring AI 的 VectorStore 抽象，是因为 UUID、metadata 过滤、距离换算和启动自检需要自己掌控（ARCHITECTURE.md §1.4）。

### 3.2 导入链路（`knowledge.DocumentIngestService`）

执行的状态机：`PROCESSING` → 切分 → 逐 Chunk `embed` → Chroma `upsert` → MySQL `insert` → `READY`；任一步异常则 `FAILED` 并写入 `error_message`，**半成品不得进入检索**。异步化后每次尝试由 `IngestWorker` 认领执行。

- **向量 ID = `docId#seq#indexVersion`**，同时也是 `kb_chunk` 表主键。重导同一文档靠 Chroma upsert 幂等，但 `kb_chunk` 是 `insert`，重复执行会撞主键——当前用「整文档重导」代替部分重试。
- `chunk_id` 的语义就是 `vector_id`（`KbChunkEntity.vectorId` 即是它），引用与检索都以此为准。
- 切分在 `ChunkSplitter`：Markdown 按标题分节并维护章节路径（`H1/H2`，分隔符 `/`），纯文本整篇一节；超长单行走字符滑窗 + overlap。`charStart/charEnd` 是**原文中的绝对码点偏移**，所有子串/取长操作必须用 `offsetByCodePoints`，不能按 `char` 或 `String.length()` 直接算。定位失败时 `charStart` 为 `-1`，属于弱化引用的兜底而非错误。

### 3.3 检索链路（`knowledge.KnowledgeRetrievalService`）

query 嵌入 → Chroma top-K（where 过滤 `user_id` / 可选 `doc_type`）→ 按 `similarityThreshold` 截断 → MySQL 回捞 Chunk 原文，**并二次过滤只保留 `status='READY'` 的文档**（`loadReadyChunks`，防止被删/失败文档经陈旧向量命中）→ 组装 `Citation`。

降级路径细节（硬约束见 AGENTS.md）：`search()` 捕获向量路径的任何异常，改用 MySQL `LIKE` 关键词检索，**返回值必须带 `searchMode=KEYWORD_FALLBACK` 和 `degraded=true`，且一路透传到 Controller**。关键词检索有 `keywordMinHits` 闸门（默认 2），命中数太少不算证据，避免降级模式返回噪声。降级路径里的 `document_id` 子查询用 `sqlLiteral()` 手工转义拼 SQL——改这段时注意别引入注入。

### 3.4 问答（`knowledge.RagAskService`）

证据为空时**直接拒答，不调用 LLM**（有无降级两种不同话术）。有证据时：引用列表由服务端从命中的 Chunk 组装，模型只负责正文，prompt 里带编号的 `[n]` 证据块——目的是杜绝模型虚构文档名。

### 3.5 Agent（`agent.AgentRunner`）

自研的**线性** ReAct 循环（ARCHITECTURE §4.3/§6）：`ChatPort.chat` 拿工具请求 → `AgentToolRegistry` 分发执行 → 结果回填 → 重复，直到模型给出答案或预算耗尽。

- **审批语义三条规矩**（`ApprovalExecutionService`，改审批链路前先读）：
  - **校验全部前置**——载荷校验、候选解析、工具白名单都放在 `claimForApproval` **之前**。草稿一旦被推到终态而副作用没执行，此后每次 approve 都是静默 no-op，「已批准但什么都没发生」是不可恢复的。
  - **幂等路径返回持久化的事实**，不回显本次请求的输入——把请求内容放进响应就是报告一件没发生的事。
  - **空选择 / 未知候选 ID / 重复候选 ID 一律拒绝**，不静默丢弃（要全弃请用 reject）。
- **批量部分审批**（`memory_candidate_create`）：一份草稿装 N 条候选，`PARTIALLY_APPROVED` 表示只批了子集。用户勾选的**意图**记在草稿的 `approval_selection`，实际写入的**效果**按 `user_memory.source_draft_id` 回查——两者分开存不是冗余，用户删掉记忆后审批记录仍须留存。
- **不用 Spring AI 的 `ToolCallingAdvisor` / `ToolCallingManager`**：它们会把工具执行关进适配器，而租户注入、预算计数、trace、HITL 短路全都在工具执行那一刻。适配器里的 `ToolCallback` 只提供定义，`call()` 是永不执行的存根（真被调到会抛错，用于暴露有人接上了 advisor）。
- **`AgentRunner.SYSTEM_PROMPT` 是功能性的，不是文案**：实测 qwen2.5:3b 在弱提示下反问用户而不调工具，把「必须先调 `knowledge_search`」写死后才稳定触发。删掉或弱化它会让真机闭环失效——而 mock 单测发现不了（它们直接返回 tool_calls）。
- **trace 独立成表**（`agent_trace` / `agent_trace_step`）是**结构保证**：检索只读 `kb_document`/`kb_chunk`，trace 不在其路径上，所以不需要过滤条件。改表结构时要保持这个前提。

### 3.6 用量计量（`usage.UsageRecorder`）

只做「可归集」，配额与限流后置到 I-5（阈值等真实用量数据校准）。四条口径，改前先读类注释：

1. **本地路径 0 成本仍计量**——计量的是「消耗了什么」不是「花了多少钱」，切云端时口径一行不改；
2. **token 未返回记 NULL 不记 0**——Spring AI 在供应商未返回时给的是 `EmptyUsage` 占位（0/0）而不是 null，适配器已识别（不识别就会把「没数据」伪装成「零消耗」）；汇总接口用 `tokenUnavailable` 显式暴露缺口，不让「部分已知的合计」冒充完整总量；
3. **检索入口 `search()` 必须显式传 `UsageScenario`**（ASK/SEARCH/AGENT/INGEST/EVAL）——编译器强迫每个调用点声明归属，刻意不设默认值：默认值会把归属错误静默吞掉；
4. **存储用量不入事件行**——它是「当前态」不是事件流，汇总时对 `kb_document` 现查，不与删除时序赛跑。

计量失败只告警不阻断业务（与 trace 同款旁路语义，REQUIRES_NEW）；`ChatPort.complete()` 已随 I-3c 删除——问答链路改走 `chat()` 才能带出 `TokenUsage`。

### 3.7 启动自检（`knowledge.ChromaStartupCheck`）

`ApplicationRunner`（不能用 `@PostConstruct`，它要调 `VectorStorePort` 和 `EmbeddingPort` 两个 Bean）。三类失败语义**故意不同**，改这里前先想清楚属于哪类：

- **集合不存在** → 永久性配置错误，抛异常**阻断启动**（避免应用长期以「看似正常」的姿态跑在降级模式）；
- **向量库不可达** → 临时故障，仅告警并保留关键词降级能力；
- **维度不一致** → 换过 embedding 模型但没重建索引，永久性错误，同样**阻断启动**。

未配置 `chroma-collection-id` 时只告警不报错（首次运行尚未建集合属于正常）。

### 3.8 其他约定

- `user_id` 从 I-0 day1 起贯穿导入、Chunk、检索、删除全链路。**I-1 起不再由请求体传入**：身份从凭证解析，`UserContext` 承载当前租户，数据访问层强制注入租户条件。
- 统一响应为 `ApiResponse<T>`（`success/data/error`），失败走 `GlobalExceptionHandler`；可预期错误用 `ApiException(code, message)`，不要向外泄漏堆栈。
- 领域/接口注释是中文，保持这个风格。

---

## 4. 架构约束的论证（规则见 AGENTS.md §「关键不变量」）

- **不把参考项目加入依赖**会陷入「看似省事、实则接管控制流」：`paicli` / `PaiSmart` / `tianji` / `hm-dianping` / `sky-take-out` 都不可加为 Maven、submodule 或源码依赖，也不复制其 `Agent` / `ToolRegistry` / `AgentOrchestrator`。借鉴矩阵与各项目取舍见 `docs/ARCHITECTURE.md` §1.5 / §2。
- **降级目标不能是第二个数据库**（易反复踩，详见 `docs/ARCHITECTURE.md` §8.1）。缓存的语义是「缓存挂了回源到库」，库是最后防线而非被绕过对象——「MySQL 挂了用 MongoDB 顶」方向是反的。红线理由：异步副本会让**用户已确认删除的私密材料复活**（NFR-6 / US-7），这比诚实返回「服务不可用」严重得多。降级目标只能是被明确设计为「可缺失 / 可过期」的层（Redis 缓存、关键词检索兜底）；要提高可用性走 MySQL 主从，不走第二套存储。**MongoDB 的引入触发条件是数据形状（字段高度可变、需嵌套查询且 MySQL JSON 列做不顺手），不是可用性。**
- **不引入消息队列**（RabbitMQ / RocketMQ / Kafka / Redis Stream 队列）：文档索引用「数据库当队列」——`PENDING/PROCESSING/READY/FAILED` 状态机的行就是消息，`SELECT ... FOR UPDATE SKIP LOCKED` 认领。**也不要为了异步化引入 `@Async`**——重启即丢在途任务。详见 `docs/ARCHITECTURE.md` §4.1。
- **多租户 ≠ RBAC**（易反复踩，详见 `docs/ARCHITECTURE.md` §1.7.1）。SaaS 是商业模式、RBAC 是授权模型，不在同一维度；多租户（数据隔离）与 RBAC（授权）是两个正交的轴。**本项目租户 = 一个自然人用户，无权限可分配，因此不使用 RBAC。** 三轴是「隔离 / 认证 / 授权」，只做前两个。`tianji` 里那套 RBAC 是**平台运营侧**权限，看到它别推导出「多租户 = RBAC」。
- **不引入 LangGraph4j**（已评估，见 `docs/ARCHITECTURE.md` §1.4.1）。两个容易搞错的事实：它是**库不是框架**，且 **`langgraph4j-core` 不依赖 LangChain4j**（依赖仅 `async-generator`/`jspecify`/`slf4j-api`），官方还支持 Spring AI 2.0.1。暂缓的真实理由是「图编排 + checkpointing」与线性 ReAct 循环不匹配，且其最强能力「运行中 interrupt」恰是当前 HITL 草稿模型刻意规避的问题。触发条件见 §14.4，别用「框架会接管应用」当理由。
- **可扩展性按「接缝设计」**：一个接缝值得现在留，当且仅当「以后补的成本远高于现在」**且**「触发概率足够高」。据此只做四件事（`docs/ARCHITECTURE.md` §14）：① Credential 表用 `(provider, identifier)` 而非 email 唯一键；② 租户键经 `TenantLineHandler` 抽象；③ 文档类型分发收拢一处且未知类型**显式拒绝**（现散在 `KnowledgeController` / `DocumentIngestService.validate()` / `ChunkSplitter`，加格式时 `ChunkSplitter` 会静默按纯文本切）；④ 后台任务用带 `type` 的通用 job 表。反向清单——**不要**：空接口/空 adapter、插件系统或 SPI、通用工作流引擎、为拆微服务提前切 Maven 模块。

---

## 5. 测试与检查

### 5.1 测试约定

JUnit 5 + Mockito + AssertJ（`spring-boot-starter-test`）；技术栈已升到 Boot 4.0.7，测试 starter 为 `spring-boot-starter-webmvc-test`，测试侧自动配置注解是 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`（**不是** Boot 3 的 `org.springframework.boot.test.autoconfigure.web.servlet.*`）。

**`mvn test` 需要可用的 MySQL**：`JobPilotApplicationTests` 是全量 `@SpringBootTest`，会启动完整上下文（Flyway 迁移 + 真实 DataSource），数据库连不上直接 BUILD FAILURE。只 mock 不依赖基础设施的是 `ChunkSplitterTest` / `DocumentIngestServiceTest` / `KnowledgeRetrievalServiceTest`，可单独跑：

```bash
mvn test -Dtest=KnowledgeRetrievalServiceTest   # 不需要 MySQL / Chroma / Ollama
```

三个单元的 mock 约定：mapper 与 port（`EmbeddingPort` / `VectorStorePort` / `ChatPort`）全部 mock 掉。`DocumentIngestServiceTest` 用 `doAnswer` 模拟 MyBatis-Plus 的 `ASSIGN_UUID` 补主键，新增依赖主键生成的用例需保持这个 stub。构造 `RagProperties` 用全参构造器（11 个字段）。集成测试的 mock port 用嵌套 `@TestConfiguration`（参考 `AgentChatServiceIntegrationTest.TestPorts`）。

### 5.2 check-arch.sh 实现注记

`scripts/check-arch.sh` 把 AGENTS.md 的「端口/适配器边界」「架构约束」变成可执行检查（Spring AI 类型是否越界、是否引入被禁依赖、是否 `printStackTrace`、是否裸 `new Thread`）。退出码 0 = 全部通过。**改动 `ai/` 或 `service/` 层后、提交前应跑一次。**

- 单条规则：`bash scripts/check-arch.sh boundary`（可选 `deps` / `quality`）
- 依赖：`rg` 必需；`ast-grep` 可选（缺了会跳过 AST 类规则，不会报错）
- **实现注记**：查 import 路径用 `rg` 而非 ast-grep——Java 的 import 是嵌套 `scoped_identifier`，ast-grep 的 `$$$` 不匹配路径段（实测：`import org.springframework.ai.$$$;` 返回 0 行，而 `rg` 能查到）。ast-grep 只用在能发挥 AST 优势处（如区分代码里的 `new Thread` 与注释/字符串里的）。

pre-commit hook（`scripts/git-hooks/pre-commit`，已在版本控制中）：Maven 构建时自动配置 `core.hooksPath` 指向它（pom.xml `initialize` 阶段），首次 clone 后跑一次 `mvn compile`/`mvn test` 即生效。启用后，提交 Java 源码变更时自动运行 `check-arch.sh`，违规时阻止提交；缺 `rg` 时 hook 不阻塞（退出码 2 视为跳过）。也可手动 `git config core.hooksPath scripts/git-hooks`。

# JobPilot 开发路线图与进度

| 项 | 内容 |
|---|---|
| 最后更新 | 2026-10-07 |
| 当前迭代 | **I-4 进行中：前端四页（登录/对话/知识库/账号）已可用；另落地面试模拟官（US-3）与云端 AI 双路径（不再依赖 Ollama）** |
| 已完成 | I-0、I-1、I-2、I-3 |
| 最近验证 | 264 个测试通过（含面试满弧线、全量重建隔离、云端适配器、**平台库并入检索的跨租户隔离证明**）；`check-arch.sh` 全部通过；前端 `pnpm build` 通过 |

> **本文件是「进度状态」的唯一事实来源。**
> BRD / PRD / ARCHITECTURE 只回答「要做什么」和「为什么这么做」，**不记录做到哪一步**。
> 更新进度只改本文件；迭代的完整产出与出口条件见 [BRD §9](./BRD-求职Copilot需求文档.md) 与 [PRD §10](./PRD.md)。
>
> **看图例**：`[x]` 已完成 · `[ ]` 未开始 · `[~]` 进行中（在行尾注明卡在哪）
---

## 1. 总览

| 迭代 | 主题 | 状态 | 出口（一句话） |
|---|---|---|---|
| **I-0** | 技术基线 + RAG 最小闭环 | ✅ **已完成** | 导入 → 嵌入 → 向量检索 → 引用问答的 API 闭环可用 |
| **I-1** | 账号 + 租户隔离 + 导入异步化 | ✅ **已完成** | 新用户可注册并完成导入→问答；跨租户越权用例全通过 |
| **I-2** | Agent 最小闭环 | ✅ **已完成** | 自研 ReAct runner + `knowledge_search` + JD 分析 + trace + HITL |
| **I-3** | 长期记忆与投递管理 | ✅ **已完成** | Memory + 投递 CRUD + 用量计量，全部通过隔离用例 |
| **I-4** | 产品化外壳 | ⬜ 未开始 | 四个页面可用；10~20 个真实 JD 端到端演练通过 |
| **I-5** | 商业化与合规收口 | ⬜ 未开始 | 配额、订阅计费、数据导出自助化、SSE |
| **对外发布** | — | ⬜ **前置已满足，待决策** | 原定「I-1 完成后开放注册」——I-1 已于 2026-10-03 完成，是否解锁由产品决定，不由开发窗口自行放开 |

> **I-1 是发布前置**：在租户隔离落地前不对外开放注册，也不接受真实用户的私密材料。理由见 [ARCHITECTURE §1.7](./ARCHITECTURE.md)。

---

## 2. 开工前（当前阻塞）

- [x] **提交文档改动** —— 已拆为 4 个提交（`8ceaee5` 产品定位 / `f9e1b09` 架构 / `684a308` ROADMAP / `0058c23` 零散注释）
- [x] **验证 Redis 连通性** —— 本机用 `D:\Workspace\TechResources\Redis\Redis-8.6.2-Windows-x64-msys2-with-Service`，8.6.2 / 6379 / `bind 127.0.0.1` / **无 `requirepass`**。已用 `redis-cli client list` 确证应用侧 Lettuce（6.8.2.RELEASE）连接真实建立，**配置正确性已验证**
- [x] **Redis 常驻** —— 已注册为 **Windows 服务**：`RedisService.exe install`，`START_TYPE: AUTO_START`（开机自启），当前 `RUNNING`。服务二进制路径与 `redis.conf`、`--dir` 均为绝对路径（避免服务工作目录不同导致 `dir ./` 落到 `C:\Windows\System32`）。管理命令：`net start Redis` / `net stop Redis`，卸载 `RedisService.exe uninstall`
- [x] **补 `application-local.yml` 的 `spring.data.redis` 配置块** —— 已补（含 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` 占位符）；`.example` 同步补齐 `spring.autoconfigure.exclude` 与 `chroma-collection-id`，两边不再漂移
- [x] **确认 I-0 数据库有无真实数据** —— 2026-10-02 查实：仅 `u-demo` 演示数据（6 文档 / 9 chunk / 1 测试账号，2026-09-26 产生），按预登记规则**直接清库**（TRUNCATE 四表）。Chroma 中 u-demo 的孤儿向量归 §4.4 清理项处理
- [ ] （可选）确认 Chroma（:8000）与 Ollama（:11434）是否需启动 —— I-1 编码与单测不依赖，但端到端验证需要

> **关于 Redis 的持久方式**：`D:\Workspace\TechResources\Redis\Redis-8.6.2-Windows-x64-msys2-with-Service\RedisService.exe` 提供三种形态——
> - `RedisService.exe install -c redis.conf`：注册为 **Windows 服务**，默认 `--start-mode auto`（开机自启），随系统常驻，用 `uninstall` 卸载；
> - `start.bat` / `redis-server.exe redis.conf`：前台运行，**关掉窗口即停止**；
> - 随 Claude Code 会话后台启动：**会话或后台任务超时后即被杀**，不适合作为开发环境的常规做法。
>
> 前两种都可用，取决于是否希望它开机自启。**第三种已证明不可靠**（一次验证后就停了）。

## 2.1 工程基础设施（本会话新增）

- [x] **CI 门禁** —— `.github/workflows/ci.yml`：MySQL 8.4 service container + JDK 21 + `check-arch.sh` + `mvn test`。**已实测确认只需要 MySQL**，Chroma/Ollama 缺失属预期（向量库不可达 → 告警 + 保留关键词降级，不阻断启动）
- [x] **本地基础设施编排** —— `docker-compose.yml`：MySQL 8.4 + Redis 7，带 healthcheck 与命名卷
- [ ] **ADR 目录** —— 把 §1.4.1（LangGraph4j）、§8.1（降级边界）这类决策从架构文档中抽出为独立、只增不改的记录；当前它们混在 ARCHITECTURE 里，随主文档一起被改写
- [x] **测试覆盖 I-1 的新约束** —— 越权用例与 ThreadLocal 清理回归（`a57dbb4` + 本轮降级回归，见 §4.1）

---

## 3. 待决策（会卡住 I-1 编码）

> 2026-10-02：本节清空——密码哈希（BCrypt 强度 10）与 JWT 参数（HS256 + access TTL 2h）随 I-1a 落地；存量数据经查证仅为演示数据并已清库，无迁移需求。结论正式归档到 ARCHITECTURE 的动作与 §2.1 的 ADR 条目一并处理。其余待决项见 [PRD §12](./PRD.md)。

---

## 4. I-1 · 账号 + 租户隔离 + 导入异步化

> 完整出口条件见 [PRD §10](./PRD.md) 与 [ARCHITECTURE §9](./ARCHITECTURE.md)。

### 4.1 I-1a · 租户隔离骨架（优先，单独就有价值）

**状态：** `[~]` I-1a 全部完成；I-1b（登出撤销 / 隐私明示 / 安全日志）已完成；剩 I-1c 导入异步化。

**做完这一步，现有接口就安全了**——它单独消除了「`userId` 由请求体传入」这个安全缺口。

- [x] `User` / `Credential` 表 —— **credential 用 `(provider, identifier)` 唯一键，不用 email 唯一键**（理由见 [ARCHITECTURE §14.2](./ARCHITECTURE.md)）（`168232a`）
- [x] `security` 包 + `UserContext`（ThreadLocal，`afterCompletion` 必须 `remove()`）（`168232a`）
- [x] `TenantLineInnerInterceptor` 注册到 `MybatisPlusInterceptor`（`168232a`；分页拦截器按 `MybatisPlusConfig` 注释刻意未加，引分页时必须补在租户拦截器之后）
- [x] `KnowledgeController` 移除入参 `userId`，改为凭证解析（`168232a`）
- [~] 越权集成测试（六个业务对象 × 读/改/删/检索）—— 现有业务对象（文档读/检索/降级）已覆盖（`a57dbb4` + 本轮降级回归）；其余业务对象随 I-3 落地时补
- [x] `ThreadLocalCleanupTest` 形式的跨请求污染回归（`a57dbb4`）

### 4.1.1 I-1a 已完成项（本轮）

- [x] **认证与租户隔离核心代码** —— `UserContext`、JWT/BCrypt、`AuthInterceptor`、账号接口、`TenantLineInnerInterceptor`、Controller 移除入参 `userId`、Chroma fail-closed、关键词降级移除手工 SQL
- [x] **I-1a 核心单测** —— 41 个测试全通过（新增 JWT / BCrypt / UserContext / AuthInterceptor / TenantLineHandler、真实 MySQL + MockMvc 租户隔离与异常响应回归）
- [x] **I-1a 代码提交** —— 从「剩余项」移出，本条随该提交一并入库
- [x] **越权与隔离集成测试提交** —— `a57dbb4`：A/B 双账号 × 文档读/检索越权断言、租户拦截器 SQL 实证、无上下文 fail-closed、无效令牌不残留上下文；含 `GlobalExceptionHandler` 405/安全文案修复及单测
- [x] **空知识库降级集成回归** —— `KeywordFallbackDegradationIntegrationTest` 3 例：与 §4.1.2 的单元级覆盖（`emptyReadyDocumentSetSkipsChunkQuery…`）互补，在真实 MySQL 上锁定「空集合不拼 `IN ()`」「非 READY 文档不进降级检索」「纯符号 query 空返回」「降级路径同样不泄跨租户数据、请求体 `userId` 不生效」

### 4.1.2 I-1a 剩余项（下一步）

- [x] 集成越权测试：A/B 两账号 × 文档读 / 检索，断言不能跨租户
- [x] `ThreadLocalCleanupTest` 形式的跨请求污染回归（当前已有纯单元 `UserContextTest`）
- [x] 租户拦截器 SQL 实证测试：`selectById` / `selectByIds` / `selectList` 均不跨租户
- [x] 测试空知识库关键词降级（避免 `IN ()` 类问题）—— 单元级 `emptyReadyDocumentSetSkipsChunkQuery…` + 集成级 `KeywordFallbackDegradationIntegrationTest` 双层锁定
- [x] 修复 `GlobalExceptionHandler` 的通用异常响应：未知 HTTP 方法返回 405，未知异常不暴露异常类名
- [x] 登出与 Redis jti 撤销 —— `POST /api/v1/auth/logout` + `TokenBlacklistService`（条目 TTL = 令牌剩余寿命；Redis 不可用 fail-open，取舍见类注释）；端到端回归 `LogoutIntegrationTest`，Redis 不可用的 CI 环境整体跳过（拦截器层有 mock 兜底用例）
- [x] 注册接口（`168232a`）
- [x] 登录 / 登出接口（登录 `168232a`，登出 I-1b）
- [x] 注册流程的隐私政策与数据用途明示 —— `GET /api/v1/auth/privacy-notice` + 注册强制 `privacyConsent=true`（文案在 `application.yml`；同意版本持久化留痕挂 §4.4）
- [x] JWT 签发与校验 + **登出后原凭证立即失效**（`168232a` + I-1b）
- [x] 未认证返回 401；跨租户返回 404（测试锁定）；两者均写 `SECURITY` 前缀安全日志（`GlobalExceptionHandler`，I-1b）

### 4.3 I-1c · 导入异步化

**状态：** `[x]` 全部完成（2026-10-02，67 个测试通过）。

- [x] 导入改为「落 `PENDING` 即返回 `202` + `documentId`」，索引移出请求线程 —— `KnowledgeController` 202 + `DocumentIngestService.enqueue`；原文落 `kb_document.content`（worker 的事实来源，reindex 前提）
- [x] DB 队列 worker：`SELECT ... FOR UPDATE SKIP LOCKED` 认领 —— `IngestWorker` + `KbDocumentMapper.selectClaimCandidates`（`@InterceptorIgnore` 的四个跨租户面，论证见 mapper 注释）
- [x] 重试状态存行内（`retry_count` / `next_retry_at`）—— V3 迁移；确定性校验失败（空白内容等）直接 FAILED 不消耗重试，暂态失败指数退避（`retryBackoff` × 2^n）
- [x] **启动时接管僵死任务**：超时仍为 `PROCESSING` 的行重置为 `PENDING` —— `resetStaleProcessing`（按 `updated_at` 判僵死）
- [x] 每租户并发上限，防单租户批量导入饿死其他租户 —— 认领时比对在途计数（`maxPerTenant`，默认 2）
- [x] **不使用 `@Async`**，不引入消息队列（理由见 [ARCHITECTURE §4.1](./ARCHITECTURE.md)）—— 调度用自建 `ScheduledExecutorService`（守护线程池），行即消息

### 4.4 I-1 · 顺带清理

**状态：** `[x]` 七项全部完成（2026-10-03，80 个测试通过）。

- [x] 20 条评测集（JSONL，含失败归因分类；格式与归因口径见 [PRD §9.2](./PRD.md)）—— 语料 `eval-corpus.md`（P01~P20，含 5 个**有意留的干扰项**）+ `eval-set.jsonl`（8 事实 / 5 比较 / 4 综合 / 3 无答案）+ `RetrievalEvalRunner`（`EVAL_RUN=true` 手动跑，需真实 Ollama/Chroma）+ `EvalSetStructureTest`（**不依赖基础设施**，随日常 `mvn test` 校验格式、配比与期望段落是否存在）
- [x] consent 版本持久化留痕（合规）—— V4 迁移给 `user_account` 加 `privacy_version` / `privacy_consented_at`；版本号取 `jobpilot.security.privacy-notice-version`，控制器传入、服务落库
- [x] `ApiResponse` 补 `requestId` 字段 —— `RequestIdFilter`（`HIGHEST_PRECEDENCE`）写 MDC + `X-Request-Id` 响应头，`ApiResponse` 工厂方法直接读 MDC；用 Filter 而非拦截器是因为要覆盖 `/error` 与 401 等全部路径
- [x] Testcontainers 集成测试基类 —— `MySqlIntegrationTestBase`：**默认用本机 MySQL，完全不连接 Docker**；仅当环境变量 `JOBPILOT_TEST_DOCKER=true` 时才起 `mysql:8.4` 容器（`ci.yml` 显式设置）。**两条路径均已真实验证**（容器路径 80 测试通过、耗时 3 分 52 秒；本机路径 17.8 秒）。本机启动 Docker 非常卡，因此默认不开启
- [x] 中文关键词 2 字窗口 —— `extractKeywords` 按码点滑窗（汉字 2 字、拉丁 3 字），窗口不会切进代理对
- [x] 向量路径候选池 —— `CANDIDATE_POOL_FACTOR = 3`：放大取候选，阈值截断与 READY 过滤后再 `limit(topK)`
- [x] reindex / 孤儿向量清理 —— 端口加 `deleteByDocumentId`；`process` 开头幂等清场升级为「Chunk + 旧向量」双清，终态清场失败降级为尽力而为（READY 过滤兜底）；`POST /documents/{id}/reindex` 条件更新（`status IN (READY, FAILED)`）兜住「查询后被认领」的竞态

---

## 5. I-2 · Agent 最小闭环

**状态：** `[x]` 全部完成（2026-10-03，110 个测试通过 + 真机闭环验证）。

### 5.1 已完成项

- [x] **协议与端口** —— `com.jobpilot.ai` 新增 `AgentMessage`（sealed，四类角色）/`ChatRequest`/`ChatCompletion`/`ToolCall`/`ToolDefinition`/`ToolExecutionContext`/`ToolExecutionResult`；`ChatPort` 加 `chat(ChatRequest)`，`complete` 保留不动。**类型名刻意避开 Spring AI 的类名**，见 ARCHITECTURE §5.1 记录
- [x] **自研 ReAct 循环** —— `AgentRunner`：最多 5 轮、工具上限 8 次/run、LLM 30s 超时重试 1 次；工具异常与未知工具名都转成结构化失败回填给模型，**绝不外抛到 Controller**；上下文超长时截断并记 trace
- [x] **`knowledge_search`** —— 租户只来自 `ToolExecutionContext`；模型参数里的 `userId` 一律忽略（有回归测试锁定）
- [x] **`job_description_analyze`** —— 只做「解析 JD 字段 + 检索个人材料并附引用」；PRD-FP-2.3 的八项分析字段由**外层模型**产出，工具内不嵌套 LLM 调用（避免延迟翻倍与双层预算）
- [x] **HITL** —— `save_jd_analysis_to_kb` 只落 `agent_approval_draft` 草稿并返回 `PENDING_APPROVAL`，本轮 run 立即结束；用户经 `POST /api/v1/agent/approvals/{id}/approve|reject` 审批
- [x] **幂等两道防线** —— ① 审批 `SELECT ... FOR UPDATE` 锁行 + 状态检查；② `UNIQUE (user_id, idempotency_key)`，捕获 `DuplicateKeyException` 后按租户重读并返回同一草稿
- [x] **trace** —— `agent_trace` + `agent_trace_step` 两张独立表。**不参与检索是结构保证**（检索只读 `kb_document`/`kb_chunk`），不靠过滤条件
- [x] **配置** —— `AgentProperties`（`jobpilot.agent.*`）；`provider-path` 声明但不消费，是本地/云端双路径的接缝
- [x] **API** —— `POST /api/v1/agent/run`、`/approvals/{id}/approve|reject`、`GET /approvals/{id}`；请求体一律不含 `userId`

### 5.2 验证证据（2026-10-03）

| 层次 | 证据 |
|---|---|
| 单元 | `AgentRunnerTest` 11 例（预算截断、异常不外抛、重试次数、HITL 短路、未认证 401）；`KnowledgeSearchToolTest` 5 例（**伪造 `userId` 被忽略**）；`ApprovalDraftServiceTest` 9 例 |
| 真实 MySQL | `ApprovalIntegrationTest` 5 例：唯一索引**真抛** `DuplicateKeyException`、`FOR UPDATE` **真串行化**并发审批、租户拦截器**真覆盖**三张新表、重复审批只建一份文档 |
| 真机闭环 | `AgentE2EIT`（`AGENT_E2E=true` 手动触发）：真实 qwen2.5:3b 调用 `knowledge_search` → 回填证据 → 给出答案 |

全量 `mvn test` **110 通过**；`check-arch.sh` 六条全过。

### 5.3 真机验证挖出的 4 个缺陷（均已修，见 `62e3aa4` / `833d8e5`）

1. **`spring.ai.ollama.*` 从未配置** —— `jobpilot.rag.*` 与 `spring.ai.ollama.*` 是同一批模型的**两个入口**，只有后者能到达注入的 `ChatModel`/`EmbeddingModel`。此前只配了前者，于是嵌入落到 Spring AI 默认的 `mxbai-embed-large`、对话落到 `mistral`，**这两个模型本机都没装**。属**先前就存在的缺口**，被真机验证首次暴露。
2. **`ToolCallingChatOptions` 不回落默认模型** —— 不设 model 时把 `null` 一路传给 Ollama，报 `model cannot be null or empty`。
3. **`ToolCallingChatOptions.builder()` 类型不对** —— Ollama 的 chat model 内部把 options 强转成 `OllamaChatOptions`，必须用 `OllamaChatOptions.builder()`（它本身即实现 `ToolCallingChatOptions`）。
4. **`AgentRunner` 原先没有 system prompt** —— 实测 qwen2.5:3b 在弱提示下**反问用户而不调工具**；把「回答涉及用户经历前必须先调 `knowledge_search`」写死后才稳定触发。**这个 prompt 是闭环能跑起来的前提，不是可选调优。**

### 5.4 明确不做（留给后续）

- [x] **本地/云端双路径的云端适配器** —— 2026-10-07 落地：`provider-path`（`ollama|openai`）一处切换驱动三处（含 `spring.ai.model.chat/embedding`），云端走 Spring AI OpenAI 客户端（嵌入 SiliconFlow `bge-large-zh-v1.5`、对话智谱 `GLM-4.7-Flash`）；凭据进 gitignored 的 `.env`。另加 reranker（SiliconFlow `bge-reranker-v2-m3`）与 `reindex-all`
- [x] **跨 run 会话记忆** —— I-4 前置已落地（会话/消息落库 + `AgentChatService` 回填历史，见 §7）；原记「随 I-3 的 Memory 一起做」改为随 I-4 做
- [ ] **`application_*` 工具与投递 CRUD** —— 表还不存在，不造空表
- [ ] **`EXPIRED` 审批状态** —— PRD 列了它，但需要调度器；`PENDING` 长期堆积是已知的小风险
- [ ] 多 Agent、并行工具、SSE

### 5.5 已知风险

- ~~端到端测试偶发失败~~ **已定位并修复（2026-10-03，I-3a 期间）**：先前怀疑是 `AgentE2EIT` 的事务回滚与 `ChromaStartupCheck` 启动自检时序竞争——**该归因是错的**。真实原因是 `JwtServiceTest.tamperedTokenIsRejected` 的一个随机失败：它把 JWT 签名段最后一个字符替换成 `"x"`，而 HS256 的 32 字节签名经 base64url 编成 43 个字符后，**末位只贡献 2 个有效位、低 4 位解码时被丢弃**；末位恰好为 `"w"`（`110000`）时换成 `"x"`（`110001`）高 2 位相同，解出的签名完全一致，篡改等于没改。实测 3000 次样本失败 177 次（**5.9%**）。改为篡改 payload 后 3000 次零失败。教训：**「复现不出」不等于「环境问题」**，要先怀疑随机性来源。

---

## 6. I-3 · 长期记忆与投递管理

**状态：** `[~]` I-3a 已完成（2026-10-03）；I-3b / I-3c 未开始。

### 6.0 为什么拆成三段

I-3 实际是四块互不依赖的工作，一次做完会产出低质量代码；且 ③ 依赖 ①② 产生真实用量才有意义。

| 段 | 内容 | 状态 |
|---|---|---|
| **I-3a** | 投递记录 CRUD + 统计 + 4 个 agent 工具 | `[x]` 已完成 |
| **I-3b** | 长期记忆 + HITL 写入链路（批量、可部分选择） | `[x]` 已完成 |
| **I-3c** | 用量计量（先只做「可归集」，配额与限流再后置） | `[x]` 可归集已完成；配额/限流属 I-5 |

> **I-3c 为什么先不做配额**：PRD-FP-10 要求「预留-结算」而非事后记账，机制不轻；且配额阈值需要有真实用量数据才能校准，现在拍一个值必然不准。先把埋点跑起来积累数据。

### 6.1 I-3a 已完成项（投递记录）

- [x] **表 `job_application`**（V6）—— 公司/岗位/状态/投递日期/来源/关联 JD/备注 + 租户键；无外键（与既有表一致），`jd_document_id` 的归属在写入时用租户范围内的查询校验
- [x] **状态枚举** `ApplicationStatus` —— 七种状态，未知值**显式拒绝**（消息列出允许值），不静默降级
- [x] **CRUD API** —— `POST/GET/PATCH/DELETE /api/v1/applications` + `GET /{id}` + `GET ?status=&from=&to=&limit=` + `GET /stats?from=&to=`；请求体不含 `userId`
- [x] **统计** —— 七种状态**全部零填充**，`total` 取自各分组之和（不另发一次 count，避免两者条件不一致）；未识别的状态值归入 `UNKNOWN` 桶而非丢弃，保证「各桶之和 == total」
- [x] **四个 agent 工具** —— `application_create` / `application_update` / `application_query` / `application_stats`，全部 `AUTO`（PRD-FP-2.2：低风险业务写入无需审批）
- [x] **删除的边界** —— REST 有 DELETE（用户要的 CRUD），**agent 没有对应工具**（PRD 工具清单里没有，删除不该由模型发起）；与「撤回投递」（状态置 `WITHDRAWN`）是两件事

### 6.2 I-3a 验证证据

| 层次 | 证据 |
|---|---|
| 单元（27 例） | `ApplicationServiceTest` 12：未知状态/缺日期/超长/关联文档不存在均拒绝；**影响行数 0 → NOT_FOUND**；统计零填充与 `Σ==total`。`ApplicationToolTest` 15：**伪造 `userId` 被忽略**；坏 JSON/未知状态/坏日期 → `INVALID_ARGUMENTS`；更新他人记录 → `NOT_FOUND` 而非成功 |
| 真实 MySQL（17 例） | `ApplicationIntegrationTest` 9：字段合并只改提供的字段、**空串真的把列写成 NULL**、DATE 闭区间含当天、统计分组。`ApplicationTenantIsolationIntegrationTest` 8：跨租户 `get`/`query`/`stats`/`update`/`delete` 全部挡住，且 **`update`/`delete` 的影响行数为 0** 是数据库层的事实（绕过服务层直接打 mapper 验证） |

**这补上了 ROADMAP §4.1「六个业务对象 × 读/改/删/检索」中的投递对象——`改` 与 `删` 这两个动词此前从未被任何业务对象验证过。**

### 6.3 I-3b 已完成项（长期记忆）

- [x] **表 `user_memory`**（V7）—— 类型 / 正文 / 来源 / 来源草稿 / 置信度 / 说明 / 状态 + 租户键。「记忆不是知识库」靠**列宽强制**：`content` 上限 512 字
- [x] **`MemoryType` 封闭枚举**（JOB_PREFERENCE / INTERVIEW_WEAKNESS / PREPARATION_PLAN / EXPRESSION_ISSUE）—— 未知值显式拒绝，不静默降级
- [x] **记忆查看 / 编辑 / 删除 API**（`/api/v1/memories`）—— **刻意没有 POST**：PRD-FP-4 只给了这三项，创建的唯一路径是 Agent 候选 → 审批。开直建入口等于多一条绕过审批的写入
- [x] **`memory_candidate_create` 工具** —— 生成一批候选落 `PENDING_APPROVAL` 草稿；**只落草稿、绝不写记忆**
- [x] **批量部分审批**（PRD-FP-3.2「整批，可部分选择」）—— 草稿 `status` 增加 `PARTIALLY_APPROVED`；用户勾选子集，全选记 `APPROVED`、部分记 `PARTIALLY_APPROVED`、`reject` 不写任何一条
- [x] **审批意图与效果分开存** —— 草稿的 `approval_selection` 记「用户勾了哪些」（意图），`user_memory.source_draft_id` 回查「实际写了哪些」（效果）。**不是冗余**：用户删掉某条记忆后效果侧痕迹就没了，而审批记录必须留存

### 6.4 I-3b 验证证据

| 层次 | 证据 |
|---|---|
| 真实 MySQL（30 例） | `MemoryApprovalIntegrationTest` 12：**只写入选中的子集**、全选记 `APPROVED`、重复审批带另一套选择是 no-op 且返回持久化事实、空选择/未知 ID/重复 ID 均拒绝、**校验失败后草稿仍是 PENDING 可重试**、旧草稿里的未知类型 fail-closed。`MemoryTenantIsolationIntegrationTest` 8：跨租户读/列/过滤/改/删/按草稿回查全部挡住。`MemoryCrudIntegrationTest` 10：三态补丁（缺省不改、空串清空）、来源与置信度编辑后不变、归档 ≠ 删除 |
| 单元（9 例） | `MemoryCandidateCreateToolTest`：**伪造 `userId` 被忽略**；**候选 ID 由服务端按位置生成**（模型自报的被丢弃）；载荷序列化确定（幂等键依赖它）；校验失败 → `INVALID_ARGUMENTS`；返回 `PENDING_APPROVAL` 且携带 `draftId` |

### 6.5 I-3b 的关键设计取舍

- **校验必须在「抢占审批权」之前完成**。草稿一旦被推到终态而副作用没执行，之后每次 approve 都是静默 no-op，用户再也推不动它——「已批准但什么都没发生」是不可恢复的。`ApprovalExecutionService` 现在把载荷校验、候选解析、白名单判定全部前置到 `claimForApproval` 之前。
- **幂等路径返回持久化的事实，绝不回显本次请求的选择**。第二次审批带着另一套勾选时，那套选择被丢弃；若把请求内容放进响应，就等于报告了一件没发生的事。
- **`[]` 拒绝而不是当 reject**。空选择若被接受，会得到 `PARTIALLY_APPROVED` 却零写入——一个自相矛盾的终态。要全弃请用 `reject`。
- **未知/重复候选 ID 拒绝而非静默丢弃**。丢弃会让用户看到「我勾了 3 条」而实际写 2 条，响应无法自证。
- **`Map.of` 不能用来序列化幂等键的载荷**。它的迭代顺序每次 JVM 启动随机，同一逻辑载荷会算出不同字符串，跨重启去重静默失效。记忆工具用 `LinkedHashMap`；`SaveJdAnalysisToKbTool` 那处也已暴露同一问题（待改）。

### 6.6 I-3a 过程中发现并修复的既有缺陷

**`JwtServiceTest.tamperedTokenIsRejected` 有 5.9% 的随机失败率**（与 I-3a 无关，全量跑时撞上）。

它把 JWT 签名段最后一个字符替换成 `"x"`。HS256 的 32 字节签名经 base64url 编成 43 个字符后，**末位只贡献 2 个有效位、低 4 位解码时被丢弃**；末位恰为 `"w"`（`110000`）时换成 `"x"`（`110001`）高 2 位相同，解出的签名完全一致——篡改等于没改，验证照常通过。实测 3000 次样本失败 177 次。

已改为篡改 payload（签名是对 payload 算的，必然对不上），3000 次零失败。

> **这同时纠正了先前的一处错误归因**：2026-10-03 早先那次「跑出 1 个失败、连续 5 次复现不出」，当时怀疑是 `AgentE2EIT` 与 `ChromaStartupCheck` 的时序竞争。真实原因是本 flake。教训：**「复现不出」不等于「环境问题」，先怀疑随机性来源**。

### 6.7 明确不做

- 状态流转规则（PRD 未要求，加了是猜需求）
- 投递与 JD 的深度关联分析（只存 id 并校验存在）
- 创建去重（无唯一键；靠工具描述让模型先查后建。PRD 未要求）
- 分页拦截器（无分页插件，列表用 `.last("LIMIT n")` 钳制；引入 `PaginationInnerInterceptor` 是横切改动且必须排在租户拦截器之后）

### 6.8 段间遗留

- `domain/DocumentStatus` 是「声明了但无人引用」的死枚举（`KbDocumentEntity.status` 实际是 `String`）。修它要动导入状态机，**未修**。
- I-2 的两处遗留：**Conversation/Message 持久化已于 I-4 前置完成**（见 §7）；本地/云端双路径的云端适配器仍未做。

### 6.9 I-3c 已完成项（用量计量·第一阶段「可归集」）

**状态：** `[x]` 完成（2026-10-04，209 个测试通过）。配额与限流按 §6.0 决策**后置到 I-5**——阈值需要真实用量数据校准，先把埋点跑起来。

- [x] **表 `usage_record`**（V8）—— 一张表承载三个计量维度，维度专属列允许 NULL；拒绝用 JSON 列装「通用数值」（聚合 SQL 会退化成字符串处理）
- [x] **两个封闭枚举** —— `UsageDimension`（EMBEDDING / LLM_TOKEN / AGENT_RUN）、`UsageScenario`（ASK / SEARCH / AGENT / INGEST / EVAL）。**`KnowledgeRetrievalService.search` 强制要求调用方声明场景**：编译器强迫五个调用点（问答/纯检索/两个工具/评测）显式归属，刻意不设默认值——默认值会把归属错误静默吞掉
- [x] **`UsageRecorder`** —— 旁路语义与 trace 同款（REQUIRES_NEW + 失败只告警）；消耗已真实发生，不因业务回滚而消失
- [x] **计量口径落定（PRD §12.11 已决）** —— ① 本地路径 0 成本仍计量，切云端口径一行不改；② LLM 输入/输出分开，**未返回记 NULL 不记 0**——Spring AI 在供应商未返回时给的是 `EmptyUsage` 占位（0/0）而非 null，适配器识别后映射 NULL，否则「没数据」被伪装成「零消耗」；③ Embedding 记**码点**字符数（嵌入供应商普遍不返回 token，字符数是唯一拿得到且不撒谎的量）；④ Agent 每 run 一行（迭代数 + 工具调用数）+ 每轮模型调用一行，最终失败记 FAILED 行（消耗不可知 → tokens 为 NULL）；⑤ 导入按文档聚合，失败尝试的**部分消耗**如实记 FAILED 行（嵌入移出 `indexChunk` 就是为了在消耗点精确计数）
- [x] **存储维度不入事件行** —— 文档数/字符数是「当前态」不是事件流，`GET /usage/summary` 对 `kb_document` 现查（`KbDocumentMapper.selectStorageUsage`），不与删除时序赛跑
- [x] **`GET /api/v1/usage/summary`** —— 三维度零填充 + 场景细分 + `tokenUnavailable`（部分已知的合计必须显式声明与完整总量的差距）+ 存储现查；租户过滤由拦截器强制，Controller 不接收身份参数
- [x] **`ChatPort.complete()` 删除** —— 问答链路改走 `chat()` 以带出 `TokenUsage`（String 返回值装不下计量）；没有调用方的端口方法不留

**验证证据**：`UsageRecorderTest` 5 例（口径锁定：token 拆分 / NULL 语义 / FAILED 行 / 计量失败不外抛）；`OllamaChatAdapterTest` 3 例（**EmptyUsage 陷阱**——不识别它，所有「无用量」响应都会记成 0/0）；`UsageMeteringIntegrationTest` 6 例（真实 MySQL：码点字符数、拒答无 LLM 行、ask 的 token 拆分、agent run 汇总、汇总聚合 + 跨租户隔离、summary 端点）；`AgentRunnerTest` +2 例（成功/失败计量行）。**集成测试刻意不加 `@Transactional`**：REQUIRES_NEW 提交的计量行在 MySQL REPEATABLE READ 下对测试事务的既有快照不可见，回滚式清理会误报——改为 `@AfterEach` 显式清理。

---

## 7. I-4 · 产品化外壳（进行中）

**状态：** `[~]` **后端前置（会话持久化）已完成**（2026-10-06）；四个页面（注册登录 / 对话 / 知识库管理 / 账号设置）未开始。前端栈已定：**Vue 3 + Vite + TypeScript + Element Plus**（PRD §12.14 已决，独立 `frontend/` SPA）。

### 7.1 已完成：Conversation / Message 持久化

- [x] **表 `conversation` / `conversation_message`**（V9）—— 只存 **USER / ASSISTANT** 文本；助手行带 `steps_json`（工具步骤摘要）与 `trace_id`（可回放 `agent_trace`）。工具调用细节留在 `agent_trace_step`，本表不重复。索引以 `user_id` 开头（拦截器会前置它）；不加外键
- [x] **`AgentChatService` 编排层**（`agent` 包）—— 「读历史 → run → 落消息」。**不加 `@Transactional`**：中间是数十秒的 LLM 调用，包进事务会长时间占用连接，并破坏 `AgentTraceRecorder` 的 `REQUIRES_NEW` 语义。**会话创建 + 用户消息在 `run` 之前各自提交**，run 内产生的审批草稿才挂得住
- [x] **跨 run 上下文** —— `AgentRunner.RunRequest.priorMessages`（协议消息，runner 仍不知道表结构）；历史装配为 `[System] + prior + [User]`。`compactIfTooLong` 修为**保留 System**（旧实现取「最近 N 条」会把 System 一起截掉）；`MAX_CONTEXT_MESSAGES` 40→60，加载侧另限最近 24 条
- [x] **会话 id 契约收紧** —— 首轮 `/agent/run` **不带** `conversationId`（服务端新建并返回），之后必须回传且属本租户，否则 404。修掉了「客户端传的 id 完全不校验」这一缺口（此前任意字符串都会写进 trace / 草稿）
- [x] **API `GET /api/v1/conversations`、`GET /{id}`（含消息）、`DELETE /{id}`** —— 无 POST（会话在首轮 run 落库）；无重命名

**失败策略（有意不对称）**：会话创建 / 用户消息 **fail-loud**；助手消息 **best-effort**（模型已答完，不因记账插入失败丢答案）；**`ERROR` 终态不写助手消息**（罐头错误话不算助手的真实回答）。

**验证证据**：`ConversationServiceTest` 8 例（派生 title 按码点截断 / 租户一致性 / 先查后删 / 倒序翻正）；`AgentChatServiceTest` 4 例（编排顺序 / ERROR 不写助手行 / prior 映射）；`AgentRunnerTest` +2 例（prior 拼接位置 / compact 保留 System）；`AgentChatServiceIntegrationTest` 4 例（真实 MySQL：两条有序消息 / **次轮把上轮回填给模型** / 未知 id 404 / 删级联）；`ConversationTenantIsolationIntegrationTest` 2 例（跨租户读/删 404、列表互不可见）。全量 **229 通过**；`check-arch.sh` 六条全过。

> **踩坑记录**：改 V5 迁移的注释导致 Flyway 校验和不匹配、整套集成测试起不来——**已应用的迁移不可再改**。注释修正改由 V9 头注释与 ARCHITECTURE §4.3 承载。

### 7.2 面试模拟官（BRD US-3）· 2026-10-06 落地

**背景**：US-3 是 BRD 的 P0，却从未排进任何迭代、无归属 FP、无输出契约；「按公司难度分级」是净新增。本轮补齐。

- [x] **有状态的独立面试会话**（`interview_session` / `interview_message`，V10）—— 阶段 `BASIC → PROJECT_DEEP_DIVE → PRESSURE`，由服务端 `InterviewStateMachine` **裁决**，模型只负责措辞
- [x] **不经过 `AgentRunner`** —— runner 的硬编码 `SYSTEM_PROMPT`（强制先调 knowledge_search）与单轮预算 / HITL 短路都跟三轮面试冲突；走 `interview.InterviewService` 直接调 `ChatPort`
- [x] **难度 = 公司档位预设 + 可覆盖**（`jobpilot.interview.tiers`，创建时**快照**）
- [x] **面经检索**：新增 `doc_type=INTERVIEW`（导入 UI 可选「面经」）；简历/项目走无类型检索
- [x] **报告 + 弱点走 HITL 写记忆** —— 复用 `memory_candidate_create` 审批（批量/部分审批），`ApprovalExecutionService` **零改动**
- [x] **前端 `/interview` 页**：设置 → 问答线程（第 x/3 轮 · 阶段）→ 报告 + 弱点勾选审批

**验证证据**：`InterviewStateMachineTest` 5 例（三轮弧线由服务端保证）、`InterviewServiceTest` 6 例（检索传 INTERVIEW / 草稿复用 `memory_candidate_create` / 未知档位与难度拒绝）、`InterviewFlowIntegrationTest` 1 例（真实 MySQL：走完 7 轮 → 报告 → 部分审批 → `user_memory` 行数 == 勾选数、草稿 `PARTIALLY_APPROVED`）、`InterviewTenantIsolationIntegrationTest` 1 例（跨租户读/答/收尾/列表全挡住）。全量 **250 通过**；`check-arch.sh` 六条全过；前端 `pnpm build` 过。

> **踩坑**：`PROJECT_DEEP_DIVE` 17 字符 > `phase VARCHAR(16)` → `Data too long`。V10 是新建、仅被本地库应用过，遂改列宽 + **重置本地 Flyway 应用记录重跑**（不搞「刚建就 ALTER」的 V11）。

### 7.3 平台内容库最小切片（③）· 2026-10-07

**背景**：产品方向转向「用户端 + 管理端」、平台统一维护公司/面经（见 [`docs/平台内容库与双端设计.md`](./平台内容库与双端设计.md)，草案 v3）。整个方向里**唯一有真实技术风险的**是「把全局数据并进按租户的检索、又不破隔离」——本切片先证伪/证实这一块，**不动双端**。

- [x] **平台公司目录**（`platform_company` / `platform_position` / `platform_company_position`，V11）—— 全局、**无 `user_id`**，`TENANT_EXEMPT_TABLES` 的**第二种用途**；只读 API `GET /api/v1/companies?position=&q=`（+ `/positions`）
- [x] **`owner` 列**（`kb_document` / `kb_chunk`）—— `USER` / `PLATFORM`；平台行 `user_id=NULL`；DB `CHECK (owner='PLATFORM' OR user_id IS NOT NULL)` 把「用户行必有租户」升级为**数据库保证**
- [x] **检索合并** —— 向量 `$or[user_id=<t>, owner='PLATFORM']`（`search(..., includePlatform)`，**fail-closed 不放松**）+ 回捞与关键词降级各**另读平台行**（`@InterceptorIgnore` + 硬写 `owner='PLATFORM'`）
- [x] **隔离证明** —— `PlatformRetrievalIsolationIntegrationTest`：向量层**故意把他人向量也返回**，A 仍只见「自己 + 平台」

**验证证据**：`PlatformRetrievalIsolationIntegrationTest` 3 例（隔离 + 平台可见）、`PlatformCatalogIntegrationTest` 4 例（按岗位反查公司）、`KnowledgeRetrievalServiceTest` 13 例；全量 **264 通过**；`check-arch.sh` 六条全过。

**明确不做**（属后续）：管理端与双端认证、面试「按岗位选公司」的集成、BYOK 接缝、平台文档的写入口与前端、平台内容上下架/审计、用量口径区分。

---

## 8. 已知缺陷（I-1 需一并修复）

| 缺陷 | 影响 | 位置 |
|---|---|---|
| `userId` 由请求体传入 **✅ 已修复（`168232a`）** | 安全缺陷——客户端可任意指定身份 | `KnowledgeController` |
| 索引同步执行 **✅ 已修复（I-1c）** | 单个请求占用线程数十秒到数分钟；被反代默认超时切断 | `DocumentIngestService`（改为 202 + DB 队列 worker） |
| `PROCESSING` 僵死行无接管 **✅ 已修复（I-1c）** | JVM 重启后该文档永久停留在中间态 | `IngestWorker.resetStaleProcessing` |
| `ApiResponse` 缺 `requestId` **✅ 已修复** | 用户报障无法关联服务端日志 | `RequestIdFilter` / `common` |
| 文档类型分发散在三处 | 新增格式时 `ChunkSplitter` 会静默按纯文本切 | `KnowledgeController:150` / `DocumentIngestService:260` / `ChunkSplitter:50` —— **仍未修**，属 §14.3 ③ 的接缝 |
| `mvn test` 全量上下文依赖本机 MySQL **✅ 已解除（回退式）** | CI 与协作成本 | `MySqlIntegrationTestBase`：有 Docker 走容器，无 Docker 回退本机库 |

---

## 9. 更新约定

1. **只在本文件勾选进度**。BRD / PRD / ARCHITECTURE 不记录状态，避免多处漂移；
2. 迭代**完成时**才更新第 1 节总览表的「状态」列，进行中不改；
3. 遇到阻塞在条目行尾写 `[~] 卡在：___`，不要只在私下记；
4. 新增迭代时，出口条件先写入 BRD §9 与 PRD §10，再回到本文件拆任务；
5. **待决策项做出决定后，从第 3 节删除并在 ARCHITECTURE / PRD 对应章节记录结论**——不要让已决事项留在待决列表里。

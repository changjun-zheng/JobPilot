# JobPilot

JobPilot 是面向求职流程的 Copilot 后端。当前已实现 **M-1：RAG 最小闭环**（文档导入 → 切分 → 嵌入 → 向量检索 → 引用问答 → 关键词降级）；M-2（Agent runner + 工具 + HITL + trace）尚未开始。

设计与实施计划见 [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)；面向 AI 编码工具的仓库约定见 [AGENTS.md](./AGENTS.md)。

## 技术栈

- Java 21
- Spring Boot 4.0
- Maven
- MyBatis-Plus + MySQL
- Redis
- Spring AI 2.0（仅 AI 适配边界）+ Ollama + Chroma

## 启动

需要先准备好 MySQL 并复制 `application-local.yml`（见下节）。没有数据库时应用无法启动：MyBatis-Plus 的 mapper 扫描依赖 `SqlSessionFactory`。

```bash
mvn spring-boot:run
```

或运行测试：

```bash
mvn test
```

`mvn test` 会启动完整 Spring 上下文，**需要 MySQL 可用**。只跑不依赖基础设施的单元测试：

```bash
mvn test -Dtest=KnowledgeRetrievalServiceTest   # 或 ChunkSplitterTest / DocumentIngestServiceTest
```

如果本机默认 Java 版本不是 21，可使用项目根目录的 `run-java21.cmd`，它只为当前 Maven 进程临时指定 Java 21，不修改系统环境变量：

```cmd
run-java21.cmd test
run-java21.cmd spring-boot:run
```

脚本默认使用 `D:\develop\Java\jdk-21`。如果本机安装路径不同，修改脚本中的 `JAVA_HOME` 即可。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 业务 API 健康检查 |
| GET | `/actuator/health` | Spring Boot 健康检查 |
| POST | `/api/v1/knowledge/documents` | 导入文档并同步完成索引（返回 `status` / `errorMessage`） |
| GET | `/api/v1/knowledge/documents/{id}` | 查询文档索引状态 |
| POST | `/api/v1/knowledge/search` | 纯检索（响应含 `searchMode` / `degraded`） |
| POST | `/api/v1/knowledge/ask` | 引用问答（回答 + 引用列表） |

RAG 链路需要 **MySQL**（Flyway 建表）、**Ollama**（`bge-m3` 嵌入 + `qwen2.5:3b` 生成）和本地 **Chroma** 同时可用；任一不可用时的行为见 ARCHITECTURE.md §8 的降级表。

## 本地基础设施配置

需要连接 MySQL/Redis 时，将 `src/main/resources/application-local.yml.example` 复制为 `application-local.yml`，再通过环境变量填写连接信息。`application-local.yml` 不应提交到仓库。

`application.yml` 默认 profile 为 `local`，数据库与 Redis 连接信息全部来自 `application-local.yml`，没有该文件时应用启动会失败。

## 提交前架构检查

`scripts/check-arch.sh` 把 AGENTS.md 里的架构约束变成可执行检查：Spring AI 类型是否越出 `ai/adapter`、是否引入被禁的参考项目依赖或 Elasticsearch、是否 `printStackTrace`、是否裸 `new Thread`。

仓库带了配套的 pre-commit hook。Maven 构建时会自动配置 `core.hooksPath` 指向 `scripts/git-hooks/`（pom.xml `initialize` 阶段），首次 clone 后跑一次 `mvn compile` 或 `mvn test` 即可让 hook 生效，无需手动配置。如果还没跑过 Maven 就想提交，可以手动启用：

```bash
git config core.hooksPath scripts/git-hooks
```

启用后，暂存区含 `src/main/java/**/*.java` 的提交会先跑检查，不通过即阻止提交（确认无误可用 `git commit --no-verify` 绕过）；纯文档提交不触发检查。手动运行的完整用法与规则细节见 [AGENTS.md](./AGENTS.md)。

依赖 `rg`（必需）和 `ast-grep`（可选，缺失时跳过 AST 类规则）。

## 目录约定

```text
src/main/java/com/jobpilot/
├── ai/          # 自定义 port（ChatPort / EmbeddingPort / VectorStorePort）与协议 record
│   └── adapter/ # Spring AI Ollama 适配 + 自定义 RestClient Chroma 适配
├── common/      # ApiResponse、ApiException、GlobalExceptionHandler
├── config/      # RagProperties、HTTP 客户端工厂
├── controller/  # HTTP API
├── domain/      # MyBatis-Plus 实体（kb_document / kb_chunk）
├── knowledge/   # 导入、切分、检索、问答、启动自检
├── mapper/      # MyBatis-Plus Mapper
└── service/     # 应用服务
```

数据库表由 `src/main/resources/db/migration/` 下的 Flyway 脚本创建，应用启动时自动迁移。

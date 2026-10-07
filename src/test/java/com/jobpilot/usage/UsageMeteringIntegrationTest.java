package com.jobpilot.usage;

import com.jobpilot.agent.AgentRunner;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.TokenUsage;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.knowledge.RagAskService;
import com.jobpilot.mapper.UsageRecordMapper;
import com.jobpilot.security.JwtService;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用量计量的端到端回归（I-3c，PRD-FP-10「可归集」）。
 * <p>
 * 用真实 MySQL 锁四件事：
 * 1. 检索的查询嵌入按租户落行，字符数按<b>码点</b>计（emoji/生僻字不算错）；
 * 2. 拒答路径不触碰模型 → <b>没有</b> LLM 计量行；有证据的问答落输入/输出分开的 token 行；
 * 3. Agent run 落一行汇总（迭代数 + 工具调用数）+ 每轮模型调用行；
 * 4. 汇总按维度零填充聚合，且跨租户严格隔离；存储维度对 kb_document 现查。
 * <p>
 * 端口全部 mock（固定返回向量/回答），不依赖 Ollama/Chroma，CI 可跑。
 * <p>
 * <b>刻意不加 {@code @Transactional}</b>：Recorder 是 REQUIRES_NEW，计量行在测试事务外提交；
 * MySQL REPEATABLE READ 下，测试事务一旦读过数据（如检索回捞），快照就定格在计量行提交之前，
 * 之后的验证查询永远看不到那行。所以这里靠 {@code @AfterEach} 显式清理，不用事务回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ContextConfiguration(classes = UsageMeteringIntegrationTest.TestPorts.class)
class UsageMeteringIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private KnowledgeRetrievalService retrievalService;
    @Autowired
    private RagAskService askService;
    @Autowired
    private AgentRunner agentRunner;
    @Autowired
    private UsageRecordMapper usageRecordMapper;
    @Autowired
    private UsageSummaryService summaryService;
    @Autowired
    private EmbeddingPort embeddingPort;
    @Autowired
    private VectorStorePort vectorStorePort;
    @Autowired
    private ChatPort chatPort;

    private String tenant;

    @BeforeEach
    void setUp() {
        reset(embeddingPort, vectorStorePort, chatPort);
        tenant = "usage-it-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
        // Recorder 的 REQUIRES_NEW 会越过测试事务提交，残留行必须显式清掉，不污染本机库
        jdbcTemplate.update("DELETE FROM usage_record WHERE user_id LIKE 'usage-it-%'");
        jdbcTemplate.update("DELETE FROM agent_trace_step WHERE user_id LIKE 'usage-it-%'");
        jdbcTemplate.update("DELETE FROM agent_trace WHERE user_id LIKE 'usage-it-%'");
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE user_id LIKE 'usage-it-%'");
        jdbcTemplate.update("DELETE FROM kb_document WHERE user_id LIKE 'usage-it-%'");
    }

    @Test
    void embeddingSearchMeteredWithCodepointCharCount() {
        String query = "简历里有 emoji 🙂 和中文";
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStorePort.search(any(), anyInt(), any(), anyBoolean())).thenReturn(List.of());
        UserContext.set(tenant);

        retrievalService.search(new RetrievalQuery(tenant, query, 5, null), UsageScenario.SEARCH);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT dimension, scenario, char_count, call_count, status FROM usage_record WHERE user_id = ?",
                tenant);
        org.assertj.core.api.Assertions.assertThat(row.get("dimension")).isEqualTo("EMBEDDING");
        org.assertj.core.api.Assertions.assertThat(row.get("scenario")).isEqualTo("SEARCH");
        org.assertj.core.api.Assertions.assertThat(((Number) row.get("char_count")).intValue())
                .isEqualTo(query.codePointCount(0, query.length()));
        org.assertj.core.api.Assertions.assertThat(((Number) row.get("call_count")).intValue()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(row.get("status")).isEqualTo("OK");
    }

    @Test
    void refusedAskRecordsNoLlmRow() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStorePort.search(any(), anyInt(), any(), anyBoolean())).thenReturn(List.of());
        UserContext.set(tenant);

        askService.ask(tenant, "知识库里没有的问题", 5, null);

        // 拒答不调用模型：没有 LLM 计量行（嵌入行仍存在——那次嵌入是真实消耗）
        Integer llmRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_record WHERE user_id = ? AND dimension = 'LLM_TOKEN'",
                Integer.class, tenant);
        org.assertj.core.api.Assertions.assertThat(llmRows).isZero();
    }

    @Test
    void askWithEvidenceRecordsLlmTokenSplit() {
        String documentId = UUID.randomUUID().toString();
        String vectorId = documentId + "#0#1";
        insertDocument(documentId, tenant, "READY");
        insertChunk(vectorId, documentId, tenant);
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStorePort.search(any(), anyInt(), any(), anyBoolean()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch(vectorId, 0.9)));
        when(chatPort.chat(any())).thenReturn(new ChatCompletion("回答", List.of(),
                FinishReason.STOP, new TokenUsage(11, 7), "ollama", "qwen2.5:3b"));
        UserContext.set(tenant);

        RagAskService.AskAnswer answer = askService.ask(tenant, "会什么技术", 5, null);

        org.assertj.core.api.Assertions.assertThat(answer.answer()).isEqualTo("回答");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT prompt_tokens, completion_tokens, scenario, model, status "
                        + "FROM usage_record WHERE user_id = ? AND dimension = 'LLM_TOKEN'", tenant);
        org.assertj.core.api.Assertions.assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(11);
        org.assertj.core.api.Assertions.assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(row.get("scenario")).isEqualTo("ASK");
        org.assertj.core.api.Assertions.assertThat(row.get("model")).isEqualTo("qwen2.5:3b");
        org.assertj.core.api.Assertions.assertThat(row.get("status")).isEqualTo("OK");
    }

    @Test
    void agentRunMeteredWithIterationsAndToolCalls() {
        when(chatPort.chat(any())).thenReturn(new ChatCompletion("直接回答", List.of(),
                FinishReason.STOP, new TokenUsage(3, 5), "ollama", "qwen2.5:3b"));
        UserContext.set(tenant);

        agentRunner.run(new AgentRunner.RunRequest(null, "你好"));

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT iterations, tool_calls, status FROM usage_record "
                        + "WHERE user_id = ? AND dimension = 'AGENT_RUN'", tenant);
        org.assertj.core.api.Assertions.assertThat(((Number) run.get("iterations")).intValue()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(((Number) run.get("tool_calls")).intValue()).isZero();
        org.assertj.core.api.Assertions.assertThat(run.get("status")).isEqualTo("OK");

        Map<String, Object> llm = jdbcTemplate.queryForMap(
                "SELECT prompt_tokens, completion_tokens FROM usage_record "
                        + "WHERE user_id = ? AND dimension = 'LLM_TOKEN'", tenant);
        org.assertj.core.api.Assertions.assertThat(((Number) llm.get("prompt_tokens")).intValue()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(((Number) llm.get("completion_tokens")).intValue()).isEqualTo(5);
    }

    @Test
    void summaryAggregatesDimensionsAndIsolatesTenants() {
        String tenantB = "usage-it-" + UUID.randomUUID();
        UserContext.set(tenant);
        insertUsage(tenant, UsageDimension.EMBEDDING, UsageScenario.SEARCH, 1, 10, null, null);
        insertUsage(tenant, UsageDimension.EMBEDDING, UsageScenario.INGEST, 3, 25, null, null);
        insertUsage(tenant, UsageDimension.LLM_TOKEN, UsageScenario.AGENT, 1, 0, 11, 7);
        insertUsage(tenant, UsageDimension.LLM_TOKEN, UsageScenario.ASK, 1, 0, null, null); // 供应商未返回
        insertUsage(tenant, UsageDimension.AGENT_RUN, UsageScenario.AGENT, 1, 0, null, null);
        setIterationsAndToolCalls(tenant);
        jdbcTemplate.update("INSERT INTO kb_document (id, user_id, name, doc_type, status, index_version, "
                        + "chunk_count, content) VALUES (?, ?, 'a.md', 'PLAIN_TEXT', 'READY', 1, 0, '你好世界')",
                UUID.randomUUID().toString(), tenant);

        UsageSummaryService.UsageSummary summary = summaryService.summary(null, null);

        UsageSummaryService.DimensionUsage embedding = byDimension(summary, "EMBEDDING");
        org.assertj.core.api.Assertions.assertThat(embedding.calls()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(embedding.chars()).isEqualTo(35);
        UsageSummaryService.DimensionUsage llm = byDimension(summary, "LLM_TOKEN");
        org.assertj.core.api.Assertions.assertThat(llm.calls()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(llm.promptTokens()).isEqualTo(11);
        org.assertj.core.api.Assertions.assertThat(llm.completionTokens()).isEqualTo(7);
        // 部分已知不能冒充完整总量：缺 token 的行数必须显式暴露
        org.assertj.core.api.Assertions.assertThat(llm.tokenUnavailable()).isEqualTo(1);
        UsageSummaryService.DimensionUsage run = byDimension(summary, "AGENT_RUN");
        org.assertj.core.api.Assertions.assertThat(run.calls()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(run.iterations()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(run.toolCalls()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(summary.storage().documents()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(summary.storage().chars()).isEqualTo(4);

        // 另一个租户：全部为零——汇总的租户范围由拦截器强制，不靠参数
        UserContext.set(tenantB);
        UsageSummaryService.UsageSummary other = summaryService.summary(null, null);
        org.assertj.core.api.Assertions.assertThat(other.dimensions())
                .allSatisfy(d -> org.assertj.core.api.Assertions.assertThat(d.calls()).isZero());
        org.assertj.core.api.Assertions.assertThat(other.storage().documents()).isZero();
    }

    @Test
    void summaryEndpointIsTenantScopedAndZeroFilled() throws Exception {
        String token = jwtService.issue(tenant);
        mockMvc.perform(get("/api/v1/usage/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimensions.length()").value(3))
                .andExpect(jsonPath("$.data.dimensions[0].dimension").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.storage").exists());
    }

    private UsageSummaryService.DimensionUsage byDimension(
            UsageSummaryService.UsageSummary summary, String dimension) {
        return summary.dimensions().stream()
                .filter(d -> d.dimension().equals(dimension))
                .findFirst()
                .orElseThrow();
    }

    private void insertUsage(String userId, UsageDimension dimension, UsageScenario scenario,
                             int callCount, int charCount, Integer prompt, Integer completion) {
        com.jobpilot.domain.UsageRecordEntity entity = new com.jobpilot.domain.UsageRecordEntity();
        entity.setUserId(userId);
        entity.setDimension(dimension.name());
        entity.setScenario(scenario.name());
        entity.setCallCount(callCount);
        if (charCount > 0) {
            entity.setCharCount(charCount);
        }
        entity.setPromptTokens(prompt);
        entity.setCompletionTokens(completion);
        entity.setStatus("OK");
        usageRecordMapper.insert(entity);
    }

    private void setIterationsAndToolCalls(String userId) {
        jdbcTemplate.update("UPDATE usage_record SET iterations = 2, tool_calls = 3 "
                + "WHERE user_id = ? AND dimension = 'AGENT_RUN'", userId);
    }

    private void insertDocument(String id, String userId, String status) {
        jdbcTemplate.update("INSERT INTO kb_document "
                        + "(id, user_id, name, doc_type, status, index_version, chunk_count) "
                        + "VALUES (?, ?, ?, 'PLAIN_TEXT', ?, 1, 1)",
                id, userId, userId + "-document", status);
    }

    private void insertChunk(String vectorId, String documentId, String userId) {
        jdbcTemplate.update("INSERT INTO kb_chunk "
                        + "(vector_id, document_id, user_id, doc_name, doc_type, section_path, seq, text, "
                        + "char_start, char_end, index_version) "
                        + "VALUES (?, ?, ?, ?, 'PLAIN_TEXT', '/', 0, ?, 0, 10, 1)",
                vectorId, documentId, userId, userId + "-document", "熟悉 Java 与 Spring");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPorts {

        @Bean
        @Primary
        EmbeddingPort embeddingPort() {
            return org.mockito.Mockito.mock(EmbeddingPort.class);
        }

        @Bean
        @Primary
        VectorStorePort vectorStorePort() {
            return org.mockito.Mockito.mock(VectorStorePort.class);
        }

        @Bean
        @Primary
        ChatPort chatPort() {
            return org.mockito.Mockito.mock(ChatPort.class);
        }
    }
}

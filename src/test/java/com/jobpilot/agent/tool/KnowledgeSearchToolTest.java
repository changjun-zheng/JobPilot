package com.jobpilot.agent.tool;

import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.Citation;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.ai.SearchMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.ai.ToolResultStatus;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索工具的<b>租户隔离</b>回归——本项目最重要的安全不变量之一。
 * <p>
 * 模型是外部不可信输入：它完全可能在参数里编一个 {@code userId}。
 * 该字段必须被彻底忽略，下游 {@code RetrievalQuery.userId()} 只能来自认证上下文。
 */
class KnowledgeSearchToolTest {

    private KnowledgeRetrievalService retrievalService;
    private KnowledgeSearchTool tool;

    private static final String TENANT_B = "tenant-b-real";
    private static final String VICTIM = "tenant-victim";

    @BeforeEach
    void setUp() {
        retrievalService = mock(KnowledgeRetrievalService.class);
        tool = new KnowledgeSearchTool(retrievalService);
    }

    @Test
    void tenantIdComesFromContextAndForgedArgumentIsIgnored() {
        when(retrievalService.search(any(), eq(UsageScenario.AGENT))).thenReturn(RetrievalResult.vector(List.of()));

        // 模型在参数里塞了别人的 userId
        tool.execute(context(TENANT_B), "{\"query\":\"Java 经验\",\"userId\":\"" + VICTIM + "\"}");

        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalService).search(captor.capture(), eq(UsageScenario.AGENT));

        // 关键断言：下游看到的是认证上下文的租户，不是模型编的那个
        assertThat(captor.getValue().userId()).isEqualTo(TENANT_B);
        assertThat(captor.getValue().userId()).isNotEqualTo(VICTIM);
        assertThat(captor.getValue().text()).isEqualTo("Java 经验");
    }

    @Test
    void returnsNumberedEvidenceWithCitations() {
        RetrievedChunk chunk = chunk("doc-1#0#1", "熟悉 RAG 与 Agent");
        when(retrievalService.search(any(), eq(UsageScenario.AGENT))).thenReturn(RetrievalResult.vector(List.of(chunk)));

        ToolExecutionResult result = tool.execute(context(TENANT_B), "{\"query\":\"RAG\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        // 编号证据是 I-0 就定下的不变量：让模型引用 [n] 而不是虚构文档名
        assertThat(result.modelText()).contains("[1]").contains("简历.md > 技能");
        assertThat(result.citations()).hasSize(1);
    }

    @Test
    void degradedSearchTellsTheModelRetrievalWasDegraded() {
        when(retrievalService.search(any(), eq(UsageScenario.AGENT))).thenReturn(RetrievalResult.keywordFallback(List.of()));

        ToolExecutionResult result = tool.execute(context(TENANT_B), "{\"query\":\"查不到的东西\"}");

        assertThat(result.data()).isEqualTo(List.of());
        assertThat(result.modelText()).contains("降级");
    }

    @Test
    void missingQueryFailsWithoutTouchingRetrievalService() {
        ToolExecutionResult result = tool.execute(context(TENANT_B), "{}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENTS");
        verify(retrievalService, never()).search(any(), any());
    }

    @Test
    void malformedJsonFailsWithoutThrowing() {
        ToolExecutionResult result = tool.execute(context(TENANT_B), "{不是 json");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENTS");
        verify(retrievalService, never()).search(any(), any());
    }

    private ToolExecutionContext context(String userId) {
        return new ToolExecutionContext(userId, "conv-1", "trace-1", "call-1", ApprovalMode.AUTO);
    }

    private RetrievedChunk chunk(String chunkId, String text) {
        return new RetrievedChunk("doc-1", chunkId, text, 0.9,
                new Citation("doc-1", "简历.md", "技能", chunkId, 0, 10));
    }
}

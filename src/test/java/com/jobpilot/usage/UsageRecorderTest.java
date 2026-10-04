package com.jobpilot.usage;

import com.jobpilot.ai.TokenUsage;
import com.jobpilot.common.RequestId;
import com.jobpilot.domain.UsageRecordEntity;
import com.jobpilot.mapper.UsageRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 计量写入的口径锁定（FP-10）：token 分开存、未返回即 NULL、失败不外抛。
 */
class UsageRecorderTest {

    private UsageRecordMapper mapper;
    private UsageRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(UsageRecordMapper.class);
        recorder = new UsageRecorder(mapper);
    }

    @AfterEach
    void cleanMdc() {
        MDC.remove(RequestId.MDC_KEY);
    }

    @Test
    void llmCallStoresTokenSplitWithTraceAndRequestId() {
        MDC.put(RequestId.MDC_KEY, "req-1");

        recorder.recordLlmCall("u1", UsageScenario.AGENT, "qwen2.5:3b",
                new TokenUsage(11, 7), true, "trace-1");

        UsageRecordEntity entity = captured();
        assertThat(entity.getUserId()).isEqualTo("u1");
        assertThat(entity.getDimension()).isEqualTo("LLM_TOKEN");
        assertThat(entity.getScenario()).isEqualTo("AGENT");
        assertThat(entity.getModel()).isEqualTo("qwen2.5:3b");
        assertThat(entity.getPromptTokens()).isEqualTo(11);
        assertThat(entity.getCompletionTokens()).isEqualTo(7);
        assertThat(entity.getCallCount()).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("OK");
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getRequestId()).isEqualTo("req-1");
    }

    @Test
    void missingSupplierUsageStaysNullRatherThanZero() {
        // 供应商未返回用量：NULL 是「不可用」，0 是「零消耗」——两者不得混淆（PRD-FP-10）
        recorder.recordLlmCall("u1", UsageScenario.ASK, "qwen2.5:3b", null, true, null);

        UsageRecordEntity entity = captured();
        assertThat(entity.getPromptTokens()).isNull();
        assertThat(entity.getCompletionTokens()).isNull();
        assertThat(entity.getStatus()).isEqualTo("OK");
    }

    @Test
    void failedAgentRunRecordedWithFailedStatusAndCounters() {
        recorder.recordAgentRun("u1", "qwen2.5:3b", 3, 5, false, "trace-1");

        UsageRecordEntity entity = captured();
        assertThat(entity.getDimension()).isEqualTo("AGENT_RUN");
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getIterations()).isEqualTo(3);
        assertThat(entity.getToolCalls()).isEqualTo(5);
    }

    @Test
    void embeddingRowStoresCodepointCharCountAndDocumentLink() {
        recorder.recordEmbedding("u1", UsageScenario.INGEST, "bge-m3", 1234, 7, true, "doc-1");

        UsageRecordEntity entity = captured();
        assertThat(entity.getDimension()).isEqualTo("EMBEDDING");
        assertThat(entity.getCharCount()).isEqualTo(1234);
        assertThat(entity.getCallCount()).isEqualTo(7);
        assertThat(entity.getDocumentId()).isEqualTo("doc-1");
        assertThat(entity.getStatus()).isEqualTo("OK");
    }

    @Test
    void meteringFailureNeverPropagatesToBusinessFlow() {
        doThrow(new IllegalStateException("数据库不可用")).when(mapper).insert(any(UsageRecordEntity.class));

        // 旁路语义：计量挂了只告警，绝不让业务调用方跟着失败
        assertThatCode(() -> recorder.recordLlmCall("u1", UsageScenario.AGENT, "m", null, true, "t"))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.recordEmbedding("u1", UsageScenario.SEARCH, "m", 1, 1, true, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.recordAgentRun("u1", "m", 1, 0, true, "t"))
                .doesNotThrowAnyException();
    }

    private UsageRecordEntity captured() {
        ArgumentCaptor<UsageRecordEntity> captor = ArgumentCaptor.forClass(UsageRecordEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}

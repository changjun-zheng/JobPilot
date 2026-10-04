package com.jobpilot.agent.tool;

import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.ai.ToolResultStatus;
import com.jobpilot.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code memory_candidate_create} 的约定。
 * <p>
 * 两条最要紧的：<b>租户只来自上下文</b>（模型伪造的 userId 被忽略），
 * 以及<b>候选 ID 由服务端按位置生成</b>——审批时正是靠它标识「用户勾了哪几条」，
 * 模型可控就能撞号或伪造。
 */
class MemoryCandidateCreateToolTest {

    private MemoryService memoryService;
    private ApprovalDraftService draftService;
    private MemoryCandidateCreateTool tool;

    private static final String REAL_TENANT = "tenant-real";
    private static final String FORGED_TENANT = "tenant-victim";

    private static final String ARGS = """
            {"source":"面试评估","candidates":[
              {"type":"INTERVIEW_WEAKNESS","content":"讲项目时缺少量化结果"},
              {"type":"PREPARATION_PLAN","content":"准备三个 STAR 案例"}
            ]}""";

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        draftService = mock(ApprovalDraftService.class);
        tool = new MemoryCandidateCreateTool(memoryService, draftService);
        when(draftService.createDraft(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("draft-1");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(REAL_TENANT, "conv-1", "trace-1", "call-1", ApprovalMode.AUTO);
    }

    // ── 租户隔离 ────────────────────────────────────────────────

    @Test
    void forgedUserIdInArgumentsIsIgnored() {
        tool.execute(context(), ARGS.replace("\"source\"", "\"userId\":\"" + FORGED_TENANT + "\",\"source\""));

        ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
        verify(draftService).createDraft(userId.capture(), anyString(), anyString(), anyString(), anyString());
        assertThat(userId.getValue()).isEqualTo(REAL_TENANT);
    }

    // ── 候选 ID 由服务端生成 ────────────────────────────────────

    @Test
    void candidateIdsAreAssignedByPositionNotTakenFromTheModel() {
        // 模型自报的 candidateId 必须被丢弃：审批时靠它标识用户勾选项，模型可控即可伪造
        String argsWithForgedIds = """
                {"source":"面试评估","candidates":[
                  {"candidateId":"hacked","type":"INTERVIEW_WEAKNESS","content":"一"},
                  {"candidateId":"hacked","type":"PREPARATION_PLAN","content":"二"}
                ]}""";

        tool.execute(context(), argsWithForgedIds);

        assertThat(capturedPayload()).contains("\"candidateId\":\"c1\"")
                .contains("\"candidateId\":\"c2\"")
                .doesNotContain("hacked");
    }

    @Test
    void payloadSerializationIsDeterministic() {
        tool.execute(context(), ARGS);
        String first = capturedPayload();

        tool = new MemoryCandidateCreateTool(memoryService, draftService);
        tool.execute(context(), ARGS);
        String second = capturedPayload();

        // 幂等键是 sha256(...|payloadJson)：载荷若每次序列化都不同，跨重启去重就静默失效。
        // Map.of 的迭代顺序每次 JVM 启动随机，所以这里必须用有序结构。
        assertThat(first).isEqualTo(second);
    }

    // ── 校验 ───────────────────────────────────────────────────

    @Test
    void emptyCandidateListIsRejected() {
        ToolExecutionResult result = tool.execute(context(), "{\"source\":\"面试评估\",\"candidates\":[]}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        verify(draftService, never()).createDraft(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void missingSourceIsRejected() {
        ToolExecutionResult result = tool.execute(context(), "{\"candidates\":[{\"type\":\"JOB_PREFERENCE\",\"content\":\"x\"}]}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.modelText()).contains("source");
    }

    @Test
    void validationFailureFromTheSharedRulesBecomesInvalidArguments() {
        // 载荷校验复用执行侧那份规则（单一校验源），因此这里的失败就是用户会看到的措辞
        when(memoryService.parseBatch(anyString()))
                .thenThrow(new IllegalArgumentException("未知记忆类型：BOGUS，允许值：[JOB_PREFERENCE, ...]"));

        ToolExecutionResult result = tool.execute(context(), ARGS);

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(result.modelText()).contains("允许值");
        verify(draftService, never()).createDraft(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void malformedJsonIsRejectedWithoutThrowing() {
        ToolExecutionResult result = tool.execute(context(), "{不是 json");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
    }

    // ── 结果契约 ───────────────────────────────────────────────

    @Test
    void returnsPendingApprovalCarryingTheDraftId() {
        ToolExecutionResult result = tool.execute(context(), ARGS);

        assertThat(result.status()).isEqualTo(ToolResultStatus.PENDING_APPROVAL);
        // AgentRunner 靠 data.draftId 收集草稿 ID 并结束本轮
        assertThat(result.data()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.data()).get("draftId")).isEqualTo("draft-1");
        // 消息必须让模型明白「还没写进去」——它要转述给用户，含糊会让用户以为已经生效
        assertThat(result.modelText()).contains("尚未写入").contains("2 条");
    }

    @Test
    void isRequireApprovalSoTheModelCannotWriteMemoriesDirectly() {
        assertThat(tool.approvalMode()).isEqualTo(ApprovalMode.REQUIRE_APPROVAL);
    }

    private String capturedPayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(draftService, org.mockito.Mockito.atLeastOnce())
                .createDraft(anyString(), anyString(), anyString(), anyString(), payload.capture());
        return payload.getValue();
    }
}

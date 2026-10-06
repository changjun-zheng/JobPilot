package com.jobpilot.agent;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.ToolCall;
import com.jobpilot.ai.ToolDefinition;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.config.AgentProperties;
import com.jobpilot.security.UserContext;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环的行为约定（ARCHITECTURE.md §6）。
 * 全部用 mock ChatPort，不依赖 Ollama / MySQL。
 */
class AgentRunnerTest {

    private ChatPort chatPort;
    private AgentToolRegistry registry;
    private AgentTraceRecorder traceRecorder;
    private UsageRecorder usageRecorder;
    private AgentRunner runner;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        chatPort = mock(ChatPort.class);
        registry = new AgentToolRegistry(List.of());
        traceRecorder = mock(AgentTraceRecorder.class);
        usageRecorder = mock(UsageRecorder.class);
        runner = newRunner(5, 8, 1);
        UserContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private AgentRunner newRunner(int maxIterations, int maxToolCalls, int retry) {
        AgentProperties props = new AgentProperties(maxIterations, maxToolCalls,
                Duration.ofSeconds(2), retry, null, 0.2, 1024, "local");
        return new AgentRunner(chatPort, registry, traceRecorder, usageRecorder, props);
    }

    // ── 终止条件 ────────────────────────────────────────────────

    @Test
    void answersDirectlyWhenModelRequestsNoTools() {
        when(chatPort.chat(any())).thenReturn(completion("直接回答", List.of()));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "你好"));

        assertThat(result.answer()).isEqualTo("直接回答");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).kind()).isEqualTo("model");
    }

    @Test
    void executesToolThenContinuesToFinalAnswer() {
        RecordingTool tool = new RecordingTool("knowledge_search", "搜到了证据");
        runner = newRunner(5, 8, 1);
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(tool)), traceRecorder, usageRecorder,
                new AgentProperties(5, 8, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        when(chatPort.chat(any()))
                .thenReturn(toolCallCompletion("call-1", "knowledge_search", "{}"))
                .thenReturn(completion("最终答案", List.of()));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "我的项目经验？"));

        assertThat(result.answer()).isEqualTo("最终答案");
        assertThat(result.steps()).extracting(AgentRunner.Step::kind)
                .containsExactly("model", "tool", "model");
        assertThat(tool.lastContext.userId()).isEqualTo(TENANT);
    }

    @Test
    void stopsAtMaxIterationsWhenModelKeepsRequestingTools() {
        RecordingTool tool = new RecordingTool("loop", "还是调工具");
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(tool)), traceRecorder, usageRecorder,
                new AgentProperties(3, 100, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        when(chatPort.chat(any())).thenReturn(toolCallCompletion("c", "loop", "{}"));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "循环"));

        assertThat(result.finishReason()).isEqualTo(FinishReason.BUDGET_EXHAUSTED);
        assertThat(result.answer()).contains("最大迭代次数");
        assertThat(tool.callCount.get()).isEqualTo(3);
    }

    @Test
    void stopsWhenToolCallBudgetExhaustedAndSkipsTheRemainingTool() {
        RecordingTool tool = new RecordingTool("multi", "ok");
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(tool)), traceRecorder, usageRecorder,
                new AgentProperties(10, 2, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        // 模型一轮请求 3 个工具，但预算只有 2 → 第三个不得执行
        when(chatPort.chat(any())).thenReturn(new ChatCompletion("",
                List.of(new ToolCall("c1", "multi", "{}"),
                        new ToolCall("c2", "multi", "{}"),
                        new ToolCall("c3", "multi", "{}")),
                FinishReason.TOOL_CALLS, null, "test", "test"));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "多工具"));

        assertThat(result.finishReason()).isEqualTo(FinishReason.BUDGET_EXHAUSTED);
        assertThat(tool.callCount.get()).isEqualTo(2);
    }

    // ── 异常不得外抛（ARCHITECTURE §6）──────────────────────────

    @Test
    void toolFailureBecomesStructuredResultAndDoesNotEscapeRun() {
        AgentTool exploding = new AgentTool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "总是抛异常";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }

            @Override
            public com.jobpilot.ai.ApprovalMode approvalMode() {
                return com.jobpilot.ai.ApprovalMode.AUTO;
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson) {
                throw new IllegalStateException("数据库炸了");
            }
        };
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(exploding)), traceRecorder, usageRecorder,
                new AgentProperties(5, 8, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        when(chatPort.chat(any()))
                .thenReturn(toolCallCompletion("c1", "boom", "{}"))
                .thenReturn(completion("我无法完成该操作", List.of()));

        // 关键断言：run 正常返回，异常没有穿透
        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "触发异常"));

        assertThat(result.answer()).isEqualTo("我无法完成该操作");
        assertThat(result.steps()).extracting(AgentRunner.Step::status)
                .contains("failed");
    }

    @Test
    void unknownToolBecomesFailedResultRatherThanException() {
        when(chatPort.chat(any()))
                .thenReturn(toolCallCompletion("c1", "does_not_exist", "{}"))
                .thenReturn(completion("抱歉", List.of()));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "调不存在"));

        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("does_not_exist");
            assertThat(step.status()).isEqualTo("failed");
        });
    }

    @Test
    void modelFailureEndsRunWithErrorInsteadOfThrowing() {
        when(chatPort.chat(any())).thenThrow(new IllegalStateException("Ollama 离线"));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "问一句"));

        assertThat(result.finishReason()).isEqualTo(FinishReason.ERROR);
        assertThat(result.answer()).contains("模型调用失败");
    }

    @Test
    void retriesModelCallExactlyOnceThenGivesUp() {
        when(chatPort.chat(any())).thenThrow(new IllegalStateException("失败"));

        runner.run(new AgentRunner.RunRequest("conv-1", "问一句"));

        // llmRetry=1 → 首次 + 1 次重试 = 恰好 2 次
        verify(chatPort, times(2)).chat(any());
    }

    // ── 用量计量（I-3c，PRD-FP-10）──────────────────────────────

    @Test
    void recordsLlmUsagePerModelCallPlusOneAgentRunRow() {
        when(chatPort.chat(any())).thenReturn(new ChatCompletion("直接回答", List.of(),
                FinishReason.STOP, new com.jobpilot.ai.TokenUsage(3, 5), "test", "qwen2.5:3b"));

        runner.run(new AgentRunner.RunRequest("conv-1", "你好"));

        // 每次模型调用一行（tokens 透传供应商返回值），外加每 run 一行汇总
        verify(usageRecorder).recordLlmCall(eq(TENANT), eq(UsageScenario.AGENT), eq("qwen2.5:3b"),
                eq(new com.jobpilot.ai.TokenUsage(3, 5)), eq(true), anyString());
        verify(usageRecorder).recordAgentRun(eq(TENANT), eq("qwen2.5:3b"), eq(1), eq(0), eq(true), anyString());
    }

    @Test
    void failedModelCallRecordsFailedUsageRowWithoutTokens() {
        when(chatPort.chat(any())).thenThrow(new IllegalStateException("Ollama 离线"));

        runner.run(new AgentRunner.RunRequest("conv-1", "问一句"));

        // 调用确实发生 → FAILED 行；消耗不可知 → usage 为 null（不得编 0）
        verify(usageRecorder).recordLlmCall(eq(TENANT), eq(UsageScenario.AGENT),
                isNull(), isNull(), eq(false), anyString());
        verify(usageRecorder).recordAgentRun(eq(TENANT), isNull(), eq(1), eq(0), eq(false), anyString());
    }

    // ── HITL：不在环上等（ARCHITECTURE §4.4）──────────────────

    @Test
    void pendingApprovalEndsRunWithoutAnotherModelCall() {
        AgentTool approvalTool = new AgentTool() {
            @Override
            public String name() {
                return "save_it";
            }

            @Override
            public String description() {
                return "写入类工具";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }

            @Override
            public com.jobpilot.ai.ApprovalMode approvalMode() {
                return com.jobpilot.ai.ApprovalMode.REQUIRE_APPROVAL;
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson) {
                return ToolExecutionResult.pendingApproval(context.callId(), name(), "已生成待审批草稿",
                        java.util.Map.of("draftId", "draft-123"));
            }
        };
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(approvalTool)), traceRecorder, usageRecorder,
                new AgentProperties(5, 8, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        when(chatPort.chat(any())).thenReturn(toolCallCompletion("c1", "save_it", "{}"));

        AgentRunner.RunResult result = runner.run(new AgentRunner.RunRequest("conv-1", "保存它"));

        // 只调了一次模型：草稿产生后立即结束，不再让模型接着说话
        verify(chatPort, times(1)).chat(any());
        assertThat(result.draftIds()).containsExactly("draft-123");
        assertThat(result.answer()).contains("待审批");
    }

    // ── 租户：未认证即失败，且绝不碰 ChatPort ──────────────────

    @Test
    void unauthenticatedRunFailsBeforeTouchingChatPort() {
        UserContext.clear();

        assertThatThrownBy(() -> runner.run(new AgentRunner.RunRequest("conv-1", "你好")))
                .isInstanceOf(UnauthorizedException.class);

        verify(chatPort, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void toolReceivesTenantFromContextNotFromArguments() {
        RecordingTool tool = new RecordingTool("echo", "ok");
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(tool)), traceRecorder, usageRecorder,
                new AgentProperties(5, 8, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        // 模型在参数里塞了一个 userId，runner 注入的仍必须是认证上下文的租户
        when(chatPort.chat(any()))
                .thenReturn(toolCallCompletion("c1", "echo", "{\"userId\":\"victim-tenant\"}"))
                .thenReturn(completion("done", List.of()));

        runner.run(new AgentRunner.RunRequest("conv-1", "带伪造参数"));

        assertThat(tool.lastContext.userId()).isEqualTo(TENANT);
    }

    // ── 跨 run 上下文（I-4）─────────────────────────────────────

    @Test
    void priorMessagesAreSplicedBetweenSystemAndCurrentUserMessage() {
        when(chatPort.chat(any())).thenReturn(completion("答", List.of()));

        List<AgentMessage> prior = List.of(
                new AgentMessage.User("上一轮的问题"),
                new AgentMessage.Assistant("上一轮的回答", List.of()));
        runner.run(new AgentRunner.RunRequest("conv-1", "这一轮的问题", prior));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatPort).chat(captor.capture());
        List<AgentMessage> sent = captor.getValue().messages();
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0)).isInstanceOf(AgentMessage.System.class);
        assertThat(sent.get(1).text()).isEqualTo("上一轮的问题");
        assertThat(sent.get(2).text()).isEqualTo("上一轮的回答");
        assertThat(sent.get(3)).isInstanceOf(AgentMessage.User.class);
        assertThat(sent.get(3).text()).isEqualTo("这一轮的问题");
    }

    @Test
    void contextCompactionKeepsTheSystemMessage() {
        RecordingTool tool = new RecordingTool("loop", "ok");
        runner = new AgentRunner(chatPort, new AgentToolRegistry(List.of(tool)), traceRecorder, usageRecorder,
                new AgentProperties(40, 100, Duration.ofSeconds(2), 1, null, 0.2, 1024, "local"));
        when(chatPort.chat(any())).thenReturn(toolCallCompletion("c", "loop", "{}"));

        runner.run(new AgentRunner.RunRequest("conv-1", "循环"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatPort, org.mockito.Mockito.atLeast(2)).chat(captor.capture());
        ChatRequest last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        // 旧实现取「最近 N 条」会把 System 一起截掉，模型随即失去全部行为约束
        assertThat(last.messages().get(0)).isInstanceOf(AgentMessage.System.class);
        assertThat(last.messages().size()).isLessThanOrEqualTo(60);
    }

    // ── helpers ────────────────────────────────────────────────

    private ChatCompletion completion(String content, List<ToolCall> toolCalls) {
        return new ChatCompletion(content, toolCalls, FinishReason.STOP, null, "test", "test-model");
    }

    private ChatCompletion toolCallCompletion(String callId, String toolName, String arguments) {
        return new ChatCompletion("", List.of(new ToolCall(callId, toolName, arguments)),
                FinishReason.TOOL_CALLS, null, "test", "test-model");
    }

    private static final class RecordingTool implements AgentTool {
        private final String name;
        private final String reply;
        private final AtomicInteger callCount = new AtomicInteger();
        private ToolExecutionContext lastContext;

        private RecordingTool(String name, String reply) {
            this.name = name;
            this.reply = reply;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public String inputSchema() {
            return "{}";
        }

        @Override
        public com.jobpilot.ai.ApprovalMode approvalMode() {
            return com.jobpilot.ai.ApprovalMode.AUTO;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson) {
            callCount.incrementAndGet();
            lastContext = context;
            return ToolExecutionResult.success(context.callId(), name, reply, null);
        }
    }
}

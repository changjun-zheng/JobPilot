package com.jobpilot.agent;

import com.jobpilot.common.RequestId;
import com.jobpilot.config.AgentProperties;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.ToolCall;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.ai.ToolResultStatus;
import com.jobpilot.usage.UsageRecorder;
import com.jobpilot.usage.UsageScenario;

/**
 * 自研的同步 ReAct 循环（ARCHITECTURE.md §4.3 / §6）。
 *
 * <h3>为什么自研而不用 Spring AI 的 ToolCallingAdvisor</h3>
 * {@code ToolCallingAdvisor} 能替我们跑完整个循环，但它把工具执行关在适配器内部——
 * 而租户上下文注入、预算计数、trace 记录、HITL 短路全都必须发生在<b>工具执行那一刻</b>。
 * 交给它，这些控制权就没了。循环本身只有约 200 行，自己写反而更清楚。
 *
 * <h3>三条不变量</h3>
 * <ol>
 *   <li><b>工具异常不外抛</b>：任何失败都转成 {@code FAILED} 结果回填给模型，
 *       由模型在预算内决定重试还是向用户说明（ARCHITECTURE §6）；</li>
 *   <li><b>租户只来自 {@link UserContext}</b>，模型参数里的任何 userId 都被忽略；</li>
 *   <li><b>HITL 不在环上等</b>：工具返回 {@code PENDING_APPROVAL} 就结束本轮 run，
 *       用户稍后经独立接口审批（ARCHITECTURE §4.4）。</li>
 * </ol>
 */
@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /**
     * 上下文条数上限；超过时保留首条 system 与最近若干条。
     * <p>
     * 本期不做自动摘要（PRD-FP-2.1 允许明确截断并记录），但<b>截断必须可见</b>——
     * 静默丢掉中间轮次会让模型「忘记」自己已经查过什么，然后重复调用同一个工具。
     */
    private static final int MAX_CONTEXT_MESSAGES = 40;

    private final ChatPort chatPort;
    private final AgentToolRegistry toolRegistry;
    private final AgentTraceRecorder traceRecorder;
    private final UsageRecorder usageRecorder;
    private final AgentProperties props;
    private final ExecutorService llmExecutor;

    public AgentRunner(ChatPort chatPort,
                       AgentToolRegistry toolRegistry,
                       AgentTraceRecorder traceRecorder,
                       UsageRecorder usageRecorder,
                       AgentProperties props) {
        this.chatPort = chatPort;
        this.toolRegistry = toolRegistry;
        this.traceRecorder = traceRecorder;
        this.usageRecorder = usageRecorder;
        this.props = props;
        // 复用项目既有的守护线程工厂（与 IngestWorker 同一写法），不用裸 new Thread：
        // 后者会被 check-arch.sh 的 no-raw-thread 规则拦下
        this.llmExecutor = Executors.newCachedThreadPool(
                new CustomizableThreadFactory("agent-llm-"));
    }

    /** 一次 run 的输入；{@code conversationId} 为空时新建 */
    public record RunRequest(String conversationId, String userMessage) {
    }

    /**
     * 一次 run 的结果。
     *
     * @param steps    逐步摘要（模型步 + 工具步），用于响应体展示与排查
     * @param draftIds 本轮产生的待审批草稿 ID；模型据此告知用户去审批
     */
    public record RunResult(
            String traceId,
            String conversationId,
            String answer,
            FinishReason finishReason,
            List<Step> steps,
            List<String> draftIds
    ) {
    }

    /** 单步摘要，与 {@code agent_trace_step} 一一对应 */
    public record Step(int iteration, String kind, String name, long durationMs, String status, String summary) {
    }

    /**
     * 每轮 run 的 system prompt。
     *
     * <p><b>「必须先调工具」这句是实测逼出来的，不是修辞。</b>本机 qwen2.5:3b 在弱提示下
     * 会反问用户「请提供你的用户名」而<b>不调用</b> {@code knowledge_search}；把规则写死后
     * 才稳定产出 tool_calls。小模型的工具调用意愿需要明确指令，不能指望它自己领会。
     *
     * <p>同理「不要编造」与「引用用 [n]」：I-0 起就确立了「引用列表由服务端从命中 Chunk 组装、
     * 模型只负责正文」这条不变量，prompt 只是把它讲给模型听。
     */
    private static final String SYSTEM_PROMPT = """
            你是 JobPilot 求职助手。规则：
            1. 回答任何涉及用户自身经历（简历、项目、技术栈、工作经历）的问题前，**必须先调用 knowledge_search**，
               拿到证据后再作答。禁止在没有调用工具的情况下直接回答这类问题；
            2. 只能依据工具返回的【证据】回答，不得编造；
            3. 引用证据时使用其编号，形如 [1]、[2]；
            4. 知识库中没有相关证据时，直接回答"知识库中没有找到相关依据"，不要猜测；
            5. 分析 JD 时，基于 job_description_analyze 返回的材料逐条对照，缺少依据的项写"未找到依据"；
            6. 涉及投递记录时必须用工具，不要凭印象作答：
               - 用户说「投了某家」「帮我记一下」→ application_create；
               - 说「那家面试了」「状态改成…」→ 先用 application_query 拿到 ID，再 application_update；
               - 问「投了哪些」「有没有跟进」→ application_query；
               - 问「总共投了多少家」「多少在面试」→ application_stats；
               - **创建前先 application_query 查重**，同一公司同一岗位不要重复录入；
            7. 面试复盘、简历分析后若发现值得长期记住的偏好、弱点或计划，用 memory_candidate_create
               提炼成候选条目。**它会生成待审批草稿，不会立即生效**——必须告诉用户去确认；
               记忆只放短小条目（一条一句话），长篇材料用 save_jd_analysis_to_kb 存进知识库；
            8. 投递类工具的日期参数用 yyyy-MM-dd；状态取值以工具说明里列出的枚举为准，不要自造；
            9. 回答使用简体中文，简洁分点。
            """;

    public RunResult run(RunRequest request) {
        // 未认证即失败，绝不带着空租户往下走
        String userId = UserContext.require();
        String traceId = Objects.requireNonNullElseGet(
                org.slf4j.MDC.get(RequestId.MDC_KEY), () -> UUID.randomUUID().toString());
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();

        List<AgentMessage> history = new ArrayList<>(List.of(
                new AgentMessage.System(SYSTEM_PROMPT),
                new AgentMessage.User(request.userMessage())));
        List<Step> steps = new ArrayList<>();
        List<String> draftIds = new ArrayList<>();
        int iterations = 0;
        int toolCalls = 0;
        String lastModel = props.model();
        long startedAt = System.currentTimeMillis();

        traceRecorder.start(traceId, userId, conversationId,
                org.slf4j.MDC.get(RequestId.MDC_KEY));

        while (true) {
            if (iterations >= props.maxIterations()) {
                return finish(traceId, userId, conversationId, startedAt, steps, draftIds,
                        FinishReason.BUDGET_EXHAUSTED,
                        "已达到最大迭代次数 " + props.maxIterations() + "，未能得出结论。", "BUDGET_EXHAUSTED",
                        iterations, toolCalls, lastModel);
            }
            iterations++;
            long callStart = System.currentTimeMillis();

            ChatCompletion completion;
            try {
                completion = callModel(history);
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - callStart;
                traceRecorder.step(traceId, userId, iterations, "model", "unknown", "failed",
                        cost, safeMessage(e), null, ToolErrorCode.TIMEOUT);
                log.warn("Agent 模型调用失败 traceId={} iteration={}", traceId, iterations, e);
                // 最终失败也计一行：调用确实发生了（消耗不可知 → tokens 为 NULL），不能凭空消失
                usageRecorder.recordLlmCall(userId, UsageScenario.AGENT, props.model(), null, false, traceId);
                return finish(traceId, userId, conversationId, startedAt, steps, draftIds,
                        FinishReason.ERROR, "模型调用失败，请稍后重试。", "ERROR",
                        iterations, toolCalls, lastModel);
            }

            long modelCost = System.currentTimeMillis() - callStart;
            steps.add(new Step(iterations, "model", "chat",
                    modelCost, "ok", summarize(completion.content())));
            traceRecorder.step(traceId, userId, iterations, "model", "chat", "ok",
                    modelCost, summarize(completion.content()), null, null);
            usageRecorder.recordLlmCall(userId, UsageScenario.AGENT, completion.model(),
                    completion.usage(), true, traceId);
            if (completion.model() != null) {
                lastModel = completion.model();
            }

            // 终止条件一：模型不再请求工具 → 这是最终答案
            if (!completion.hasToolCalls()) {
                history.add(new AgentMessage.Assistant(completion.content(), List.of()));
                return finish(traceId, userId, conversationId, startedAt, steps, draftIds,
                        FinishReason.STOP, completion.content(), "OK",
                        iterations, toolCalls, lastModel);
            }

            history.add(new AgentMessage.Assistant(completion.content(), completion.toolCalls()));
            boolean pendingApproval = false;

            for (ToolCall call : completion.toolCalls()) {
                // 预算：工具调用总数。超限时**尚未执行**的那个工具就此放弃
                if (toolCalls >= props.maxToolCallsPerRun()) {
                    return finish(traceId, userId, conversationId, startedAt, steps, draftIds,
                            FinishReason.BUDGET_EXHAUSTED,
                            "已达到工具调用上限 " + props.maxToolCallsPerRun() + "，中止本轮。",
                            "BUDGET_EXHAUSTED", iterations, toolCalls, lastModel);
                }
                toolCalls++;
                long toolStart = System.currentTimeMillis();
                ToolExecutionResult result = executeTool(call, userId, conversationId, traceId);
                long toolCost = System.currentTimeMillis() - toolStart;

                steps.add(new Step(iterations, "tool", call.name(), toolCost,
                        result.status().name().toLowerCase(), summarize(result.modelText())));
                traceRecorder.step(traceId, userId, iterations, "tool", call.name(),
                        result.status().name().toLowerCase(), toolCost,
                        summarize(result.modelText()), call.arguments(), result.errorCode());

                history.add(new AgentMessage.ToolResult(
                        call.id(), call.name(), result.status(), result.modelText()));

                if (result.status() == ToolResultStatus.PENDING_APPROVAL) {
                    pendingApproval = true;
                    String draftId = extractDraftId(result.data());
                    if (draftId != null) {
                        draftIds.add(draftId);
                    }
                }
            }

            // 终止条件二：HITL 不在环上等，草稿产生即结束本轮
            if (pendingApproval) {
                return finish(traceId, userId, conversationId, startedAt, steps, draftIds,
                        FinishReason.STOP, "已生成待审批草稿，请前往审批中心确认后再执行。",
                        "PENDING_APPROVAL", iterations, toolCalls, lastModel);
            }
            history = compactIfTooLong(history, traceId, userId, iterations);
        }
    }

    /**
     * 执行单个工具，<b>永不向调用方抛异常</b>。
     * <p>
     * 三种失败都在这里被转成结构化结果：未知工具、工具抛异常、以及兜底的兜底。
     */
    private ToolExecutionResult executeTool(ToolCall call, String userId, String conversationId, String traceId) {
        AgentTool tool = toolRegistry.find(call.name()).orElse(null);
        if (tool == null) {
            return ToolExecutionResult.failed(call.id(), call.name(),
                    "未知工具：" + call.name() + "。可用工具请以工具清单为准。", ToolErrorCode.UNKNOWN_TOOL);
        }
        ToolExecutionContext context = new ToolExecutionContext(
                userId, conversationId, traceId, call.id(), tool.approvalMode());
        try {
            return tool.execute(context, call.arguments());
        } catch (Exception e) {
            log.warn("Agent 工具执行异常 traceId={} tool={}", traceId, call.name(), e);
            return ToolExecutionResult.failed(call.id(), call.name(),
                    "工具执行失败：" + safeMessage(e), ToolErrorCode.TOOL_FAILED);
        }
    }

    /**
     * 模型调用：超时 + 有限重试（ARCHITECTURE §6：30 秒，重试 1 次）。
     * <p>
     * 超时靠「把调用丢到线程池 + {@code future.get(timeout)}」实现，而不是靠 Ollama 客户端自己的
     * 超时设置——端口之后可能换成别的 provider，只有这一层能保证预算真的被遵守。
     * 超时后 {@code cancel(true)} 打断，避免线程被一个卡死的模型调用永久占住。
     */
    private ChatCompletion callModel(List<AgentMessage> history) throws Exception {
        ChatRequest request = new ChatRequest(history, toolRegistry.definitions(),
                props.model(), props.temperature(), props.maxTokens(), props.llmTimeout());

        Exception lastFailure = null;
        for (int attempt = 0; attempt <= props.llmRetry(); attempt++) {
            try {
                return invokeWithTimeout(request);
            } catch (TimeoutException e) {
                lastFailure = e;
                log.warn("Agent 模型调用超时（第 {} 次），llmTimeout={}", attempt + 1, props.llmTimeout());
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Agent 模型调用第 {} 次失败，将重试", attempt + 1, e);
            }
        }
        throw lastFailure == null ? new IllegalStateException("模型调用失败") : lastFailure;
    }

    private ChatCompletion invokeWithTimeout(ChatRequest request) throws Exception {
        CompletableFuture<ChatCompletion> future =
                CompletableFuture.supplyAsync(() -> chatPort.chat(request), llmExecutor);
        try {
            return future.get(props.llmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * 上下文过长时截断：保留最近的消息。
     * <p>
     * 截断会记一条 step，让「模型为什么像失忆了一样」在 trace 里可查——
     * 静默丢弃中间轮次是这类问题最难查的成因。
     */
    private List<AgentMessage> compactIfTooLong(List<AgentMessage> history, String traceId,
                                                String userId, int iteration) {
        if (history.size() <= MAX_CONTEXT_MESSAGES) {
            return history;
        }
        log.warn("Agent 上下文超长（{} 条），截断到最近 {} 条", history.size(), MAX_CONTEXT_MESSAGES);
        traceRecorder.step(traceId, userId, iteration, "system", "context-compaction", "ok", 0,
                "上下文由 " + history.size() + " 条截断至 " + MAX_CONTEXT_MESSAGES + " 条", null, null);
        return new ArrayList<>(history.subList(history.size() - MAX_CONTEXT_MESSAGES, history.size()));
    }

    /**
     * 收尾：trace 终态 + 一行 AGENT_RUN 计量（PRD-FP-10「Agent 调用次数按 run 统计，含工具调用次数」）。
     * {@code finishReason == ERROR} 视为失败行，其余终态（含预算耗尽）都是真实发生的 run。
     */
    private RunResult finish(String traceId, String userId, String conversationId, long startedAt,
                             List<Step> steps, List<String> draftIds,
                             FinishReason reason, String answer, String status,
                             int iterations, int toolCalls, String model) {
        long cost = System.currentTimeMillis() - startedAt;
        traceRecorder.finish(traceId, userId, conversationId, cost, status, reason.name());
        usageRecorder.recordAgentRun(userId, model, iterations, toolCalls,
                !"ERROR".equals(status), traceId);
        return new RunResult(traceId, conversationId, answer, reason, List.copyOf(steps), List.copyOf(draftIds));
    }

    private String extractDraftId(Object data) {
        return data instanceof java.util.Map<?, ?> map && map.get("draftId") instanceof String id ? id : null;
    }

    private String summarize(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

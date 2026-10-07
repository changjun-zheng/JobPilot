package com.jobpilot.ai.adapter;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.TokenUsage;
import com.jobpilot.ai.ToolCall;
import com.jobpilot.ai.ToolDefinition;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@code ChatModel} 适配器的公共骨架（本地 Ollama 与云端 OpenAI 兼容共用）。
 *
 * <h3>为什么只映射工具「定义」，不执行工具</h3>
 * Spring AI 自带 {@code ToolCallingManager} / {@code ToolCallingAdvisor}，可以直接替我们跑完整个
 * ReAct 循环。本项目<b>刻意不用</b>：那会把工具执行关进适配器内部，而工具执行必须发生在
 * {@code AgentRunner} 里——租户上下文注入、预算计数、trace、HITL 短路全都在那儿。
 * 所以这里的 {@code ToolCallback} 只提供定义，{@link ToolCallback#call(String)} 永不执行。
 *
 * <h3>子类只差两点</h3>
 * providers 的 options 类型不兼容（{@code OllamaChatOptions} vs {@code OpenAiChatOptions}），
 * 所以 {@link #toSpringOptions} 由子类实现；{@link #providerId} 决定写进 {@code ChatCompletion.provider} 的值。
 * 其余（消息映射、工具调用回读、usage 提取、模型回落）全在这里，改一处即可。
 */
abstract class AbstractSpringAiChatAdapter implements ChatPort {

    protected final ChatModel chatModel;

    protected AbstractSpringAiChatAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** provider id（写进 ChatCompletion.provider，供计量与排查）：ollama / openai */
    protected abstract String providerId();

    /** 该 provider 的 options 构造（类型不兼容，各自实现） */
    protected abstract ChatOptions toSpringOptions(ChatRequest request);

    /** 「模型未配置」错误里提示该配哪个属性 */
    protected abstract String modelPropertyHint();

    @Override
    public ChatCompletion chat(ChatRequest request) {
        ChatResponse response = chatModel.call(
                new Prompt(toSpringMessages(request.messages()), toSpringOptions(request)));
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        if (output == null) {
            throw new IllegalStateException(providerId() + " 未返回回答");
        }
        String content = output.getText() == null ? "" : output.getText().trim();
        List<ToolCall> toolCalls = toJobPilotToolCalls(output);
        // 模型只请求工具时没有正文，这是正常的；两者皆空才是异常（沿用 I-0 的判定）
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalStateException(providerId() + " 未返回有效回答");
        }
        return new ChatCompletion(content, toolCalls,
                toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS,
                toTokenUsage(response), providerId(), resolveModelName());
    }

    /**
     * Spring AI 侧配置的默认模型名。
     * <p>取不到时返回 {@code null} 而非编一个默认值：报「未配置模型」比报「模型 x 不存在」更好定位。
     */
    protected String resolveModelName() {
        ChatOptions defaults = chatModel.getDefaultOptions();
        return defaults == null ? null : defaults.getModel();
    }

    /** 本轮用哪个模型：请求优先 → Spring AI 默认 → 都没有则早失败并说清该配什么 */
    protected String resolveModel(ChatRequest request) {
        String model = request.model() != null && !request.model().isBlank()
                ? request.model()
                : resolveModelName();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "未指定模型：请在请求的 model 中给出，或配置 " + modelPropertyHint());
        }
        return model;
    }

    /**
     * Spring AI 的 Usage → 端口 record（FP-10 计量的数据来源，只有适配器摸得到它）。
     * <p>
     * <b>坑：供应商未返回用量时，Spring AI 给的不是 null 而是 {@link EmptyUsage} 占位（0/0）。</b>
     * 不识别它就会把「没数据」记成「零消耗」——把不可用伪装成零，正是计量口径禁止的估算。
     */
    private TokenUsage toTokenUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null || metadata.getUsage() == null
                || metadata.getUsage() instanceof EmptyUsage) {
            return null;
        }
        Integer input = metadata.getUsage().getPromptTokens();
        Integer output = metadata.getUsage().getCompletionTokens();
        if (input == null && output == null) {
            return null;
        }
        return new TokenUsage(input, output);
    }

    private List<ToolCall> toJobPilotToolCalls(AssistantMessage output) {
        if (!output.hasToolCalls()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            calls.add(new ToolCall(call.id(), call.name(), call.arguments()));
        }
        return calls;
    }

    /** JobPilot 消息 → Spring AI 消息。{@link AgentMessage} 是 sealed，switch 穷尽性由编译器保证 */
    private List<Message> toSpringMessages(List<AgentMessage> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (AgentMessage message : messages) {
            switch (message) {
                case AgentMessage.System m -> result.add(new SystemMessage(m.text()));
                case AgentMessage.User m -> result.add(new UserMessage(m.text()));
                case AgentMessage.Assistant m -> result.add(AssistantMessage.builder()
                        .content(m.text())
                        .toolCalls(m.toolCalls().stream()
                                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                                .toList())
                        .build());
                case AgentMessage.ToolResult m -> result.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                m.callId(), m.name(), m.text())))
                        .build());
            }
        }
        return result;
    }

    protected static ToolCallback toToolCallback(ToolDefinition definition) {
        return new DefinitionOnlyToolCallback(definition);
    }

    /**
     * 只提供工具定义的 {@link ToolCallback}；{@link #call(String)} 不可达。
     * <p>
     * 刻意实现 Spring AI 的接口而不是用 {@code FunctionToolCallback}：后者的 builder 需要一个
     * 真实函数，而这里根本没有可执行的函数——执行权在 runner 手里。
     */
    private record DefinitionOnlyToolCallback(ToolDefinition source) implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return new DefaultToolDefinition(
                    source.name(), source.description(), source.inputSchema());
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException(
                    "工具执行由 AgentRunner 负责，适配器只提供定义；不应在此调用 " + source.name());
        }
    }
}

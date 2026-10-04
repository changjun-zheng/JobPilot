package com.jobpilot.ai.adapter;

import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.ToolCall;
import com.jobpilot.ai.ToolDefinition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatModel 适配器（非流式）。
 * 业务层只依赖 ChatPort；Spring AI 负责 Ollama 协议与响应模型转换，Agent 控制流由 JobPilot 自己实现。
 *
 * <h3>为什么只映射工具「定义」，不执行工具</h3>
 * Spring AI 自带 {@code ToolCallingManager} 与 {@code ToolCallingAdvisor}，可以直接替我们跑完整个
 * ReAct 循环。本项目<b>刻意不用</b>：那会把工具执行关进适配器内部，而工具执行必须发生在
 * {@code AgentRunner} 里——租户上下文注入、预算计数、trace 记录、HITL 短路全都在那儿。
 * 一旦交给 Spring 的 manager，这些控制权就跟着没了。
 * <p>
 * 所以这里的 {@code ToolCallback} 只提供定义（name / description / inputSchema），
 * 它的 {@link ToolCallback#call(String)} 永不执行：裸 {@code ChatModel.call} 不带 advisor 时
 * Spring AI 不会自动调工具。工具请求由模型返回给 {@code AgentRunner}，由 runner 分发。
 */
@Component
public class OllamaChatAdapter implements ChatPort {

    private final ChatModel chatModel;

    public OllamaChatAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        // 单一代码路径：单轮问答只是「不带工具的一次 chat」
        return chat(new ChatRequest(
                List.of(new AgentMessage.System(systemPrompt), new AgentMessage.User(userPrompt)),
                List.of(), null, null, null, null)).content();
    }

    @Override
    public ChatCompletion chat(ChatRequest request) {
        var response = chatModel.call(new Prompt(toSpringMessages(request.messages()), toSpringOptions(request)));
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        if (output == null) {
            throw new IllegalStateException("Ollama 未返回回答");
        }
        String content = output.getText() == null ? "" : output.getText().trim();
        List<ToolCall> toolCalls = toJobPilotToolCalls(output);
        // 模型只请求工具时没有正文，这是正常的；两者皆空才是异常（沿用 I-0 的判定）
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalStateException("Ollama 未返回有效回答");
        }
        return new ChatCompletion(content, toolCalls,
                toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS,
                null, "ollama", modelName());
    }

    /**
     * Spring AI 侧配置的默认模型名。
     * <p>
     * 取不到时返回 {@code null} 而非编一个默认值：模型名猜错会让 Ollama 报 404，
     * 报「未配置模型」比报「模型 x 不存在」更容易定位。
     */
    private String modelName() {
        ChatOptions defaults = chatModel.getDefaultOptions();
        return defaults == null ? null : defaults.getModel();
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

    /**
     * 组装 Spring AI 的调用选项。
     *
     * <p><b>必须用 {@code OllamaChatOptions} 而不是 {@code ToolCallingChatOptions.builder()}</b>：
     * 后者产出 {@code DefaultToolCallingChatOptions}，而 Ollama 的 chat model 内部会把
     * options 强转成 {@code OllamaChatOptions}，直接 {@code ClassCastException}。
     * {@code OllamaChatOptions} 本身就实现了 {@code ToolCallingChatOptions}，
     * 所以工具回调、模型名这些设置方式完全一致。
     *
     * <p><b>model 必须显式给，不能指望自动回落。</b>不设 model 时它会被当作 null 一路传到
     * Ollama，对方报 {@code model cannot be null or empty}——它<b>不会</b>去用
     * {@code ChatModel.getDefaultOptions()} 里的模型。
     */
    private ChatOptions toSpringOptions(ChatRequest request) {
        String model = request.model() != null && !request.model().isBlank()
                ? request.model()
                : modelName();
        if (model == null || model.isBlank()) {
            // 请求没指定、Spring AI 侧也没配默认模型：早失败并说清楚该配什么，
            // 而不是让 null 一路传到 Ollama 变成一句莫名其妙的 "model cannot be null or empty"
            throw new IllegalStateException(
                    "未指定模型：请在请求的 model 中给出，或配置 spring.ai.ollama.chat.options.model");
        }
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .toolCallbacks(request.tools().stream().map(OllamaChatAdapter::toToolCallback).toList())
                .model(model);
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        return builder.build();
    }

    private static ToolCallback toToolCallback(ToolDefinition definition) {
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

package com.jobpilot.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/**
 * 四个投递类工具共用的参数解析与错误映射。
 * <p>
 * 抽出来是因为四个工具都要做同样三件事，而不是为了「将来可能加工具」——那是提前抽象。
 *
 * <h3>错误映射的边界（刻意收窄）</h3>
 * 只把两类<b>预期内</b>异常转成结构化失败：
 * <ul>
 *   <li>{@link IllegalArgumentException} —— 参数非法、状态未知、关联文档不存在；</li>
 *   <li>{@link ApiException} —— 目标不存在或不属于当前租户。</li>
 * </ul>
 * 其余异常一律<b>不接</b>，让它冒到 {@code AgentRunner} 变成 {@code TOOL_FAILED}。
 * 若把整段都包进 catch，真正的程序 bug（NPE 之类）会被伪装成「模型参数写错了」，
 * trace 里的归因就骗人了。
 */
final class ApplicationToolSupport {

    private static final Logger log = LoggerFactory.getLogger(ApplicationToolSupport.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApplicationToolSupport() {
    }

    /** 解析参数；坏 JSON 抛 {@link IllegalArgumentException}，由 {@link #guarded} 统一转成失败结果 */
    static JsonNode parse(String argumentsJson) {
        try {
            return MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON");
        }
    }

    /**
     * 模型若在参数里塞了 {@code userId}，忽略并留痕。
     * <p>租户只来自认证上下文——模型输出是不可信输入，不是授权凭据。
     */
    static void warnForgedUserId(JsonNode args, String toolName, ToolExecutionContext context) {
        if (args.has("userId")) {
            log.warn("{} 参数中出现 userId，已忽略（租户只来自认证上下文）traceId={}",
                    toolName, context.traceId());
        }
    }

    static String requiredText(JsonNode args, String field) {
        String value = args.path(field).asText("").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数 " + field);
        }
        return value;
    }

    /** 可选字符串；缺省或空视为「未提供」 */
    static String optionalText(JsonNode args, String field) {
        String value = args.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    /** 可选日期（yyyy-MM-dd）；格式错显式报错而不是静默当没传 */
    static LocalDate optionalDate(JsonNode args, String field) {
        String value = args.path(field).asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 需为 yyyy-MM-dd 格式，收到：" + value);
        }
    }

    /**
     * 执行并映射预期内失败。
     *
     * @param callId  当前工具调用 ID，用于让模型把结果与请求配对
     * @param toolName 工具名，用于结果与 trace
     * @param action  真正的业务调用；返回给模型看的摘要文本
     */
    static ToolExecutionResult guarded(String callId, String toolName, Supplier<String> action) {
        try {
            return ToolExecutionResult.success(callId, toolName, action.get(), null);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failed(callId, toolName, e.getMessage(), ToolErrorCode.INVALID_ARGUMENTS);
        } catch (ApiException e) {
            // 不存在与跨租户在服务层已被合并成同一个 NOT_FOUND，消息里不会泄漏他人数据的存在性
            return ToolExecutionResult.failed(callId, toolName, e.getMessage(), ToolErrorCode.NOT_FOUND);
        }
    }
}

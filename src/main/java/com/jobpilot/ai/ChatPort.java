package com.jobpilot.ai;

/**
 * 对话生成端口。
 * <p>
 * <b>端口不执行工具。</b>{@link #chat} 只会带回模型的 {@link ToolCall} 请求，
 * 执行由 {@code AgentRunner} 负责——预算、trace、租户上下文注入与 HITL 短路都必须在端口之外。
 * <p>
 * I-0 时代还有一个单轮便捷方法 {@code complete(systemPrompt, userPrompt)}；I-3c 起问答链路
 * 也改走 {@link #chat}——用量计量需要带回 {@link ChatCompletion#usage()}，String 返回值装不下，
 * 而为计量再造一个「会返回用量的 complete」就是两个几乎相同的端口方法。没有调用方的端口方法
 * 按项目约定删除，不留死接口。
 */
public interface ChatPort {

    /** 对话补全；可能带回工具调用请求，但<b>不执行</b>它们 */
    ChatCompletion chat(ChatRequest request);
}

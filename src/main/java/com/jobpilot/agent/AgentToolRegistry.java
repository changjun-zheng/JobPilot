package com.jobpilot.agent;

import com.jobpilot.ai.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：由容器注入的 {@link List<AgentTool>} 按名字建索引。
 * <p>
 * <b>刻意不做成插件/SPI</b>——加一个 {@code @Component} 就是注册，加一个就是注销，
 * 没有配置文件、没有扫描顺序、没有生命周期（AGENTS.md「不提前创建空接口/插件系统」）。
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> toolsByName;   // 给 Runner 看
    private final List<ToolDefinition> definitions;   // 给大模型看

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool tool : tools) {
            AgentTool previous = byName.put(tool.name(), tool);
            if (previous != null) {
                // 重名意味着模型会在两个实现之间随机挑一个，且 trace 无法归因——宁可启动失败
                throw new IllegalStateException("工具名重复：" + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(byName);
        this.definitions = byName.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    /** 发给模型的工具清单；空列表表示本轮不给模型任何工具（退化为纯对话） */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }
}

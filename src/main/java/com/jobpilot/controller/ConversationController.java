package com.jobpilot.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.conversation.ConversationService;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话查询（PRD-FP-6 对话页：新建 / 切换 / 查看历史会话）。
 * <p>
 * <b>只读 + 删除</b>：会话没有独立的创建接口——首轮 {@code POST /agent/run} 不带 {@code conversationId}
 * 即由服务端新建并返回 id（「新建会话」在客户端是一个空态，落库发生在第一条消息）。
 * <p>
 * 请求一律不含 {@code userId}：身份从 JWT 解析、经 {@code UserContext} 传递，租户过滤由数据访问层强制注入。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 助手消息里的一个工具步骤（对应 {@code AgentRunner.Step} 的云端视图） */
    public record MessageStep(int iteration, String kind, String name, long durationMs,
                              String status, String summary) {
    }

    public record MessageView(String id, String role, String content,
                              List<MessageStep> steps, String traceId, LocalDateTime createdAt) {
    }

    public record ConversationView(String id, String title, LocalDateTime updatedAt) {

        static ConversationView from(ConversationEntity entity) {
            return new ConversationView(entity.getId(), entity.getTitle(), entity.getUpdatedAt());
        }
    }

    public record ConversationDetail(String id, String title, LocalDateTime createdAt,
                                     LocalDateTime updatedAt, List<MessageView> messages) {
    }

    /** 历史会话列表，按最近活跃倒序 */
    @GetMapping
    public ApiResponse<List<ConversationView>> list(
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return ApiResponse.ok(conversationService.list(limit).stream()
                .map(ConversationView::from).toList());
    }

    /** 会话详情（含按序消息）；不存在与跨租户对外不可区分（均为 404） */
    @GetMapping("/{id}")
    public ApiResponse<ConversationDetail> get(@PathVariable String id) {
        ConversationEntity conversation = conversationService.get(id);
        List<MessageView> messages = conversationService.messages(id).stream()
                .map(this::toView).toList();
        return ApiResponse.ok(new ConversationDetail(conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), messages));
    }

    /** 删除会话及其消息（数据主权：用户可清掉自己的对话） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        conversationService.delete(id);
        return ApiResponse.ok(null);
    }

    private MessageView toView(ConversationMessageEntity message) {
        return new MessageView(message.getId(), message.getRole(), message.getContent(),
                parseSteps(message.getStepsJson()), message.getTraceId(), message.getCreatedAt());
    }

    /**
     * 步骤摘要解析。解析失败返回空列表而不是让查询失败——与
     * {@code AgentController.candidatesOf} 同一取舍：展示接口的职责是「如实展示当前状态」，
     * 结构漂移不该让整个会话读不出来。
     */
    private List<MessageStep> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stepsJson, new TypeReference<List<MessageStep>>() {
            });
        } catch (Exception e) {
            log.warn("工具步骤摘要解析失败，返回空列表", e);
            return List.of();
        }
    }
}

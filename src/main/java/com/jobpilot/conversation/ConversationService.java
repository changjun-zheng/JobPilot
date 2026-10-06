package com.jobpilot.conversation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import com.jobpilot.domain.MessageRole;
import com.jobpilot.mapper.ConversationMapper;
import com.jobpilot.mapper.ConversationMessageMapper;
import com.jobpilot.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息的持久化（PRD-FP-2.1）。<b>只做表操作，不含编排</b>——「加载历史 → 跑 run → 落消息」的
 * 编排在 {@code AgentChatService}，本类也刻意不依赖 {@code ai} 的协议类型。
 *
 * <h3>事务边界</h3>
 * 每个写方法各自一个事务。**调用方（AgentChatService）不加事务**——它要跑一个 30s+ 的 LLM 调用，
 * 把它包进事务会长时间占用连接，并破坏 {@code AgentTraceRecorder} 的 {@code REQUIRES_NEW} 语义。
 * 也因此，「会话创建 + 用户消息写入」在 {@code run} 之前就已提交，run 内的工具（审批草稿）不会挂空。
 *
 * <h3>租户隔离</h3>
 * 每张表都带 {@code user_id}，读写由拦截器注入过滤；写入时 {@code setUserId} 显式设置
 * （拦截器对已带 user_id 的插入是跳过、不覆盖）。{@code create} 里再与 {@code UserContext} 比对一次，
 * 防未来新增调用方绕过 Controller 注入别的租户。
 */
@Service
public class ConversationService {

    /** 历史列表一次返回上限；无分页拦截器，用 LIMIT 钳制（同 ApplicationService.query） */
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    /** title 截断长度（码点） */
    private static final int TITLE_MAX_CODEPOINTS = 30;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    // ── 写 ──────────────────────────────────────────────────────

    /** 新建会话；title 由首条用户消息派生（PRD 契约未定义该字段，本实现补齐）。 */
    @Transactional
    public ConversationEntity create(String userId, String firstMessage) {
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(userId)) {
            throw new com.jobpilot.common.UnauthorizedException("租户上下文与会话归属不一致");
        }
        ConversationEntity conversation = new ConversationEntity();
        // 租户键必须在这里显式设置：拦截器不会覆盖实体已带的 user_id（ignoreInsert 会跳过）
        conversation.setUserId(userId);
        conversation.setTitle(deriveTitle(firstMessage));
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** 追加一条用户消息 */
    @Transactional
    public void appendUserMessage(String userId, String conversationId, String content) {
        insertMessage(userId, conversationId, MessageRole.USER, content, null, null);
    }

    /**
     * 追加一条助手消息。
     *
     * @param stepsJson 工具步骤摘要（JSON 数组）；序列化由调用方负责——本类不依赖 runner 的 Step 类型
     */
    @Transactional
    public void appendAssistantMessage(String userId, String conversationId, String content,
                                       String stepsJson, String traceId) {
        insertMessage(userId, conversationId, MessageRole.ASSISTANT, content, stepsJson, traceId);
    }

    /** 删除会话及其全部消息；先确认存在（无外键时跨租户/不存在会静默 0 行并谎报成功）。 */
    @Transactional
    public void delete(String id) {
        get(id); // 404 语义（含跨租户）先行
        messageMapper.delete(new QueryWrapper<ConversationMessageEntity>().eq("conversation_id", id));
        conversationMapper.deleteById(id);
    }

    private void insertMessage(String userId, String conversationId, MessageRole role,
                               String content, String stepsJson, String traceId) {
        ConversationMessageEntity message = new ConversationMessageEntity();
        message.setUserId(userId);
        message.setConversationId(conversationId);
        message.setRole(role.name());
        message.setContent(content);
        message.setStepsJson(stepsJson);
        message.setTraceId(traceId);
        messageMapper.insert(message);
        // 触碰会话活跃时间：消息 insert 不会更新 conversation 行，列表按 updated_at 倒序取不到变化
        conversationMapper.update(null, new UpdateWrapper<ConversationEntity>()
                .eq("id", conversationId)
                .set("updated_at", LocalDateTime.now()));
    }

    // ── 读 ──────────────────────────────────────────────────────

    /** 会话详情；不存在与跨租户对外不可区分（均为 404）。 */
    public ConversationEntity get(String id) {
        ConversationEntity conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "会话不存在：" + id);
        }
        return conversation;
    }

    /** 一个会话的全部消息，按 (created_at, id) 升序 */
    public List<ConversationMessageEntity> messages(String conversationId) {
        QueryWrapper<ConversationMessageEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", conversationId)
                .orderByAsc("created_at").orderByAsc("id");
        return messageMapper.selectList(wrapper);
    }

    /**
     * 会话最近 {@code limit} 条消息，**按时间升序**返回（供跨 run 上下文回填）。
     * <p>
     * 先按倒序取最近 N 条再反转：升序 + LIMIT 拿的是最老 N 条，正好相反。
     */
    public List<ConversationMessageEntity> loadRecentMessages(String conversationId, int limit) {
        QueryWrapper<ConversationMessageEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", conversationId)
                .orderByDesc("created_at").orderByDesc("id")
                .last("LIMIT " + clampLimit(limit));
        List<ConversationMessageEntity> recent = new ArrayList<>(messageMapper.selectList(wrapper));
        Collections.reverse(recent);
        return recent;
    }

    /** 当前租户的会话列表，按最近活跃倒序 */
    public List<ConversationEntity> list(int limit) {
        QueryWrapper<ConversationEntity> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("updated_at").last("LIMIT " + clampListLimit(limit));
        return conversationMapper.selectList(wrapper);
    }

    // ── 辅助 ────────────────────────────────────────────────────

    /**
     * 由首条用户消息派生 title：折叠空白、按码点截断。
     * <p>
     * 按码点截断而非 {@code substring(0,30)}——否则正好切进代理对会把一个字符劈成两半，
     * 存进去就是乱码（与 {@code ChunkSplitter} 同一条纪律）。
     */
    private String deriveTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "新会话";
        }
        String collapsed = firstMessage.strip().replaceAll("\\s+", " ");
        int codePoints = collapsed.codePointCount(0, collapsed.length());
        if (codePoints <= TITLE_MAX_CODEPOINTS) {
            return collapsed;
        }
        int end = collapsed.offsetByCodePoints(0, TITLE_MAX_CODEPOINTS);
        return collapsed.substring(0, end);
    }

    private int clampListLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    /** 上下文回填的条数无需给到 200 上限，直接夹到一个较小的硬顶 */
    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }
}

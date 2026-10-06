package com.jobpilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.conversation.ConversationService;
import com.jobpilot.domain.ConversationEntity;
import com.jobpilot.domain.ConversationMessageEntity;
import com.jobpilot.domain.MessageRole;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 对话的编排层：把「会话持久化」与「ReAct 循环」缝在一起。
 *
 * <h3>为什么需要这一层</h3>
 * ARCHITECTURE §4.3 定死了「AgentRunner 只负责循环和预算，不知道表结构」。跨 run 上下文与落消息
 * 都需要表，所以放在 runner 之外——Controller 调本类，本类调 {@code AgentRunner} 与
 * {@code ConversationService}。
 *
 * <h3>为什么本类不加 {@code @Transactional}</h3>
 * 中间那步是一次 30s+ 的 LLM 调用。包进事务会长时间占用连接，并且会让 run 内
 * {@code AgentTraceRecorder} 的 {@code REQUIRES_NEW} 提交进外层事务、回滚时一起消失。
 * 因此：会话创建与用户消息在 {@code run} **之前各自提交**，run 内产生的审批草稿才挂得住；
 * 助手消息在 {@code run} 之后单独提交。
 *
 * <h3>失败策略（有意不对称）</h3>
 * <ul>
 *   <li>会话创建 / 用户消息写入：<b>fail-loud</b>——它们是「这一轮对话」的入口，失败应让用户知道；</li>
 *   <li>助手消息写入：<b>best-effort</b>——模型已经答完，不该因一条记账式插入失败而丢掉答案，
 *       失败只告警，该 run 仍可在 {@code agent_trace} 里回放。</li>
 * </ul>
 * <b>{@code ERROR} 终态不写助手消息</b>：模型调用失败时那句「请稍后重试」是罐头话，不该当作助手的
 * 真实回答落库——该会话会因此出现两条连续 USER 消息（用户重试），这是有意为之。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /**
     * 回填给模型的最近历史条数上限。
     * <p>
     * 只取最近 N 条：上下文越长越贵，且旧轮次对当前问题多半无用。用「最近」而非「最早」——
     * 与 {@code AgentRunner.compactIfTooLong} 的取舍一致（先丢旧轮次）。
     */
    private static final int PRIOR_MESSAGE_LIMIT = 24;

    private final AgentRunner agentRunner;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentChatService(AgentRunner agentRunner, ConversationService conversationService) {
        this.agentRunner = agentRunner;
        this.conversationService = conversationService;
    }

    /**
     * 跑一轮对话。
     *
     * @param conversationId 首轮为空（服务端新建）；之后必须传回服务端给的 id，否则 404
     */
    public AgentRunner.RunResult chat(String conversationId, String message) {
        String userId = UserContext.require();

        ConversationEntity conversation = conversationId == null || conversationId.isBlank()
                ? conversationService.create(userId, message)
                : conversationService.get(conversationId);

        List<AgentMessage> prior = toPriorMessages(
                conversationService.loadRecentMessages(conversation.getId(), PRIOR_MESSAGE_LIMIT));

        // 先落用户消息（独立提交）再 run：run 内的工具/审批草稿都按这个 conversationId 挂载
        conversationService.appendUserMessage(userId, conversation.getId(), message);

        AgentRunner.RunResult result =
                agentRunner.run(new AgentRunner.RunRequest(conversation.getId(), message, prior));

        if (result.finishReason() != FinishReason.ERROR) {
            try {
                conversationService.appendAssistantMessage(userId, conversation.getId(),
                        result.answer(), stepsJson(result.steps()), result.traceId());
            } catch (Exception e) {
                log.warn("助手消息落库失败（答案已返回，不影响本轮）conversationId={} traceId={}",
                        conversation.getId(), result.traceId(), e);
            }
        }
        return result;
    }

    /**
     * 持久化消息 → 协议消息。
     * <p>
     * 助手消息的 {@code toolCalls} 恒为空：历史里只回填文本。工具调用是「过程」，模型续接上下文
     * 只需要「结论」（上一轮说了什么），不需要重放当时的工具请求。
     */
    private List<AgentMessage> toPriorMessages(List<ConversationMessageEntity> messages) {
        List<AgentMessage> prior = new ArrayList<>(messages.size());
        for (ConversationMessageEntity message : messages) {
            switch (MessageRole.parse(message.getRole())) {
                case USER -> prior.add(new AgentMessage.User(message.getContent()));
                case ASSISTANT -> prior.add(new AgentMessage.Assistant(message.getContent(), List.of()));
            }
        }
        return prior;
    }

    /** 步骤摘要序列化；失败返回 null（助手消息照常落，只是没有步骤视图）。 */
    private String stepsJson(List<AgentRunner.Step> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            log.warn("工具步骤摘要序列化失败，助手消息将不带步骤", e);
            return null;
        }
    }
}

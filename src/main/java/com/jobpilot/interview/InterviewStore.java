package com.jobpilot.interview;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.InterviewMessageEntity;
import com.jobpilot.domain.InterviewSessionCompanyEntity;
import com.jobpilot.domain.InterviewSessionEntity;
import com.jobpilot.mapper.InterviewMessageMapper;
import com.jobpilot.mapper.InterviewSessionCompanyMapper;
import com.jobpilot.mapper.InterviewSessionMapper;
import com.jobpilot.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试会话与消息的持久化。<b>只做表操作，不含编排</b>——裁决与 LLM 调用在 {@link InterviewService}。
 * <p>
 * 拆成独立 bean 是为了让写方法能各自带上事务：编排层要在<b>事务之外</b>调 LLM（否则同一连接被长时间占用），
 * 而「追加候选人消息 + 面试官消息 + 推进进度」这几笔要在一个事务里原子完成。
 * 同一个 bean 内自调用 {@code @Transactional} 方法不会生效，所以必须分开——与
 * {@code AgentChatService} / {@code ConversationService} 是同一分法。
 *
 * <h3>租户隔离</h3>
 * 两表都带 {@code user_id}，读写由拦截器注入过滤；写入时 {@code setUserId} 显式设置
 * （拦截器对已带 user_id 的插入是跳过、不覆盖）。{@link #create} 再与 {@code UserContext} 比对一次。
 */
@Service
public class InterviewStore {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final InterviewSessionCompanyMapper sessionCompanyMapper;

    public InterviewStore(InterviewSessionMapper sessionMapper, InterviewMessageMapper messageMapper,
                          InterviewSessionCompanyMapper sessionCompanyMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.sessionCompanyMapper = sessionCompanyMapper;
    }

    /** 创建会话时选中的平台公司（创建时快照 ID / 名称 / 档位） */
    public record SelectedCompany(String companyId, String companyName, String tier) {
    }

    // ── 写 ──────────────────────────────────────────────────────

    /**
     * 新建面试会话（状态 IN_PROGRESS、第 1 轮、题数 0），并落「关联了哪几家平台公司」的关联行。
     * 首题由 {@link #record} 追加；关联行与主行同一个事务。
     */
    @Transactional
    public InterviewSessionEntity create(String userId, String company, String position, String tier,
                                         String difficultyOverride, String resolvedDifficulty, int totalRounds,
                                         List<SelectedCompany> companies) {
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(userId)) {
            throw new com.jobpilot.common.UnauthorizedException("租户上下文与会话归属不一致");
        }
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setUserId(userId);
        session.setCompany(company);
        session.setPosition(position);
        session.setTier(tier);
        session.setDifficultyOverride(difficultyOverride);
        session.setResolvedDifficulty(resolvedDifficulty);
        session.setCurrentPhase(InterviewStateMachine.phaseFor(1).name());
        session.setCurrentRound(1);
        session.setTotalRounds(totalRounds);
        session.setQuestionsInRound(0);
        session.setStatus(InterviewStatus.IN_PROGRESS.name());
        sessionMapper.insert(session);

        if (companies != null) {
            for (SelectedCompany c : companies) {
                InterviewSessionCompanyEntity link = new InterviewSessionCompanyEntity();
                link.setUserId(userId);
                link.setSessionId(session.getId());
                link.setCompanyId(c.companyId());
                link.setCompanyName(c.companyName());
                link.setTier(c.tier());
                sessionCompanyMapper.insert(link);
            }
        }
        return session;
    }

    /**
     * 落一笔交流并推进进度（原子）：
     * 追加候选人消息（{@code candidateText} 为 null 时跳过，用于面试开始的第一题）+ 面试官消息，
     * 再把会话推进到 {@code nextPhase / nextRound / nextQuestionsInRound}。
     * 候选人消息属**旧**阶段，面试官消息属**新**阶段——追问跨轮时也对得上。
     */
    @Transactional
    public void record(String userId, InterviewSessionEntity session, String candidateText,
                       String interviewerText, InterviewPhase nextPhase, int nextRound, int nextQuestionsInRound) {
        int seq = currentMessageCount(session.getId());
        if (candidateText != null) {
            insertMessage(userId, session.getId(), InterviewRole.CANDIDATE,
                    session.getCurrentPhase(), session.getCurrentRound(), ++seq, candidateText);
        }
        insertMessage(userId, session.getId(), InterviewRole.INTERVIEWER,
                nextPhase.name(), nextRound, ++seq, interviewerText);

        sessionMapper.update(null, new UpdateWrapper<InterviewSessionEntity>()
                .eq("id", session.getId())
                .set("current_phase", nextPhase.name())
                .set("current_round", nextRound)
                .set("questions_in_round", nextQuestionsInRound)
                .set("updated_at", LocalDateTime.now()));
    }

    /** 只追加一条候选人消息（「答完最后一题、直接收尾」的场景），并触碰会话活跃时间 */
    @Transactional
    public void appendCandidateAnswer(String userId, InterviewSessionEntity session, String content) {
        insertMessage(userId, session.getId(), InterviewRole.CANDIDATE,
                session.getCurrentPhase(), session.getCurrentRound(),
                currentMessageCount(session.getId()) + 1, content);
        sessionMapper.update(null, new UpdateWrapper<InterviewSessionEntity>()
                .eq("id", session.getId())
                .set("updated_at", LocalDateTime.now()));
    }

    /** 收尾落库：报告 + 弱点草稿 ID + 终态 */
    @Transactional
    public void complete(InterviewSessionEntity session, String reportJson, String draftId, InterviewStatus status) {
        sessionMapper.update(null, new UpdateWrapper<InterviewSessionEntity>()
                .eq("id", session.getId())
                .set("report_json", reportJson)
                .set("draft_id", draftId)
                .set("status", status.name())
                .set("updated_at", LocalDateTime.now()));
    }

    private void insertMessage(String userId, String sessionId, InterviewRole role,
                               String phase, int roundNo, int seq, String content) {
        InterviewMessageEntity message = new InterviewMessageEntity();
        message.setUserId(userId);
        message.setSessionId(sessionId);
        message.setRole(role.name());
        message.setPhase(phase);
        message.setRoundNo(roundNo);
        message.setSeq(seq);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private int currentMessageCount(String sessionId) {
        Long count = messageMapper.selectCount(
                new QueryWrapper<InterviewMessageEntity>().eq("session_id", sessionId));
        return count == null ? 0 : count.intValue();
    }

    // ── 读 ──────────────────────────────────────────────────────

    /** 会话详情；不存在与跨租户对外不可区分（均为 404） */
    public InterviewSessionEntity require(String sessionId) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "面试会话不存在：" + sessionId);
        }
        return session;
    }

    /** 一个会话的全部消息，按 seq 升序 */
    public List<InterviewMessageEntity> messages(String sessionId) {
        return messageMapper.selectList(new QueryWrapper<InterviewMessageEntity>()
                .eq("session_id", sessionId)
                .orderByAsc("seq"));
    }

    /** 当前租户的面试列表，按最近活跃倒序 */
    public List<InterviewSessionEntity> list(int limit) {
        int clamped = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return sessionMapper.selectList(new QueryWrapper<InterviewSessionEntity>()
                .orderByDesc("updated_at")
                .last("LIMIT " + clamped));
    }
}

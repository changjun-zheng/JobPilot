package com.jobpilot.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.ai.AgentMessage;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.ChatRequest;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.config.InterviewProperties;
import com.jobpilot.domain.InterviewMessageEntity;
import com.jobpilot.domain.InterviewSessionEntity;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.memory.MemoryService;
import com.jobpilot.platform.PlatformCatalogService;
import com.jobpilot.security.UserContext;
import com.jobpilot.usage.UsageScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模拟面试编排（BRD US-3）。
 *
 * <h3>为什么直接调 {@link ChatPort}，不走 {@code AgentRunner}</h3>
 * 三条都是结构性的，不是偏好：
 * <ul>
 *   <li>{@code AgentRunner.SYSTEM_PROMPT} 是私有常量、无 persona 槽位，且其第 1 条规则强制「必须先调
 *       knowledge_search」——与「面试官按阶段提问」直接冲突；</li>
 *   <li>runner 的预算上限与 HITL 短路都按「单轮」设计，而面试要跨调用走完三轮弧线；</li>
 *   <li><b>阶段推进必须由服务端裁决</b>（{@link InterviewStateMachine}），不能交给模型——见该类注释。</li>
 * </ul>
 * 证据检索也放在服务端（{@link KnowledgeRetrievalService}），不给模型工具：更确定、更省一次工具往返。
 *
 * <h3>事务与失败顺序</h3>
 * 本类**非事务**，LLM 调用在事务之外。收尾时<b>先建草稿、后写会话</b>：草稿的幂等键含 sessionId（见下），
 * 所以中途失败重试会拿回同一个草稿，不会留下「REPORT_PENDING 但 draftId 为空」的坏状态。
 *
 * <h3>把 sessionId 当 traceId / conversationId 传给草稿</h3>
 * 审批草稿的幂等键是 {@code sha256(traceId|toolName|conversationId|payload)}。面试没有 agent trace，
 * 用 sessionId 顶上——同一会话收尾重试得到同一个键，草稿幂等成立（`conversation_id` 只是关联字符串列，无外键）。
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 回填给模型的最近消息条数上限 */
    private static final int HISTORY_LIMIT = 40;
    /** 每类证据取几条 */
    private static final int EVIDENCE_TOP_K = 4;
    /** 面经类型：与 {@code DocumentIngestService.validate} 的白名单一致 */
    private static final String INTERVIEW_DOC_TYPE = "INTERVIEW";

    private final InterviewStore store;
    private final InterviewProperties props;
    private final PlatformCatalogService catalogService;
    private final ChatPort chatPort;
    private final KnowledgeRetrievalService retrievalService;
    private final MemoryService memoryService;
    private final ApprovalDraftService draftService;

    public InterviewService(InterviewStore store, InterviewProperties props, PlatformCatalogService catalogService,
                            ChatPort chatPort, KnowledgeRetrievalService retrievalService, MemoryService memoryService,
                            ApprovalDraftService draftService) {
        this.store = store;
        this.props = props;
        this.catalogService = catalogService;
        this.chatPort = chatPort;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.draftService = draftService;
    }

    // ── 对外结果 ────────────────────────────────────────────────

    public record StartCommand(String userId, List<String> companyIds, String position, String difficultyOverride) {
    }

    /** 一次开题/追问的结果；{@code finished} 为 true 时 {@code question} 为 null，前端应去取报告 */
    public record TurnResult(String sessionId, String phase, int round, int totalRounds,
                             String status, String question, boolean finished) {
    }

    public record InterviewReport(String summary, List<Dimension> dimensions, List<Weakness> weaknesses) {

        public record Dimension(String name, String comment, Integer score) {
        }

        public record Weakness(String content, Double confidence, String note) {
        }
    }

    public record ReportResult(String sessionId, InterviewReport report, String draftId,
                               List<String> candidateIds, String status) {
    }

    public record MessageView(String role, String phase, int round, String content) {
    }

    public record DetailView(String sessionId, String company, String position, String tier,
                             String difficulty, String phase, int round, int totalRounds, String status,
                             List<MessageView> messages) {
    }

    public record SessionSummary(String sessionId, String company, String position, String tier,
                                 String phase, int round, int totalRounds, String status) {

        static SessionSummary of(InterviewSessionEntity s) {
            return new SessionSummary(s.getId(), s.getCompany(), s.getPosition(), s.getTier(),
                    s.getCurrentPhase(), s.getCurrentRound(), s.getTotalRounds(), s.getStatus());
        }
    }

    // ── 开始 ────────────────────────────────────────────────────

    /**
     * 开一场面试。目标公司来自**平台目录**（{@code companyIds}，可多家）——不接自由文本：
     * 产品定位是「管理员维护公司库，用户从中挑选」。
     * <p>
     * <b>多家 = 混合成一套题</b>（设计草案 v3 §4）：把选中公司的面经并进同一次检索、难度取其中
     * <b>最高</b>档，出**一套综合题 + 一份报告**——不逐家分轮。会话上存顿号连接的展示快照，
     * 关联行存「这次关联了哪几家」。
     */
    public TurnResult start(StartCommand command) {
        // 身份由 Controller 从 UserContext 派生；命令带 userId 只为用例可脱离 ThreadLocal 测试，
        // store.create 会再与上下文比对一次（同 DocumentIngestService.enqueue 的约定）
        String userId = requireText(command.userId(), "userId");
        List<String> companyIds = distinct(command.companyIds());

        List<PlatformCompanyEntity> companies = catalogService.activeCompaniesByIds(companyIds);
        if (companies.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "请至少选择一家在架的公司");
        }
        if (companies.size() != companyIds.size()) {
            // 有 ID 未命中（不存在 / 已下架）——拒绝而非静默丢弃，免得用户以为面的是选中的公司
            throw new ApiException(ErrorCode.BAD_REQUEST, "存在无效或已下架的公司，请刷新后重选");
        }

        String position = blankToNull(command.position());
        String display = companies.stream().map(PlatformCompanyEntity::getName).reduce((a, b) -> a + "、" + b)
                .orElseThrow();
        String tier = resolveHardestTier(companies);
        InterviewProperties.InterviewTier tierCfg = props.tier(tier); // 档位未知 → 400

        String overrideRaw = blankToNull(command.difficultyOverride());
        Difficulty resolved = overrideRaw == null
                ? tierCfg.difficulty()
                : Difficulty.parse(overrideRaw); // 未知难度 → 400

        List<InterviewStore.SelectedCompany> selected = companies.stream()
                .map(c -> new InterviewStore.SelectedCompany(c.getId(), c.getName(), c.getTier()))
                .toList();

        InterviewSessionEntity session = store.create(userId, display, position, tier,
                overrideRaw == null ? null : resolved.name(), resolved.name(), props.totalRounds(), selected);

        String question = askQuestion(userId, session, List.of(), null,
                InterviewPhase.BASIC, tierCfg, resolved);
        store.record(userId, session, null, question, InterviewPhase.BASIC, 1, 1);

        return new TurnResult(session.getId(), InterviewPhase.BASIC.name(), 1, props.totalRounds(),
                InterviewStatus.IN_PROGRESS.name(), question, false);
    }

    /**
     * 综合档位 = 选中公司档位中**难度最高**的那个（设计草案 v3 §7：多家混合取最高档）。
     * 档位配置缺失/未知时由 {@link InterviewProperties#tier} 抛出 → 400。
     */
    private String resolveHardestTier(List<PlatformCompanyEntity> companies) {
        return companies.stream()
                .map(PlatformCompanyEntity::getTier)
                .max(Comparator.comparingInt(t -> props.tier(t).difficulty().rank()))
                .orElse(props.resolvedDefaultTier());
    }

    /** 去重且保持顺序（用户可能重复勾选，ID 集合去重后与命中条数比较才有意义） */
    private List<String> distinct(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && !id.isBlank()).map(String::strip).distinct().toList();
    }

    // ── 作答 ────────────────────────────────────────────────────

    public TurnResult answer(String sessionId, String answerText) {
        String userId = UserContext.require();
        String answer = requireText(answerText, "回答");
        InterviewSessionEntity session = store.require(sessionId);
        requireInProgress(session);

        List<InterviewMessageEntity> history = store.messages(sessionId);
        var decision = InterviewStateMachine.afterAnswer(
                new InterviewStateMachine.State(session.getCurrentRound(), session.getQuestionsInRound()),
                props.questionsPerRound());

        if (decision.decision() == InterviewStateMachine.Decision.FINISH) {
            // 候选人答完了最后一题：先落这句，再收尾出报告
            store.appendCandidateAnswer(userId, session, answer);
            ReportResult report = finishWithReport(userId, store.require(sessionId));
            return new TurnResult(sessionId, session.getCurrentPhase(), session.getCurrentRound(),
                    session.getTotalRounds(), report.status(), null, true);
        }

        int nextRound = decision.next().round();
        InterviewPhase nextPhase = InterviewStateMachine.phaseFor(nextRound);
        InterviewProperties.InterviewTier tierCfg = props.tier(session.getTier());
        Difficulty difficulty = Difficulty.parse(session.getResolvedDifficulty());

        String question = askQuestion(userId, session, history, answer, nextPhase, tierCfg, difficulty);
        store.record(userId, session, answer, question, nextPhase, nextRound,
                decision.next().questionsInRound());

        return new TurnResult(sessionId, nextPhase.name(), nextRound, session.getTotalRounds(),
                InterviewStatus.IN_PROGRESS.name(), question, false);
    }

    // ── 收尾 / 报告 ─────────────────────────────────────────────

    /** 提前收尾：按已有问答直接出报告（已收尾则幂等返回） */
    public ReportResult finish(String sessionId) {
        String userId = UserContext.require();
        InterviewSessionEntity session = store.require(sessionId);
        if (!InterviewStatus.IN_PROGRESS.name().equals(session.getStatus())) {
            return existingReport(session);
        }
        return finishWithReport(userId, session);
    }

    /** 取报告；未收尾则 404（不是错误，是「还没有」） */
    public ReportResult report(String sessionId) {
        InterviewSessionEntity session = store.require(sessionId);
        if (session.getReportJson() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "面试尚未结束，暂无报告");
        }
        return existingReport(session);
    }

    public DetailView detail(String sessionId) {
        InterviewSessionEntity session = store.require(sessionId);
        List<MessageView> messages = store.messages(sessionId).stream()
                .map(m -> new MessageView(m.getRole(), m.getPhase(), m.getRoundNo(), m.getContent()))
                .toList();
        return new DetailView(session.getId(), session.getCompany(), session.getPosition(), session.getTier(),
                session.getResolvedDifficulty(), session.getCurrentPhase(), session.getCurrentRound(),
                session.getTotalRounds(), session.getStatus(), messages);
    }

    public List<SessionSummary> list(int limit) {
        return store.list(limit).stream().map(SessionSummary::of).toList();
    }

    private ReportResult finishWithReport(String userId, InterviewSessionEntity session) {
        List<InterviewMessageEntity> transcript = store.messages(session.getId());
        String raw = generateReport(session, transcript); // LLM，在事务之外
        InterviewReport report = parseReport(raw);

        List<InterviewReport.Weakness> weaknesses =
                report.weaknesses() == null ? List.of() : report.weaknesses();

        String reportJson = toJson(report);
        String draftId = null;
        List<String> candidateIds = List.of();
        InterviewStatus status = InterviewStatus.COMPLETED;

        if (!weaknesses.isEmpty()) {
            String payloadJson = buildCandidatePayload(session, weaknesses);
            // 与审批侧同一套校验规则先验一遍：类型未知/超长/超条数在这里就被挡下
            memoryService.parseBatch(payloadJson);
            // 先建草稿、后写会话：草稿幂等键含 sessionId，重试拿回同一个，不会留坏状态
            draftId = draftService.createDraft(userId, session.getId(), session.getId(),
                    "memory_candidate_create", payloadJson);
            candidateIds = candidateIdsFor(weaknesses.size());
            status = InterviewStatus.REPORT_PENDING;
        }

        store.complete(session, reportJson, draftId, status);
        return new ReportResult(session.getId(), report, draftId, candidateIds, status.name());
    }

    private ReportResult existingReport(InterviewSessionEntity session) {
        InterviewReport report = parseReportJson(session.getReportJson());
        List<InterviewReport.Weakness> weaknesses =
                report.weaknesses() == null ? List.of() : report.weaknesses();
        List<String> ids = session.getDraftId() == null ? List.of() : candidateIdsFor(weaknesses.size());
        return new ReportResult(session.getId(), report, session.getDraftId(), ids, session.getStatus());
    }

    // ── LLM 调用 ────────────────────────────────────────────────

    /** 生成下一题（服务端已定好阶段）。{@code candidateAnswer} 为 null 表示面试开始的第一题 */
    private String askQuestion(String userId, InterviewSessionEntity session,
                               List<InterviewMessageEntity> history, String candidateAnswer,
                               InterviewPhase phase, InterviewProperties.InterviewTier tierCfg,
                               Difficulty difficulty) {
        // 目标公司（本会话关联的平台公司）——检索面经时按它硬收窄，别的公司的平台内容不进来
        List<String> companyIds = store.companyIds(session.getId());
        Evidence evidence = retrieveEvidence(userId, session.getCompany(), session.getPosition(), companyIds);
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage.System(InterviewPrompts.interviewerSystem(
                session.getCompany(), session.getPosition(), phase, difficulty,
                tierCfg.followupStyle(), tierCfg.pressure(), evidence.block())));
        messages.addAll(mapHistory(history));
        messages.add(new AgentMessage.User(candidateAnswer == null
                ? "请开始面试，提出第一道题（基础题）。"
                : candidateAnswer));
        String question = complete(messages, "面试官未能产出问题，请稍后重试");
        // 无材料时模型仍可能带 [n] 悬空引用——服务端清掉：引用的正确性由服务端保证，模型只出措辞
        return evidence.hasMaterials() ? question : stripTrailingCitations(question);
    }

    private String generateReport(InterviewSessionEntity session, List<InterviewMessageEntity> transcript) {
        StringBuilder qa = new StringBuilder();
        for (InterviewMessageEntity m : transcript) {
            qa.append(InterviewRole.INTERVIEWER.name().equals(m.getRole()) ? "面试官：" : "候选人：")
                    .append(m.getContent()).append('\n');
        }
        List<AgentMessage> messages = List.of(
                new AgentMessage.System(InterviewPrompts.reportSystem()),
                new AgentMessage.User("公司：" + session.getCompany()
                        + "；岗位：" + nullToDash(session.getPosition())
                        + "；难度：" + session.getResolvedDifficulty() + "\n\n【面试记录】\n" + qa));
        return complete(messages, "评估报告生成失败，请稍后重试");
    }

    private String complete(List<AgentMessage> messages, String failureMessage) {
        ChatCompletion completion = chatPort.chat(
                new ChatRequest(messages, List.of(), null, null, null, null));
        String text = completion.content() == null ? "" : completion.content().strip();
        if (text.isEmpty()) {
            // 服务端/模型侧失败 → 500（交由 GlobalExceptionHandler 兜底），不是客户端错误
            throw new IllegalStateException(failureMessage);
        }
        return text;
    }

    private List<AgentMessage> mapHistory(List<InterviewMessageEntity> history) {
        int from = Math.max(0, history.size() - HISTORY_LIMIT);
        List<AgentMessage> mapped = new ArrayList<>();
        for (InterviewMessageEntity m : history.subList(from, history.size())) {
            if (InterviewRole.INTERVIEWER.name().equals(m.getRole())) {
                mapped.add(new AgentMessage.Assistant(m.getContent(), List.of()));
            } else {
                mapped.add(new AgentMessage.User(m.getContent()));
            }
        }
        return mapped;
    }

    // ── 证据检索 ────────────────────────────────────────────────

    /** 检索到的材料块 + 是否真有材料（无材料时服务端要清掉模型可能带的悬空引用编号） */
    private record Evidence(String block, boolean hasMaterials) {
    }

    private Evidence retrieveEvidence(String userId, String company, String position, List<String> companyIds) {
        String pos = position == null ? "" : position;
        List<RetrievedChunk> resume = safeSearch(userId, (pos.isBlank() ? company : pos) + " 项目 经历 技术栈",
                null, companyIds);
        List<RetrievedChunk> mianjing = safeSearch(userId, (company + " " + pos).strip(),
                INTERVIEW_DOC_TYPE, companyIds);

        if (resume.isEmpty() && mianjing.isEmpty()) {
            return new Evidence(
                    "（知识库中未检索到相关材料。请基于通用常见问题提问，不要编造候选人的经历。）", false);
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        if (!resume.isEmpty()) {
            sb.append("【简历 / 项目材料】\n");
            for (RetrievedChunk c : resume) {
                sb.append('[').append(n++).append("] [").append(c.citation().display()).append("] ")
                        .append(c.text().strip()).append('\n');
            }
        }
        if (!mianjing.isEmpty()) {
            sb.append("【面经材料（目标公司相关）】\n");
            for (RetrievedChunk c : mianjing) {
                sb.append('[').append(n++).append("] [").append(c.citation().display()).append("] ")
                        .append(c.text().strip()).append('\n');
            }
        }
        return new Evidence(sb.toString(), true);
    }

    /** 去掉末尾的悬空引用编号（如「… 应用场景？ [1]」→「… 应用场景？」） */
    private String stripTrailingCitations(String text) {
        return text.replaceAll("\\s*\\[\\d+\\]\\s*$", "").strip();
    }

    /**
     * 检索失败不能让整场面试崩——降级为「无材料」，返回空。
     * <p>
     * {@code companyIds} 非空 → 平台内容硬收窄到这些公司（本轮面试的目标公司）。
     */
    private List<RetrievedChunk> safeSearch(String userId, String query, String docType, List<String> companyIds) {
        try {
            return retrievalService.search(
                    new RetrievalQuery(userId, query, EVIDENCE_TOP_K, docType, companyIds),
                    UsageScenario.AGENT).items();
        } catch (Exception e) {
            log.warn("面试证据检索失败（降级为无材料）docType={} query={}", docType, query, e);
            return List.of();
        }
    }

    // ── 报告解析 / 弱点载荷 ─────────────────────────────────────

    private InterviewReport parseReport(String raw) {
        String json = extractJsonObject(raw);
        try {
            JsonNode node = MAPPER.readTree(json);
            InterviewReport report = MAPPER.treeToValue(node, InterviewReport.class);
            // 模型偶尔漏字段，补成非空以免下游 NPE
            return new InterviewReport(
                    report.summary() == null ? raw.strip() : report.summary(),
                    report.dimensions() == null ? List.of() : report.dimensions(),
                    report.weaknesses() == null ? List.of() : report.weaknesses());
        } catch (Exception e) {
            log.warn("面试报告不是合法 JSON，降级为纯文本摘要", e);
            return new InterviewReport(raw.strip(), List.of(), List.of());
        }
    }

    private InterviewReport parseReportJson(String reportJson) {
        try {
            return MAPPER.readValue(reportJson, InterviewReport.class);
        } catch (Exception e) {
            return new InterviewReport(reportJson, List.of(), List.of());
        }
    }

    /** 容忍 ```json 围栏与前后杂字 */
    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private String toJson(InterviewReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("序列化面试报告失败", e);
        }
    }

    /**
     * 把弱点拼成 {@code memory_candidate_create} 的载荷（与 {@code MemoryCandidateCreateTool} 同形）。
     * <p>
     * 用 {@link LinkedHashMap}：草稿幂等键含 payloadJson，{@code Map.of} 的迭代顺序每次 JVM 启动随机。
     * content/note 按审批侧上限截断——模型偶发写超长不该让整场面试收不了尾（截的是模型产出，不是用户内容）。
     */
    private String buildCandidatePayload(InterviewSessionEntity session, List<InterviewReport.Weakness> weaknesses) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", "面试模拟 · " + session.getCompany());
        List<Map<String, Object>> candidates = new ArrayList<>(weaknesses.size());
        for (int i = 0; i < weaknesses.size(); i++) {
            InterviewReport.Weakness w = weaknesses.get(i);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("candidateId", "c" + (i + 1));
            candidate.put("type", "INTERVIEW_WEAKNESS");
            candidate.put("content", truncate(w.content(), 512));
            if (w.confidence() != null) {
                candidate.put("confidence", w.confidence());
            }
            if (w.note() != null && !w.note().isBlank()) {
                candidate.put("note", truncate(w.note(), 255));
            }
            candidates.add(candidate);
        }
        root.put("candidates", candidates);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("组装面试弱点载荷失败", e);
        }
    }

    /** 载荷里按位置生成的候选 ID（c1..cN），与勾选值一一对应 */
    private List<String> candidateIdsFor(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add("c" + i);
        }
        return ids;
    }

    // ── 辅助 ────────────────────────────────────────────────────

    private void requireInProgress(InterviewSessionEntity session) {
        if (!InterviewStatus.IN_PROGRESS.name().equals(session.getStatus())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "面试已结束，无法继续作答");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}

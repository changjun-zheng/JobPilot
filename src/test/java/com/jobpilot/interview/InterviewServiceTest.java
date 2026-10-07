package com.jobpilot.interview;

import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.ai.ChatCompletion;
import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.FinishReason;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.config.InterviewProperties;
import com.jobpilot.domain.InterviewSessionEntity;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.memory.MemoryService;
import com.jobpilot.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试编排（{@code InterviewService}）的关键契约。
 * <p>
 * 不依赖 MySQL：mock store 与端口。真实的「跨调用状态推进 + 写库 + 审批落库」交给
 * {@code InterviewFlowIntegrationTest} 用真实 SQL 证明。
 */
class InterviewServiceTest {

    private InterviewStore store;
    private ChatPort chatPort;
    private KnowledgeRetrievalService retrievalService;
    private MemoryService memoryService;
    private ApprovalDraftService draftService;
    private InterviewService service;

    private static final String TENANT = "tenant-a";
    private static final String SESSION = "sess-1";

    private static final InterviewProperties PROPS = new InterviewProperties(
            List.of(3, 2, 2),
            Map.of(
                    "BIG_TECH", new InterviewProperties.InterviewTier(Difficulty.HARD, "DEEP", true),
                    "STARTUP", new InterviewProperties.InterviewTier(Difficulty.EASY, "PRAGMATIC", false)),
            "BIG_TECH");

    @BeforeEach
    void setUp() {
        store = mock(InterviewStore.class);
        chatPort = mock(ChatPort.class);
        retrievalService = mock(KnowledgeRetrievalService.class);
        memoryService = mock(MemoryService.class);
        draftService = mock(ApprovalDraftService.class);
        service = new InterviewService(store, PROPS, chatPort, retrievalService, memoryService, draftService);
        UserContext.set(TENANT);
        when(retrievalService.search(any(), any())).thenReturn(RetrievalResult.vector(List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void startSnapshotsDifficultyAndAsksFirstQuestion() {
        when(chatPort.chat(any())).thenReturn(completion("请说说你最近的项目。"));
        when(store.create(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(session());

        var result = service.start(new InterviewService.StartCommand(TENANT, "字节跳动", "后端", "BIG_TECH", null));

        ArgumentCaptor<String> resolved = ArgumentCaptor.forClass(String.class);
        verify(store).create(eq(TENANT), eq("字节跳动"), eq("后端"), eq("BIG_TECH"), any(), resolved.capture(), eq(3));
        assertThat(resolved.getValue()).isEqualTo("HARD"); // 档位预设
        assertThat(result.question()).isEqualTo("请说说你最近的项目。");
        assertThat(result.phase()).isEqualTo("BASIC");
        assertThat(result.round()).isEqualTo(1);
    }

    @Test
    void difficultyOverrideWinsOverTierPreset() {
        when(chatPort.chat(any())).thenReturn(completion("题"));
        when(store.create(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(session());

        service.start(new InterviewService.StartCommand(TENANT, "字节", null, "BIG_TECH", "EASY"));

        ArgumentCaptor<String> resolved = ArgumentCaptor.forClass(String.class);
        verify(store).create(any(), any(), any(), any(), any(), resolved.capture(), anyInt());
        assertThat(resolved.getValue()).isEqualTo("EASY");
    }

    @Test
    void unknownTierAndUnknownDifficultyAreRejected() {
        assertThatThrownBy(() -> service.start(
                new InterviewService.StartCommand(TENANT, "某公司", null, "NOPE", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知公司档位");

        assertThatThrownBy(() -> service.start(
                new InterviewService.StartCommand(TENANT, "某公司", null, "BIG_TECH", "IMPOSSIBLE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知难度");

        verify(store, never()).create(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void answerRetrievesInterviewExperiencesByDocType() {
        when(store.require(SESSION)).thenReturn(session(1, 1, "IN_PROGRESS"));
        when(store.messages(SESSION)).thenReturn(List.of());
        when(chatPort.chat(any())).thenReturn(completion("下一题"));

        service.answer(SESSION, "我做过一个订单系统");

        ArgumentCaptor<RetrievalQuery> queries = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalService, org.mockito.Mockito.atLeastOnce()).search(queries.capture(), any());
        assertThat(queries.getAllValues().stream().map(RetrievalQuery::docType).toList())
                .contains("INTERVIEW")   // 面经：专门按 doc_type 检索
                .containsNull();         // 简历/项目：不加类型过滤
    }

    @Test
    void answerInLastRoundFinishesWithAReport() {
        when(store.require(SESSION)).thenReturn(session(3, 2, "IN_PROGRESS"));
        when(store.messages(SESSION)).thenReturn(List.of());
        when(chatPort.chat(any())).thenReturn(completion(
                "{\"summary\":\"不错\",\"dimensions\":[],\"weaknesses\":[]}"));
        when(store.create(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(session());

        var result = service.answer(SESSION, "最后一题的回答");

        assertThat(result.finished()).isTrue();
        assertThat(result.question()).isNull();
        verify(store).appendCandidateAnswer(eq(TENANT), any(), eq("最后一题的回答"));
    }

    @Test
    void finishPersistsWeaknessesAsMemoryCandidateDraft() {
        when(store.require(SESSION)).thenReturn(session(3, 2, "IN_PROGRESS"));
        when(store.messages(SESSION)).thenReturn(List.of());
        when(chatPort.chat(any())).thenReturn(completion("""
                {"summary":"总体不错","dimensions":[{"name":"技术深度","comment":"ok","score":3}],
                 "weaknesses":[{"content":"并发基础薄弱","confidence":0.8,"note":"多线程回答不完整"}]}
                """));
        when(draftService.createDraft(any(), any(), any(), any(), any())).thenReturn("draft-1");

        var result = service.finish(SESSION);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(draftService).createDraft(eq(TENANT), any(), any(),
                eq("memory_candidate_create"), payload.capture());
        // 复用既有审批机制：toolName 是 memory_candidate_create，载荷是 INTERVIEW_WEAKNESS 候选
        assertThat(payload.getValue()).contains("INTERVIEW_WEAKNESS").contains("c1");
        assertThat(result.draftId()).isEqualTo("draft-1");
        assertThat(result.candidateIds()).containsExactly("c1");
        assertThat(result.status()).isEqualTo("REPORT_PENDING");

        verify(store).complete(any(), anyString(), eq("draft-1"), eq(InterviewStatus.REPORT_PENDING));
    }

    // ── helpers ───────────────────────────────────────────────

    private ChatCompletion completion(String content) {
        return new ChatCompletion(content, List.of(), FinishReason.STOP, null, "test", "test-model");
    }

    private InterviewSessionEntity session() {
        return session(1, 0, "IN_PROGRESS");
    }

    private InterviewSessionEntity session(int round, int questionsInRound, String status) {
        InterviewSessionEntity s = new InterviewSessionEntity();
        s.setId(SESSION);
        s.setUserId(TENANT);
        s.setCompany("字节跳动");
        s.setPosition("后端");
        s.setTier("BIG_TECH");
        s.setResolvedDifficulty("HARD");
        s.setCurrentPhase(InterviewStateMachine.phaseFor(round).name());
        s.setCurrentRound(round);
        s.setTotalRounds(3);
        s.setQuestionsInRound(questionsInRound);
        s.setStatus(status);
        return s;
    }
}

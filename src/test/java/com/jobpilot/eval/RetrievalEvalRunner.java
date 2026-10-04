package com.jobpilot.eval;

import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.eval.EvalSetLoader.EvalCase;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import com.jobpilot.usage.UsageScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索评测执行器（PRD §9.2，I-1 顺带清理项）。
 * <p>
 * 默认跳过——评测需要真实 Ollama + Chroma + MySQL，不适合混进日常 {@code mvn test}；
 * 本地基础设施就绪后手动执行：
 * <pre>EVAL_RUN=true mvn test -Dtest=RetrievalEvalRunner</pre>
 * 输出逐例命中情况与归因汇总（P@5）。无答案型只验证「证据为空」（NOT_LABELED）；
 * PROMPT_OR_MODEL 归因需要问答链路（LLM 生成）参与，属 I-2 评测扩展，本执行器不覆盖。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "jobpilot.ingest.enabled=false")
@EnabledIfEnvironmentVariable(named = "EVAL_RUN", matches = "true")
class RetrievalEvalRunner extends MySqlIntegrationTestBase {

    private static final String EVAL_USER = "eval-user";

    @Autowired
    private DocumentIngestService ingestService;
    @Autowired
    private KnowledgeRetrievalService retrievalService;

    private record CaseResult(String id, String type, boolean hit, String attribution, List<String> top5Paths) {
    }

    @BeforeAll
    static void requireTenantContext() {
        UserContext.set(EVAL_USER);
    }

    @AfterEach
    void restoreContext() {
        UserContext.set(EVAL_USER);
    }

    @Test
    void runEvaluation() {
        String documentId = ingestCorpus();
        List<EvalCase> cases = EvalSetLoader.loadCases();
        assertThat(cases).hasSize(20);

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            results.add(runCase(documentId, evalCase));
        }

        report(results);
    }

    private String ingestCorpus() {
        String corpus = EvalSetLoader.readResource(EvalSetLoader.CORPUS_RESOURCE);
        KbDocumentEntity pending = ingestService.enqueue(new IngestCommand(
                EVAL_USER, "eval-corpus.md", "MARKDOWN", "eval", corpus));
        pending.setStatus("PROCESSING");
        ingestService.process(pending);
        assertThat(pending.getStatus())
                .as("评测语料必须索引成功（Ollama/Chroma 需在线）")
                .isEqualTo("READY");
        return pending.getId();
    }

    private CaseResult runCase(String documentId, EvalCase evalCase) {
        RetrievalResult result = retrievalService.search(new RetrievalQuery(
                EVAL_USER, evalCase.question(), 5, null), UsageScenario.EVAL);

        List<String> top5Paths = result.items().stream()
                .map(item -> item.citation().sectionPath())
                .toList();
        boolean evidenceEmpty = top5Paths.isEmpty();

        if ("NO_ANSWER".equals(evalCase.type())) {
            // 无答案型：期望证据为空（最终回答是否伪造属问答链路评测，见类注释）
            boolean refusedBasis = evidenceEmpty || result.degraded();
            return new CaseResult(evalCase.id(), evalCase.type(), refusedBasis,
                    refusedBasis ? "NOT_LABELED" : "RETRIEVAL_MISS", top5Paths);
        }
        boolean hit = top5Paths.stream()
                .anyMatch(path -> evalCase.expected().stream().anyMatch(path::contains));
        String attribution = hit ? "PASS" : "RETRIEVAL_MISS";
        log(documentId, evalCase, hit, top5Paths);
        return new CaseResult(evalCase.id(), evalCase.type(), hit, attribution, top5Paths);
    }

    private void report(List<CaseResult> results) {
        long answered = results.stream().filter(r -> !"NO_ANSWER".equals(r.type())).count();
        long hits = results.stream()
                .filter(r -> !"NO_ANSWER".equals(r.type()) && r.hit()).count();
        Map<String, Long> byAttribution = new HashMap<>();
        for (CaseResult r : results) {
            byAttribution.merge(r.attribution(), 1L, Long::sum);
        }
        System.out.println("=============== 评测结果（P@5）===============");
        results.forEach(r -> System.out.printf(
                "%s [%s] %s top5=%s%n", r.id(), r.type(), r.hit() ? "HIT " : "MISS", r.top5Paths()));
        System.out.printf("P@5 = %d/%d = %.0f%%（目标 ≥ 80%%）%n", hits, answered,
                answered == 0 ? 0 : 100.0 * hits / answered);
        System.out.println("归因汇总：" + byAttribution);
    }

    private void log(String documentId, EvalCase evalCase, boolean hit, List<String> top5Paths) {
        if (!hit) {
            System.out.printf("MISS %s：%s%n  期望=%s top5=%s%n",
                    evalCase.id(), evalCase.question(), evalCase.expected(), top5Paths);
        }
    }
}

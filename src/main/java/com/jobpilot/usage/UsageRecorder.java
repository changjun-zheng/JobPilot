package com.jobpilot.usage;

import com.jobpilot.ai.TokenUsage;
import com.jobpilot.common.RequestId;
import com.jobpilot.domain.UsageRecordEntity;
import com.jobpilot.mapper.UsageRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量计量写入（PRD-FP-10 第一阶段「可归集」；配额与限流后置到真实数据积累之后）。
 *
 * <h3>计量口径（PRD §12.11 待决项的落地决策）</h3>
 * <ul>
 *   <li><b>本地路径 0 成本仍计量</b>：计量的是「消耗了什么」而非「花了多少钱」——
 *       将来切换云端路径，归集口径一行不改；</li>
 *   <li><b>token 未返回就是 NULL</b>：不估算、也不填 0——0 会让「供应商没给数据」
 *       伪装成「零消耗」。汇总侧用 {@code tokenUnavailable} 行数补充说明这个缺口；</li>
 *   <li><b>Embedding 记字符数（码点）</b>：PRD 允许字符数或 token 数二选一。嵌入供应商普遍
 *       不返回 token 数，字符数是唯一拿得到且不撒谎的量，按码点计与 ChunkSplitter 的纪律一致；</li>
 *   <li><b>粒度</b>：LLM 每次调用一行（Agent 循环里即每轮一行）；Agent run 每轮一行；
 *       Embedding 在检索路径每次一行，在导入路径按文档聚合成一行（含跨重试的每次尝试）。</li>
 *   <li><b>失败调用</b>：Agent 的最终失败调用记 FAILED 行（catch 点唯一，调用确实发生了，
 *       消耗不可知所以 tokens 为 NULL）；检索/问答的嵌入与生成失败不记——未产生可归集的消耗，
 *       失败本身有日志、降级标记与 trace。</li>
 * </ul>
 *
 * <h3>旁路语义（与 {@code AgentTraceRecorder} 同款）</h3>
 * 计量失败绝不影响主流程：方法体整体 try-catch，写失败只告警。
 * {@code REQUIRES_NEW} 隔离外层事务：LLM 调用已经真实发生，消耗不因后续业务回滚而消失——
 * 「预留-结算」的配额模式（后置）也依赖这个语义。
 */
@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageRecordMapper mapper;

    public UsageRecorder(UsageRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记一次 Embedding 消耗。
     *
     * @param charCount  文本字符总数（码点数）
     * @param callCount  覆盖的调用次数（检索路径恒为 1；导入按文档聚合）
     * @param ok         是否全部成功；导入部分成功的聚合行如实记 FAILED + 已消耗的量
     * @param documentId INGEST 场景的关联文档，其余为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEmbedding(String userId, UsageScenario scenario, String model,
                                int charCount, int callCount, boolean ok, String documentId) {
        try {
            UsageRecordEntity entity = base(userId, scenario, UsageDimension.EMBEDDING, model, ok);
            entity.setCharCount(charCount);
            entity.setCallCount(callCount);
            entity.setDocumentId(documentId);
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("用量计量写入失败（不影响主流程）dimension=EMBEDDING scenario={} userId={}",
                    scenario, userId, e);
        }
    }

    /**
     * 记一次 LLM 调用。tokens 取供应商返回值，未返回为 null；FAILED 行 usage 必为 null
     * （调用失败，消耗不可知——这正是「不可用要标记出来」而不是编一个数）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLlmCall(String userId, UsageScenario scenario, String model,
                              TokenUsage usage, boolean ok, String traceId) {
        try {
            UsageRecordEntity entity = base(userId, scenario, UsageDimension.LLM_TOKEN, model, ok);
            if (usage != null) {
                entity.setPromptTokens(usage.inputTokens());
                entity.setCompletionTokens(usage.outputTokens());
            }
            entity.setTraceId(traceId);
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("用量计量写入失败（不影响主流程）dimension=LLM_TOKEN scenario={} userId={}",
                    scenario, userId, e);
        }
    }

    /** 记一次 Agent run：迭代数与工具调用数来自 runner 的预算计数，是「调用了多少次」的事实。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAgentRun(String userId, String model, int iterations, int toolCalls,
                               boolean ok, String traceId) {
        try {
            UsageRecordEntity entity = base(userId, UsageScenario.AGENT, UsageDimension.AGENT_RUN, model, ok);
            entity.setIterations(iterations);
            entity.setToolCalls(toolCalls);
            entity.setTraceId(traceId);
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("用量计量写入失败（不影响主流程）dimension=AGENT_RUN userId={}", userId, e);
        }
    }

    private UsageRecordEntity base(String userId, UsageScenario scenario, UsageDimension dimension,
                                   String model, boolean ok) {
        UsageRecordEntity entity = new UsageRecordEntity();
        entity.setUserId(userId);
        entity.setDimension(dimension.name());
        entity.setScenario(scenario.name());
        entity.setModel(model);
        entity.setCallCount(1);
        entity.setStatus(ok ? "OK" : "FAILED");
        // request 线程自动带出关联 ID；worker 线程没有 MDC，保持 NULL（如实）
        entity.setRequestId(MDC.get(RequestId.MDC_KEY));
        return entity;
    }
}

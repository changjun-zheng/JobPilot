package com.jobpilot.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.mapper.AgentApprovalDraftMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * HITL 审批草稿（ARCHITECTURE.md §4.4）。
 *
 * <h3>为什么审批是「独立接口」而不是「在环上等」</h3>
 * 让 HTTP 请求挂起等人点确认，会引入三个新问题：连接超时、租户长时间占线程、暂停态存哪。
 * 因此写入类工具只落草稿并结束本轮 run，用户稍后经独立接口审批。
 *
 * <h3>幂等的两道防线</h3>
 * <ol>
 *   <li><b>并发</b>：审批在事务内先 {@code SELECT ... FOR UPDATE} 锁行，后到者重读发现状态
 *       已非 PENDING 就不重复执行副作用；</li>
 *   <li><b>重复落库</b>：{@code (user_id, idempotency_key)} 唯一键兜住「模型一次 run 内重复请求」，
 *       捕获 {@link DuplicateKeyException} 后按 user_id 重读并返回<b>同一个</b> draftId。</li>
 * </ol>
 */
@Service
public class ApprovalDraftService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalDraftService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    /**
     * 审批通过的两种终态。
     * <p>
     * 封闭枚举而非裸字符串：终态会写进 {@code agent_approval_draft.status} 并出现在接口响应里，
     * 拼错会得到一个既非 PENDING 也不被任何分支识别的中间值——与 {@code ErrorCode} 收成枚举同一理由。
     */
    public enum ApprovalOutcome {
        /** 全部候选通过（单条工具也走这个） */
        APPROVED,
        /** 只通过用户勾选的那部分（PRD-FP-3.2「整批，可部分选择」） */
        PARTIALLY_APPROVED
    }

    private final AgentApprovalDraftMapper draftMapper;

    public ApprovalDraftService(AgentApprovalDraftMapper draftMapper) {
        this.draftMapper = draftMapper;
    }

    /**
     * 落一条待审批草稿；同一 (用户, 幂等键) 重复请求时返回已存在的那条，而不是新建。
     *
     * @return 草稿 ID；重复请求时是<b>已有</b>草稿的 ID
     */
    @Transactional
    public String createDraft(String userId, String traceId, String conversationId,
                              String toolName, String payloadJson) {
        String idempotencyKey = sha256(traceId + "|" + toolName + "|" + conversationId + "|" + payloadJson);
        AgentApprovalDraftEntity draft = new AgentApprovalDraftEntity();
        draft.setUserId(userId);
        draft.setTraceId(traceId);
        draft.setConversationId(conversationId);
        draft.setToolName(toolName);
        draft.setPayloadJson(payloadJson);
        draft.setStatus(STATUS_PENDING);
        draft.setIdempotencyKey(idempotencyKey);
        try {
            draftMapper.insert(draft);
            return draft.getId();
        } catch (DuplicateKeyException e) {
            // 幂等防线②：唯一键挡住了重复落库。按 user_id 重读——必须带租户条件，
            // 否则「重读」本身就成了一个跨租户探测口。
            AgentApprovalDraftEntity existing = draftMapper.selectOne(
                    new QueryWrapper<AgentApprovalDraftEntity>()
                            .eq("idempotency_key", idempotencyKey));
            if (existing != null) {
                log.info("审批草稿已存在，返回原草稿（幂等）draftId={} tool={}", existing.getId(), toolName);
                return existing.getId();
            }
            throw e;
        }
    }

    /**
     * 审批通过并执行副作用。
     * <p>
     * <b>副作用必须在调用方的同一事务里执行</b>：本方法只做「抢占审批权」——
     * 锁行、检查状态、翻状态；真正的写入由调用方紧随其后完成，从而与状态翻转同生共死。
     * 若调用方没在同一事务里，{@code DocumentIngestService.enqueue} 的插入会独立提交，
     * 就会出现「副作用已发生但草稿仍 PENDING」的窗口。
     *
     * @return true = 本次调用抢到了审批权并已执行；false = 已被处理过（幂等返回，不重复执行）
     */
    @Transactional
    public boolean claimForApproval(String draftId) {
        return claimForApproval(draftId, ApprovalOutcome.APPROVED, null);
    }

    /**
     * 抢占审批权，并把草稿推向指定的终态。
     * <p>
     * <b>为什么终态是参数而不是另写一个方法</b>：幂等防线只看「状态是否还是 PENDING」，
     * 不关心写进去的是哪个终态值——参数化不会削弱它。而复制一份「锁行 + 查状态 + 写 decidedAt/By」
     * 的逻辑，两份迟早会漂移。
     * <p>
     * <b>本方法不自证事务</b>：它与调用方 {@code ApprovalExecutionService.approve} 同处一个事务
     * （默认传播 REQUIRED）。所以调用方<b>必须在调用之前</b>把所有校验做完——一旦抢占提交而副作用
     * 没执行，草稿就成了永久终态，此后每次 approve 都是静默 no-op，用户再也推不动它。
     *
     * @param outcome       写入的终态；批量工具按选中比例传 APPROVED 或 PARTIALLY_APPROVED
     * @param selectionJson 用户勾选的候选 ID 数组；整批通过或单条工具传 null
     * @return true = 本次抢到了审批权；false = 已被处理过（幂等返回，不重复执行）
     */
    @Transactional
    public boolean claimForApproval(String draftId, ApprovalOutcome outcome, String selectionJson) {
        String approver = UserContext.require();
        // 幂等防线①：锁行。并发审批在这里串行化。
        AgentApprovalDraftEntity draft = draftMapper.selectByIdForUpdate(draftId);
        if (draft == null) {
            // 查不到 = 不存在 **或** 属于别的租户。租户拦截器已把两者合并成同一个结果，
            // 对外无法区分——与 kb_document 的跨租户语义保持一致。
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        if (!STATUS_PENDING.equals(draft.getStatus())) {
            // 已处理过：拒绝后不能再批、批过不能再批、部分批过同样不能再批。不抛错，返回 false 让调用方按幂等返回。
            log.info("审批草稿已处于终态，跳过重复执行 draftId={} status={}", draftId, draft.getStatus());
            return false;
        }
        draft.setStatus(outcome.name());
        draft.setApprovalSelection(selectionJson);
        draft.setDecidedAt(LocalDateTime.now());
        draft.setDecidedBy(approver);
        draftMapper.updateById(draft);
        return true;
    }

    /** 拒绝；已处于终态时返回 false（幂等，不报错） */
    @Transactional
    public boolean reject(String draftId) {
        UserContext.require();
        AgentApprovalDraftEntity draft = draftMapper.selectByIdForUpdate(draftId);
        if (draft == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        if (!STATUS_PENDING.equals(draft.getStatus())) {
            log.info("审批草稿已处于终态，跳过拒绝 draftId={} status={}", draftId, draft.getStatus());
            return false;
        }
        draft.setStatus(STATUS_REJECTED);
        draft.setDecidedAt(LocalDateTime.now());
        draft.setDecidedBy(UserContext.require());
        draftMapper.updateById(draft);
        return true;
    }

    /** 审批通过并执行完毕后回填结果引用；不在此处改状态（状态已在 claimForApproval 翻过） */
    @Transactional
    public void recordResult(String draftId, String resultRef) {
        AgentApprovalDraftEntity draft = new AgentApprovalDraftEntity();
        draft.setId(draftId);
        draft.setResultRef(resultRef);
        draft.setExecutedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
    }

    public AgentApprovalDraftEntity get(String draftId) {
        return draftMapper.selectById(draftId);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算幂等键失败", e);
        }
    }
}

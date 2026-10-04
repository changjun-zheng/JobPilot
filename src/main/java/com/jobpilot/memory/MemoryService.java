package com.jobpilot.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.MemoryStatus;
import com.jobpilot.domain.MemoryType;
import com.jobpilot.domain.UserMemoryEntity;
import com.jobpilot.mapper.UserMemoryMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆（PRD-FP-4）。
 *
 * <h3>为什么没有 create</h3>
 * 用户能<b>查看、编辑、删除</b>自己的记忆，但<b>不能直接创建</b>——PRD 只列了前三个动词，
 * 而审批机制存在的理由就是「模型猜的必须经人确认」。唯一入口是
 * {@link #writeApprovedCandidates}，由审批通过后调用。用户自行添加相当于一次自我确认，
 * 那应当作为独立需求单独设计，而不是在这个类里开个后门。
 *
 * <h3>与 RAG 的分工靠列宽强制</h3>
 * 正文 512 字上限不是随手定的：它让「长篇材料请走知识库」从提示词里的请求变成服务端的拒绝。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 与 DDL 列宽一致 */
    static final int MAX_CONTENT = 512;
    static final int MAX_NOTE = 255;
    static final int MAX_SOURCE = 64;
    /** 一次候选提交的条数上限；模型一次生成几十条多半是跑偏了 */
    static final int MAX_CANDIDATES = 20;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final UserMemoryMapper memoryMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryService(UserMemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    /** 用户可见的编辑补丁：null = 不修改；空串 = 清空（仅 note 可清空） */
    public record Patch(String content, String type, String note, String status) {
    }

    /** 一个候选：由模型生成、经用户勾选后落库 */
    public record Candidate(String candidateId, MemoryType type, String content,
                            BigDecimal confidence, String note) {
    }

    /** 解析并校验过的批量载荷 */
    public record CandidateBatch(String source, List<Candidate> candidates) {
    }

    // ── 用户侧：查看 / 编辑 / 删除 ──────────────────────────────

    public UserMemoryEntity get(String id) {
        UserMemoryEntity memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "记忆不存在：" + id);
        }
        return memory;
    }

    public List<UserMemoryEntity> list(String type, String status, int limit) {
        QueryWrapper<UserMemoryEntity> wrapper = new QueryWrapper<>();
        if (type != null && !type.isBlank()) {
            wrapper.eq("type", MemoryType.parse(type).name());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", MemoryStatus.parse(status).name());
        }
        wrapper.orderByDesc("updated_at").orderByDesc("created_at");
        wrapper.last("LIMIT " + clampLimit(limit));
        return memoryMapper.selectList(wrapper);
    }

    /**
     * 部分更新：先读（租户范围内）确认存在，合并补丁，再写——与 {@code ApplicationService} 同一写法。
     * <p>
     * <b>可改的是</b> content / type / note / status；<b>不可改</b>的是 source、sourceDraftId、confidence——
     * 它们是来源与依据，改了等于伪造出处。
     */
    @Transactional
    public UserMemoryEntity update(String id, Patch patch) {
        UserMemoryEntity existing = memoryMapper.selectById(id);
        if (existing == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "记忆不存在：" + id);
        }
        String content = patch.content() == null ? existing.getContent()
                : requireContent(patch.content());
        String type = patch.type() == null ? existing.getType()
                : MemoryType.parse(patch.type()).name();
        String note = patch.note() == null ? existing.getNote()
                : blankToNull(patch.note(), "说明", MAX_NOTE);
        String status = patch.status() == null ? existing.getStatus()
                : MemoryStatus.parse(patch.status()).name();

        UpdateWrapper<UserMemoryEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("content", content)
                .set("type", type)
                .set("note", note)
                .set("status", status);
        int affected = memoryMapper.update(null, wrapper);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "记忆不存在：" + id);
        }
        return memoryMapper.selectById(id);
    }

    /** 硬删；影响 0 行即不存在或跨租户。审批记录留在草稿上，删除记忆不会抹掉「当初批准过什么」 */
    public void delete(String id) {
        int affected = memoryMapper.delete(new QueryWrapper<UserMemoryEntity>().eq("id", id));
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "记忆不存在：" + id);
        }
    }

    /** 按产生它的草稿回查「实际写了哪些」——批量审批的效果侧事实来源，与草稿上的意图分开存 */
    public List<String> findIdsBySourceDraft(String draftId) {
        return memoryMapper.selectList(
                        new QueryWrapper<UserMemoryEntity>().eq("source_draft_id", draftId))
                .stream().map(UserMemoryEntity::getId).toList();
    }

    // ── 内部：审批通过后写入 ────────────────────────────────────

    /**
     * 解析并校验批量载荷。<b>必须在抢占审批权之前调用</b>——校验失败要能在状态被翻转之前抛出。
     * <p>
     * 未知类型、超长、缺字段全部显式拒绝：草稿可能是旧版本创建的，写入时重新校验一遍而不是信任它。
     */
    public CandidateBatch parseBatch(String payloadJson) {
        JsonNode root;
        try {
            root = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷不是合法 JSON，无法执行");
        }
        String source = root.path("source").asText("").strip();
        if (source.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷缺少 source");
        }
        if (source.length() > MAX_SOURCE) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "source 超过长度上限 " + MAX_SOURCE);
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷缺少 candidates 数组");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "候选数超过上限 " + MAX_CANDIDATES);
        }
        List<Candidate> parsed = new ArrayList<>(candidates.size());
        for (JsonNode node : candidates) {
            String candidateId = node.path("candidateId").asText("").strip();
            if (candidateId.isEmpty()) {
                throw invalid("候选缺少 candidateId");
            }
            MemoryType type;
            try {
                type = MemoryType.parse(node.path("type").asText(""));
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
            String content = requireContent(node.path("content").asText(""));
            BigDecimal confidence = node.hasNonNull("confidence")
                    ? BigDecimal.valueOf(node.path("confidence").asDouble())
                    : null;
            String note = node.path("note").asText("");
            parsed.add(new Candidate(candidateId, type, content, confidence,
                    note.isBlank() ? null : checkedNote(note)));
        }
        return new CandidateBatch(source, parsed);
    }

    /**
     * 把选中的候选写成记忆行。
     * <p>
     * <b>不在这里开事务</b>：由调用方（审批执行）的事务托管，从而与草稿状态翻转同生共死——
     * 批量写到一半失败必须整体回滚，否则会出现「草稿已终态、只写进去一半」。
     * <p>
     * 入参是<b>已解析好的选中列表</b>而非原始 ID：解析与校验由调用方在抢占审批权<b>之前</b>完成，
     * 这里只负责写（若在此重复解析，校验就又跑到抢占之后去了）。
     *
     * @return 新建记忆的 ID，顺序与选中项一致
     */
    public List<String> writeCandidates(String userId, String draftId, String source, List<Candidate> selected) {
        List<String> createdIds = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            UserMemoryEntity memory = new UserMemoryEntity();
            // 租户键显式设置：拦截器不会覆盖实体已带的 user_id（ignoreInsert 会跳过）
            memory.setUserId(userId);
            memory.setType(candidate.type().name());
            memory.setContent(candidate.content());
            memory.setSource(source);
            memory.setSourceDraftId(draftId);
            memory.setConfidence(candidate.confidence());
            memory.setNote(candidate.note());
            memory.setStatus(MemoryStatus.ACTIVE.name());
            memoryMapper.insert(memory);
            createdIds.add(memory.getId());
        }
        log.info("审批写入记忆 draftId={} 写入 {} 条", draftId, selected.size());
        return createdIds;
    }

    /**
     * 把用户的选择解析成候选列表。
     * <p>
     * <b>未知 ID 与重复 ID 都拒绝，不静默丢弃</b>：静默丢会让用户看到「我勾了 3 条」而实际写入 2 条，
     * 响应无法自证。空列表也拒绝——那是 reject 的语义，批成 {@code PARTIALLY_APPROVED} 写出 0 行
     * 会得到一个撒谎的状态。
     */
    public List<Candidate> resolveSelection(CandidateBatch batch, List<String> selectedCandidateIds) {
        if (selectedCandidateIds == null) {
            return batch.candidates();
        }
        if (selectedCandidateIds.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "未选择任何候选项；若要全部拒绝请用 reject");
        }
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate candidate : batch.candidates()) {
            byId.put(candidate.candidateId(), candidate);
        }
        List<Candidate> selected = new ArrayList<>(selectedCandidateIds.size());
        for (String id : selectedCandidateIds) {
            Candidate candidate = byId.get(id);
            if (candidate == null) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "候选项不存在：" + id);
            }
            if (selected.contains(candidate)) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "候选项重复选择：" + id);
            }
            selected.add(candidate);
        }
        return selected;
    }

    // ── 校验 ────────────────────────────────────────────────────

    private String requireContent(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw invalid("记忆内容不能为空");
        }
        if (stripped.length() > MAX_CONTENT) {
            throw invalid("记忆内容超过 " + MAX_CONTENT + " 字（当前 " + stripped.length()
                    + "）。长篇材料请保存为知识库文档，长期记忆只放短小条目。");
        }
        return stripped;
    }

    private String checkedNote(String value) {
        String stripped = value.strip();
        if (stripped.length() > MAX_NOTE) {
            throw invalid("说明超过长度上限 " + MAX_NOTE);
        }
        return stripped;
    }

    private String blankToNull(String value, String field, int maxLength) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > maxLength) {
            throw invalid(field + "超过长度上限 " + maxLength);
        }
        return stripped;
    }

    /**
     * 校验失败一律走 {@link ApiException} 的 {@code BAD_REQUEST}，不用 {@code IllegalArgumentException}。
     * <p>
     * 本类只被 HTTP 审批路径调用（不是 agent 工具入口），不需要工具那套「转成结构化失败回填模型」的
     * 处理——混用两种异常会让调用方不确定该接哪个。
     */
    /**
     * 校验失败——一律 {@link IllegalArgumentException}。
     * <p>
     * <b>刻意不用 {@code ApiException}</b>：工具层的错误映射把 {@code ApiException} 当作
     * 「资源不存在」归到 {@code NOT_FOUND}，而 {@code IllegalArgumentException} 才归到
     * {@code INVALID_ARGUMENTS}。用错的话，「内容超长」会在 trace 里被记成「对象不存在」，
     * 排查时被误导。这也与 {@code ApplicationService} / {@code DocumentIngestService} 的约定一致：
     * 参数与取值非法走 {@code IllegalArgumentException}，不存在走 {@code ApiException(NOT_FOUND)}。
     */
    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}

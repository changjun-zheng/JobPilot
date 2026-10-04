package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.domain.UserMemoryEntity;
import com.jobpilot.memory.MemoryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆 API（PRD-FP-4）。
 *
 * <h3>为什么没有 POST</h3>
 * PRD-FP-4 给用户的是「查看、编辑和删除」，<b>没有创建</b>。记忆的唯一创建路径是
 * Agent 生成候选 → 用户审批（{@code memory_candidate_create} + {@code /agent/approvals/...}）。
 * 加一个直建接口等于凭空多一条绕过审批的写入路径，把 HITL 的意义抵消掉——
 * 所以这里连一个返回 405 的桩都不放。
 */
@RestController
@RequestMapping("/api/v1/memories")
@Validated
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public record PatchRequest(
            String content,
            String type,
            /** 传空串 = 清空说明 */
            String note,
            String status
    ) {
    }

    public record MemoryResponse(
            String id,
            String type,
            String content,
            String source,
            /** 产生该记忆的审批草稿；可据此回溯当时批准了哪一批 */
            String sourceDraftId,
            BigDecimal confidence,
            String note,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {

        static MemoryResponse from(UserMemoryEntity entity) {
            return new MemoryResponse(entity.getId(), entity.getType(), entity.getContent(),
                    entity.getSource(), entity.getSourceDraftId(), entity.getConfidence(),
                    entity.getNote(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 列表：可按类型与状态过滤；`type` / `status` 传未知值时显式报错，不静默返回空列表 */
    @GetMapping
    public ApiResponse<List<MemoryResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        List<MemoryResponse> items = memoryService.list(type, status, limit)
                .stream().map(MemoryResponse::from).toList();
        return ApiResponse.ok(items);
    }

    @GetMapping("/{id}")
    public ApiResponse<MemoryResponse> get(@PathVariable String id) {
        return ApiResponse.ok(MemoryResponse.from(memoryService.get(id)));
    }

    /**
     * 编辑。字段缺省 = 不修改；`note` 传空串 = 清空。
     * <p>
     * `source` / `sourceDraftId` / `confidence` <b>不可改</b>——它们记录「这条记忆从哪来、
     * 当时多确信」，事后修改等于伪造出处，所以请求体里根本没有这几个字段。
     */
    @PatchMapping("/{id}")
    public ApiResponse<MemoryResponse> patch(@PathVariable String id,
                                             @RequestBody @Validated PatchRequest request) {
        UserMemoryEntity updated = memoryService.update(id, new MemoryService.Patch(
                request.content(), request.type(), request.note(), request.status()));
        return ApiResponse.ok(MemoryResponse.from(updated));
    }

    /**
     * 删除（硬删）。
     * <p>
     * 与「归档」（状态置 {@code ARCHIVED}）是两件事：归档保留记录但不再视为当前有效，
     * 删除是真的移除。审批记录不随删除消失——它留在草稿上。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        memoryService.delete(id);
        return ApiResponse.ok(null);
    }
}

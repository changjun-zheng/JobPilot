package com.jobpilot.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 记忆条目的生命周期状态。
 * <p>
 * <b>刻意不表达审批状态</b>：记忆行存在即已被批准（创建只经 HITL），审批记录在草稿上
 * （{@code agent_approval_draft.status} / {@code decided_by} / {@code approval_selection}）。
 * 把审批状态镜像到行上会造成两处事实来源。因此这里没有 PENDING/APPROVED，
 * 而 {@code ACTIVE} 也不是「已确认」的同义词——它是「生效中」。
 */
public enum MemoryStatus {
    /** 生效中 */
    ACTIVE,
    /** 用户归档：保留记录但不再视为当前有效（删除是另一条路，硬删） */
    ARCHIVED;

    public static MemoryStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆状态不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知记忆状态：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }
}

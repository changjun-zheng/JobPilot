package com.jobpilot.interview;

import java.util.List;

/**
 * 面试阶段弧线（BRD US-3：基础 → 项目深挖 → 压力）。
 * <p>
 * 顺序即推进顺序——{@link #next()} 由编译器穷尽保证，新增阶段必须显式接入弧线。
 */
public enum InterviewPhase {

    /** 基础题：语言/框架常识 */
    BASIC,

    /** 项目深挖：基于简历/项目细节追问 */
    PROJECT_DEEP_DIVE,

    /** 压力面：连锁追问、质疑与边界 */
    PRESSURE;

    /** 弧线顺序（含全部阶段）。轮数与它一一对应 */
    public static final List<InterviewPhase> ARC = List.of(BASIC, PROJECT_DEEP_DIVE, PRESSURE);

    /** 弧线里的下一个阶段；已是最后一个则返回 null（由服务端据此收尾） */
    public InterviewPhase next() {
        int idx = ARC.indexOf(this);
        return idx >= 0 && idx + 1 < ARC.size() ? ARC.get(idx + 1) : null;
    }
}

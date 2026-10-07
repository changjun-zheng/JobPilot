package com.jobpilot.interview;

/**
 * 面试会话状态。
 * <p>
 * {@code REPORT_PENDING}：报告已生成、弱点候选已落审批草稿，等用户审批后才写记忆。
 */
public enum InterviewStatus {

    /** 面试进行中 */
    IN_PROGRESS,

    /** 已收尾：报告 + 弱点草稿已生成，待审批 */
    REPORT_PENDING,

    /** 已终态（报告已出） */
    COMPLETED
}

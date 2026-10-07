package com.jobpilot.interview;

import java.util.List;

/**
 * 面试阶段推进的**纯函数**裁决（BRD US-3：基础 → 项目深挖 → 压力）。
 *
 * <h3>为什么服务端裁决，而不是交给模型</h3>
 * 模型只负责出题措辞与追问内容；<b>走到第几轮、是否进下一阶段、何时收尾，由这里决定</b>。
 * 让模型自报「进度」会让轮数不可控（模型可能连问多题、或提前说结束）——「至少三轮递进」这条
 * BRD 验收要靠服务端保证，而模型换措辞也不该改变它。
 *
 * <h3>状态语义</h3>
 * 一次「作答」提交后：本轮还有题 → {@code ASK_NEXT}；本轮题数已满且未到最后一轮 → {@code ADVANCE_ROUND}；
 * 最后一轮也满了 → {@code FINISH}。轮次与阶段一一对应（第 1 轮 = 基础，第 2 = 深挖，第 3 = 压力）。
 */
public final class InterviewStateMachine {

    /** 一次作答提交后的推进决定 */
    public enum Decision {
        /** 同一轮继续出下一题 */
        ASK_NEXT,
        /** 本轮结束，进入下一轮/阶段 */
        ADVANCE_ROUND,
        /** 弧线走完，收尾出报告 */
        FINISH
    }

    /**
     * 推进前的状态。
     *
     * @param round           当前第几轮（1-based）
     * @param questionsInRound 当前轮已出的题数
     */
    public record State(int round, int questionsInRound) {
    }

    /** 推进结果：决定 + 推进后的状态（{@code FINISH} 时状态不变） */
    public record Transition(Decision decision, State next) {
    }

    private InterviewStateMachine() {
    }

    /**
     * 给定当前状态与每轮题数，裁决下一步。
     *
     * @param questionsPerRound 每轮题数，顺序对应 {@link InterviewPhase#ARC}（长度即总轮数）
     */
    public static Transition afterAnswer(State current, List<Integer> questionsPerRound) {
        int totalRounds = questionsPerRound.size();
        int questionsThisRound = questionsPerRound.get(current.round() - 1);

        if (current.questionsInRound() < questionsThisRound) {
            return new Transition(Decision.ASK_NEXT,
                    new State(current.round(), current.questionsInRound() + 1));
        }
        if (current.round() < totalRounds) {
            return new Transition(Decision.ADVANCE_ROUND,
                    new State(current.round() + 1, 1));
        }
        return new Transition(Decision.FINISH, current);
    }

    /** 轮次 → 阶段（第 1 轮 = 弧线首阶段） */
    public static InterviewPhase phaseFor(int round) {
        return InterviewPhase.ARC.get(round - 1);
    }
}

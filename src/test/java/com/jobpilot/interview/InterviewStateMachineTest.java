package com.jobpilot.interview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试阶段推进的裁决（BRD US-3：至少三轮递进，服务端保证）。
 */
class InterviewStateMachineTest {

    /** 基础 3 题 / 深挖 2 题 / 压力 2 题 */
    private static final List<Integer> PER_ROUND = List.of(3, 2, 2);

    @Test
    void asksNextQuestionWithinRound() {
        var t = InterviewStateMachine.afterAnswer(new InterviewStateMachine.State(1, 1), PER_ROUND);

        assertThat(t.decision()).isEqualTo(InterviewStateMachine.Decision.ASK_NEXT);
        assertThat(t.next()).isEqualTo(new InterviewStateMachine.State(1, 2));
    }

    @Test
    void advancesToNextRoundWhenRoundComplete() {
        var t = InterviewStateMachine.afterAnswer(new InterviewStateMachine.State(1, 3), PER_ROUND);

        assertThat(t.decision()).isEqualTo(InterviewStateMachine.Decision.ADVANCE_ROUND);
        assertThat(t.next()).isEqualTo(new InterviewStateMachine.State(2, 1));
    }

    @Test
    void finishesAfterLastRound() {
        var t = InterviewStateMachine.afterAnswer(new InterviewStateMachine.State(3, 2), PER_ROUND);

        assertThat(t.decision()).isEqualTo(InterviewStateMachine.Decision.FINISH);
    }

    @Test
    void phaseFollowsTheArc() {
        assertThat(InterviewStateMachine.phaseFor(1)).isEqualTo(InterviewPhase.BASIC);
        assertThat(InterviewStateMachine.phaseFor(2)).isEqualTo(InterviewPhase.PROJECT_DEEP_DIVE);
        assertThat(InterviewStateMachine.phaseFor(3)).isEqualTo(InterviewPhase.PRESSURE);
    }

    @Test
    void fullArcAsksExactlyTheConfiguredNumberOfQuestions() {
        // 从「第 1 轮已问 1 题」出发，一路推进到 FINISH，数一共问了几题
        var state = new InterviewStateMachine.State(1, 1);
        int asked = 1; // 首题在 start 时已提出
        InterviewStateMachine.Decision decision;
        do {
            var t = InterviewStateMachine.afterAnswer(state, PER_ROUND);
            decision = t.decision();
            state = t.next();
            if (decision != InterviewStateMachine.Decision.FINISH) {
                asked++;
            }
        } while (decision != InterviewStateMachine.Decision.FINISH);

        // 3+2+2 = 7 题、三轮，不多不少——「至少三轮」由服务端保证，模型换措辞也不改变轮数
        assertThat(asked).isEqualTo(7);
        assertThat(state.round()).isEqualTo(3);
    }
}

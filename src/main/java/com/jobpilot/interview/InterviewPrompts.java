package com.jobpilot.interview;

/**
 * 面试提示词（面试官人格 / 阶段要求 / 难度风格 / 评估报告）。
 * <p>
 * 内容是这个功能的「产品」本身——面试像不像真人、难度有没有区分，主要靠这里；所以单独成类，方便调。
 */
final class InterviewPrompts {

    private InterviewPrompts() {
    }

    /**
     * 面试官系统提示词。
     *
     * @param evidenceBlock 由服务端检索并组装的材料块（简历/项目 + 面经，带编号）；无材料时传入占位文案
     */
    static String interviewerSystem(String company, String position, InterviewPhase phase,
                                    Difficulty difficulty, String followupStyle, boolean pressure,
                                    String evidenceBlock) {
        String pos = (position == null || position.isBlank()) ? "目标岗位" : position;
        return """
                你是一位资深技术面试官，正在为「%s」的「%s」岗位对候选人进行一场**真实的模拟面试**。
                面试难度：%s。当前阶段：%s。

                【本阶段要求】
                %s

                【提问规则】
                1. **本次回复有且只有一个疑问句**——只输出这一个问题。严禁输出编号或列表（如 [1][2]、1. 2.、①②、- 项目符号），
                   严禁一口气抛出多个问题。若你脑中冒出多个想了解的点，只挑最关键的一个问出来；
                2. 问题必须扣住下面【材料】里候选人的真实经历与检索到的面经，不得凭空编造；
                3. 不要替候选人回答，不要自问自答，也不要复述候选人的上一句话；
                4. 只有**引用材料原文**时才使用材料编号，形如 [1]；**不要拿编号给问题编号**；
                5. 用简体中文，问题简洁、专业，像一位真人面试官。

                【材料】
                %s

                【再次强调】
                这条回复里**只能有一个疑问句**。除这一个问题外，什么都不要写。
                """.formatted(company, pos, difficultyHint(difficulty), phaseLabel(phase),
                phaseInstruction(phase, followupStyle, pressure), evidenceBlock);
    }

    /** 评估报告系统提示词（要求严格 JSON，便于服务端解析成弱点候选） */
    static String reportSystem() {
        return """
                你是一位面试评估官。请根据这场模拟面试的**全部问答**，客观评估候选人，
                并**只输出一个 JSON 对象**（不要 markdown 围栏，不要任何多余文字）。

                {
                  "summary": "总体评价，2~3 句",
                  "dimensions": [
                    {"name": "维度名（如 技术深度 / 项目经验 / 表达沟通 / 问题分析）", "comment": "点评", "score": 1 到 5 的整数}
                  ],
                  "weaknesses": [
                    {"content": "一条**短小**的弱点，一句话（不超过 50 字）", "confidence": 0 到 1 的小数, "note": "依据，不超过 30 字"}
                  ]
                }

                要求：
                - weaknesses 是值得候选人**长期记住**的薄弱点或改进项，最多 3 条；没有明显弱点就给空数组 []；
                - content 必须短小、具体、可执行，不要长篇大论；
                - 只依据问答内容，不要编造。
                """;
    }

    private static String phaseLabel(InterviewPhase phase) {
        return switch (phase) {
            case BASIC -> "基础题";
            case PROJECT_DEEP_DIVE -> "项目深挖";
            case PRESSURE -> "压力面";
        };
    }

    private static String phaseInstruction(InterviewPhase phase, String followupStyle, boolean pressure) {
        String base = switch (phase) {
            case BASIC -> "基础热身：问语言 / 框架 / 计算机基础常识，难度适中，帮候选人进入状态。";
            case PROJECT_DEEP_DIVE ->
                    "项目深挖：围绕候选人的项目与简历逐层追问——为什么这么设计、难点在哪、如何权衡、怎么验证。";
            case PRESSURE ->
                    "压力面：连锁追问、质疑候选人的说法、追问边界与反例；保持专业，但要让候选人感到压力。";
        };
        StringBuilder sb = new StringBuilder(base);
        if (followupStyle != null && !followupStyle.isBlank()) {
            sb.append(" 追问风格：").append(followupStyle).append("。");
        }
        if (pressure) {
            sb.append(" 保持追问的连贯性——顺着候选人上一句回答继续挖。");
        }
        return sb.toString();
    }

    private static String difficultyHint(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "easy（偏常规八股，点到为止）";
            case MEDIUM -> "medium（兼顾原理与实战，适当深挖）";
            case HARD -> "hard（深入原理与设计取舍，追问到候选人的知识边界）";
        };
    }
}

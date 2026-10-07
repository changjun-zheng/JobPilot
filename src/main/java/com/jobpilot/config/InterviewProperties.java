package com.jobpilot.config;

import com.jobpilot.interview.Difficulty;
import com.jobpilot.interview.InterviewPhase;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 面试模拟官配置（{@code jobpilot.interview.*}，BRD US-3）。
 * <p>
 * 公司档位（{@code tiers}）→ 预设难度与追问风格；用户可在创建会话时用 {@code difficultyOverride} 覆盖。
 * <b>难度在创建时快照</b>，改这里不会改写历史会话。
 * <p>
 * <b>只保留规范构造器</b>：加第二个构造器会让 Boot 绑定直接报「No default constructor found」
 * （{@code AgentProperties} 已记过这个坑）。Map 值绑定到嵌套 record 也属于 Boot 的易错面，
 * 用 {@code InterviewPropertiesTest} 的绑定测试兜住。
 *
 * @param questionsPerRound 每轮出题数，顺序对应 {@link InterviewPhase#ARC}；数量必须与弧线等长
 * @param tiers             公司档位 → 难度/追问风格/是否压力面
 * @param defaultTier       未显式指定档位时的默认值（须是 {@code tiers} 的一个键）
 */
@ConfigurationProperties(prefix = "jobpilot.interview")
public record InterviewProperties(
        List<Integer> questionsPerRound,
        Map<String, InterviewTier> tiers,
        String defaultTier
) {

    /** 一个公司档位：预设难度、追问风格标签、是否开启压力面 */
    public record InterviewTier(Difficulty difficulty, String followupStyle, boolean pressure) {
    }

    public InterviewProperties {
        questionsPerRound = questionsPerRound == null ? List.of() : List.copyOf(questionsPerRound);
        tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
        // 配置错误在启动时炸，别等面试跑到一半才发现弧线对不上
        if (questionsPerRound.size() != InterviewPhase.ARC.size()) {
            throw new IllegalStateException(
                    "jobpilot.interview.questions-per-round 必须正好 " + InterviewPhase.ARC.size()
                            + " 个（与面试阶段弧线 基础→深挖→压力 一致），当前 " + questionsPerRound.size() + " 个");
        }
        if (tiers.isEmpty()) {
            throw new IllegalStateException("jobpilot.interview.tiers 未配置");
        }
    }

    /** 面试总轮数 = 阶段弧线长度（基础→深挖→压力） */
    public int totalRounds() {
        return InterviewPhase.ARC.size();
    }

    /** 第 {@code round} 轮（1-based）的出题数 */
    public int questionsInRound(int round) {
        return questionsPerRound.get(round - 1);
    }

    /** 默认档位；{@code default-tier} 留空时取第一个档位 */
    public String resolvedDefaultTier() {
        return defaultTier == null || defaultTier.isBlank()
                ? tiers.keySet().iterator().next()
                : defaultTier;
    }

    /** 取档位；未知档位显式拒绝 */
    public InterviewTier tier(String tier) {
        InterviewTier found = tiers.get(tier);
        if (found == null) {
            throw new IllegalArgumentException(
                    "未知公司档位：" + tier + "，允许值：" + tiers.keySet());
        }
        return found;
    }
}

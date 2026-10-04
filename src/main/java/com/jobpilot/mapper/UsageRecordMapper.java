package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.UsageRecordEntity;
import com.jobpilot.usage.UsageAggregateRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface UsageRecordMapper extends BaseMapper<UsageRecordEntity> {

    /**
     * 按（维度 × 场景）聚合区间内的计量行。
     * <p>
     * user_id 过滤由租户拦截器追加（与全项目一致：隔离不依赖调用方自觉），本 SQL 不写。
     * SUM 自动忽略 NULL；token_unavailable 单独数出「供应商未返回 token」的 LLM 行数——
     * 部分已知的合计不能被当成完整总量（PRD-FP-10：不得估算后当作真实值）。
     */
    @Select("""
            SELECT dimension,
                   scenario,
                   COALESCE(SUM(call_count), 0)          AS call_count,
                   COALESCE(SUM(char_count), 0)          AS char_count,
                   COALESCE(SUM(prompt_tokens), 0)       AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)   AS completion_tokens,
                   COALESCE(SUM(CASE WHEN dimension = 'LLM_TOKEN' AND prompt_tokens IS NULL
                                     THEN 1 ELSE 0 END), 0) AS token_unavailable,
                   COALESCE(SUM(iterations), 0)          AS iterations,
                   COALESCE(SUM(tool_calls), 0)          AS tool_calls
            FROM usage_record
            WHERE created_at >= #{from} AND created_at < #{to}
            GROUP BY dimension, scenario
            """)
    List<UsageAggregateRow> aggregate(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

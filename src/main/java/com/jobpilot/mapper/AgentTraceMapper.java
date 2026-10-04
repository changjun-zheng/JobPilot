package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.AgentTraceEntity;
import org.apache.ibatis.annotations.Update;

/**
 * run 追踪汇总。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：trace 全部读写都在请求线程上，UserContext 已就位，
 * 租户条件由拦截器自动注入——这正是「trace 跨租户不可见」得以成立的机制。
 */
public interface AgentTraceMapper extends BaseMapper<AgentTraceEntity> {

    /**
     * 收尾：只在尚未收尾时写入（{@code WHERE ended_at IS NULL}），因此重复调用不会覆盖已有结果。
     * 返回 0 表示这次 run 已被别的路径收尾过（重复 finish 或并发 finish）。
     */
    @Update("UPDATE agent_trace SET ended_at = #{endedAt}, duration_ms = #{durationMs}, "
            + "status = #{status}, finish_reason = #{finishReason} "
            + "WHERE id = #{id} AND ended_at IS NULL")
    int finish(AgentTraceEntity trace);
}

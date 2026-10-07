package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.InterviewSessionEntity;

/**
 * 面试会话。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户拦截器照常注入 {@code user_id}。
 */
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionEntity> {
}

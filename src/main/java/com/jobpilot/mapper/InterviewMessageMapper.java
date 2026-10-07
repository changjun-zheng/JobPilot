package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.InterviewMessageEntity;

/**
 * 面试消息。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户条件由拦截器注入。
 */
public interface InterviewMessageMapper extends BaseMapper<InterviewMessageEntity> {
}

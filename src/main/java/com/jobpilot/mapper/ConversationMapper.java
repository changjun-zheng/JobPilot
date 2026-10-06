package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.ConversationEntity;

/**
 * 会话。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户拦截器照常注入 {@code user_id}——
 * 跨租户的读、删因此都被自然挡住（删别人的行影响 0 行）。
 */
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}

package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.ConversationMessageEntity;

/**
 * 会话消息。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户条件由拦截器注入。
 * 按会话回捞消息时租户条件已在 WHERE 里，分组 / 排序不跨越租户。
 */
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
}

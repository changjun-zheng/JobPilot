package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.UserMemoryEntity;

/**
 * 长期记忆。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户拦截器照常注入 {@code user_id}。
 * 「按草稿回查写入了哪些记忆」也靠这个——{@code source_draft_id} 的查询会被自动加上租户条件。
 */
public interface UserMemoryMapper extends BaseMapper<UserMemoryEntity> {
}

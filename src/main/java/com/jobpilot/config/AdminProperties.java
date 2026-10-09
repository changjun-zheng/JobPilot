package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 管理端配置（{@code jobpilot.admin.*}）——**第二条认证轴**。
 * <p>
 * 与租户认证（{@code jobpilot.security.*}）完全独立：单独的密钥与签发方，管理令牌不能访问
 * 用户 API，用户令牌也不能访问管理 API——两条轴互不认账。
 * <p>
 * <b>不配置即禁用</b>：{@code username} 留空时整个管理面关闭（所有 /api/v1/admin/** 一律 401），
 * 应用照常启动——与「云端 key 没配可走 ollama」同一哲学：可选能力缺配置只关闭能力，不炸进程。
 * 管理端是**运营面的门**，而不是第二个用户系统：单运营者阶段用配置态账号，不建 admin 表
 * （多管理员与操作留痕见设计草案 §10.3，后置）。
 *
 * @param username     管理员登录名；**空 = 管理面禁用**
 * @param password     管理员密码（明文进 .env，校验走**恒定时间比较**——不比对长度，避免逐字符探测）
 * @param jwtSecret    管理端 JWT 签名密钥（与租户密钥独立；≥32 字节，仅在启用时校验）
 * @param accessTokenTtl 管理令牌有效期（默认比租户令牌短：运营面权限大，暴露窗口要小）
 */
@ConfigurationProperties(prefix = "jobpilot.admin")
public record AdminProperties(
        String username,
        String password,
        String jwtSecret,
        Duration accessTokenTtl
) {

    /** 管理面是否启用：username 配了且非空 */
    public boolean enabled() {
        return username != null && !username.isBlank();
    }
}

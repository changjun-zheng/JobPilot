package com.jobpilot.security;

import com.jobpilot.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端认证拦截器（第二认证轴）：<code>/api/v1/admin/**</code> 只认管理令牌。
 * <p>
 * <b>与 {@link AuthInterceptor} 的关键差异：绝不写 {@code UserContext}。</b>
 * 管理员不是租户——一旦写进去，管理请求就能借着租户上下文触达用户数据（拦截器会给业务表
 * 注入「以管理员名冒充的 user_id」）。保持上下文为空时，任何租户表操作会被
 * {@code TenantLineInnerInterceptor} 的 fail-closed 直接炸掉：<b>结构保证，不是约定</b>。
 * <p>
 * 管理面未启用（jobpilot.admin.username 留空）时一律 401——能力缺配置就关闭能力，不炸应用。
 * 登录端点由 {@code WebMvcConfig} 用 {@code excludePathPatterns} 放行（放行规则集中一处，
 * 不在拦截器里手写 URI 判断）。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminJwtService adminJwtService;

    public AdminAuthInterceptor(AdminJwtService adminJwtService) {
        this.adminJwtService = adminJwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!adminJwtService.enabled()) {
            throw new UnauthorizedException("管理面未启用");
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("缺少管理令牌");
        }
        try {
            adminJwtService.verifySubject(header.substring(BEARER_PREFIX.length()).trim());
        } catch (AdminJwtService.InvalidTokenException e) {
            // 含「用户令牌拿来访问管理面」——issuer 对不上，验签失败，同一条 401
            throw new UnauthorizedException("管理令牌无效或已过期");
        }
        return true;
    }
}

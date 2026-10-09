package com.jobpilot.config;

import com.jobpilot.security.AdminAuthInterceptor;
import com.jobpilot.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册认证拦截器并声明放行路径。
 * <p>
 * 用 {@code WebMvcConfigurer} 的路径模式而不是在拦截器内部手写字符串判断：
 * 放行规则集中一处、可配置，且 {@code excludePathPatterns} 是 Spring MVC 既有语义，
 * 不需要自己处理通配符与上下文路径。
 * <p>
 * <b>两条认证轴在此分轨</b>，且路径**互斥**：租户拦截器管 {@code /api/**} 但<b>排除</b>
 * {@code /api/v1/admin/**}（管理员不是租户，租户拦截器不该看到管理请求）；管理拦截器只管
 * {@code /api/v1/admin/**}。互斥意味着注册顺序无关紧要——两条轴不会同时作用于同一请求。
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final SecurityProperties securityProperties;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        AdminAuthInterceptor adminAuthInterceptor,
                        SecurityProperties securityProperties) {
        this.authInterceptor = authInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.securityProperties = securityProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**")
                // 登录端点必须在「还不知道你是谁」时可访问——放行规则集中在这里，不进拦截器手写 URI 判断
                .excludePathPatterns("/api/v1/admin/auth/login")
                // /error 也要放行：它由容器在异常后转发，此时没有业务身份可言；
                // 不放行会让错误响应本身再触发一次 401，掩盖真正的失败原因。
                .excludePathPatterns("/error");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 管理路径由管理拦截器全权负责（第二认证轴）——租户拦截器不看这些请求
                .excludePathPatterns("/api/v1/admin/**")
                .excludePathPatterns(securityProperties.publicPaths())
                .excludePathPatterns("/error");
    }
}

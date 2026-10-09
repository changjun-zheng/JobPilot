package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.security.AdminAuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录（第二认证轴，设计草案 §1#5）。
 * <p>
 * <b>唯一放行的管理路径</b>（见 {@code AdminAuthInterceptor}）：它必须在「还不知道你是谁」时可达。
 * 账号来自配置（{@code jobpilot.admin.*}），不落库——单运营者阶段不建 admin 表。
 * 与用户侧 {@code /api/v1/auth/login} 完全平行：各自的登录、各自的令牌、各自的密钥。
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Validated
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken) {
    }

    /** 管理员登录 → 管理令牌。失败统一 401（不区分账号错还是密码错） */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody @Validated LoginRequest request) {
        return ApiResponse.ok(new TokenResponse(
                adminAuthService.login(request.username(), request.password())));
    }
}

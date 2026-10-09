package com.jobpilot.security;

import com.jobpilot.common.UnauthorizedException;
import com.jobpilot.config.AdminProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端登录（第二认证轴）：凭配置态账号签发管理令牌。
 * <p>
 * 账号来自 {@code jobpilot.admin.*}（.env 注入），不是数据库表——单运营者阶段不建 admin 表
 * （多管理员与操作留痕见设计草案 §10.3）。因此校验是**恒定时间比较**：
 * 先各自 SHA-256 拉平长度再比，避免「长度不同即短路」泄露密码长度。
 * 未启用（username 留空）时一律拒绝——能力缺配置就关闭能力。
 */
@Service
public class AdminAuthService {

    private final AdminProperties props;
    private final AdminJwtService adminJwtService;

    public AdminAuthService(AdminProperties props, AdminJwtService adminJwtService) {
        this.props = props;
        this.adminJwtService = adminJwtService;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * 校验账号密码，通过则签发管理令牌。
     * 用户名或密码任一不符都抛同一条 {@link UnauthorizedException}——不透露是哪一项错。
     */
    public String login(String username, String password) {
        if (!props.enabled()) {
            throw new UnauthorizedException("管理面未启用");
        }
        boolean ok = constantTimeEquals(props.username(), username)
                && constantTimeEquals(props.password(), password);
        if (!ok) {
            throw new UnauthorizedException("管理员账号或密码错误");
        }
        return adminJwtService.issue(props.username().strip());
    }

    /** SHA-256 后比较：等长 + 恒定时间，不泄露长度或前缀 */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(actual));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e); // JDK 必然提供，触达即环境损坏
        }
    }
}

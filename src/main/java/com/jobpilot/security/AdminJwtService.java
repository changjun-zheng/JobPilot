package com.jobpilot.security;

import com.jobpilot.config.AdminProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 管理端 JWT（第二认证轴）：与 {@link JwtService}（租户轴）完全独立——
 * 单独密钥、单独 issuer、单独 token 形状。
 * <p>
 * <b>为什么要两把钥匙而不是加个 role 字段</b>：同一把密钥签出来的令牌在结构上就是「同一种东西」，
 * 任何一处校验遗漏（或未来某个接口忘了查 role）都会让用户令牌直达管理面。两把独立的密钥 +
 * 不同的 issuer 让「管理令牌」与「用户令牌」在**验签层就互不解析**——结构保证，不靠每次调用的自觉。
 * 管理令牌的 subject 是管理员名（不是租户键），因此它**永远不该被写进 {@code UserContext}**。
 * <p>
 * 启用时密钥校验与租户侧同标准（HS256 ≥256 位）；未启用时本 bean 不解析任何东西（401 在拦截器挡下）。
 */
@Component
public class AdminJwtService {

    private static final Logger log = LoggerFactory.getLogger(AdminJwtService.class);

    public static final String ISSUER = "jobpilot-admin";

    private final AdminProperties props;
    private final SecretKey key;
    private final Duration ttl;

    public AdminJwtService(AdminProperties props) {
        this.props = props;
        // 仅启用时构造密钥：未配置 secret 在启动期就暴露，而不是第一次登录时
        if (props.enabled()) {
            if (props.jwtSecret() == null
                    || props.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "jobpilot.admin.jwt-secret 必须配置且不少于 32 字节（管理面启用时）");
            }
            this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
            this.ttl = props.accessTokenTtl() == null ? Duration.ofMinutes(30) : props.accessTokenTtl();
        } else {
            this.key = null;
            this.ttl = Duration.ZERO;
        }
    }

    /** 管理面是否启用（未启用时拦截器直接 401，本类不会被走到签发/验签） */
    public boolean enabled() {
        return props.enabled();
    }

    /** 签发管理令牌：subject 是管理员名；jti 供后续撤销扩展，当前仅保证唯一 */
    public String issue(String adminName) {
        requireEnabled();
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(adminName)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 验签。租户令牌的 issuer 是 {@code jobpilot}，在这里直接解析失败——两轴互认在密码学层就被切断。
     * 任何失败统一抛 {@link InvalidTokenException}，不区分原因（同租户侧：不给攻击者探测器）。
     */
    public String verifySubject(String token) {
        requireEnabled();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException("管理令牌缺少 subject");
            }
            return subject;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("管理令牌无效");
        }
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new InvalidTokenException("管理面未启用");
        }
    }

    /** 管理令牌无效。与 {@link JwtService.InvalidTokenException} 分开：两条轴的失败不该被混捕 */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}

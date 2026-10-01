package com.gateway.admin;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 管理鉴权。
 *
 * 两种凭据：
 * <ol>
 *   <li><b>账号密码登录</b>（主路径）：{@code POST /admin/api/login} 校验用户名 + PBKDF2 口令，
 *       成功后签发 {@link AdminSession} 令牌；后续请求在 {@code X-Admin-Token} 里带上会话令牌。</li>
 *   <li><b>机器令牌</b>（可选）：配置 {@code gateway.admin.token} 后，脚本可直接用它免登录调用；
 *       留空则完全禁用。</li>
 * </ol>
 *
 * 口令用 PBKDF2-HMAC-SHA256（随机盐 + 12 万次迭代）存储于内存，登录时以常量时间比较，
 * 避免时序侧信道。
 */
@Slf4j
@Component
public class AdminAuth {

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final GatewayProperties properties;
    private final AdminSession sessions;
    private final byte[] salt = new byte[SALT_BYTES];
    private final byte[] passwordHash;

    public AdminAuth(GatewayProperties properties, AdminSession sessions) {
        this.properties = properties;
        this.sessions = sessions;
        new SecureRandom().nextBytes(salt);
        this.passwordHash = pbkdf2(properties.getAdmin().getPassword());
    }

    /** 校验用户名 + 口令。 */
    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String expectedUser = properties.getAdmin().getUsername();
        boolean userOk = expectedUser != null
                && MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8),
                        expectedUser.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(pbkdf2(password), passwordHash);
        return userOk & passOk;
    }

    public String createSession(String username) {
        return sessions.create(username);
    }

    public long sessionTtlSeconds() {
        return sessions.ttlSeconds();
    }

    /** 从令牌解析登录用户名（机器令牌返回 "machine"）。 */
    public String usernameOf(String token) {
        String user = sessions.verify(token);
        return user != null ? user : "machine";
    }

    /**
     * 管理接口令牌校验：接受有效会话令牌，或（若配置了）机器令牌。
     * 两者均用常量时间比较，避免时序侧信道。
     */
    public void require(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        if (sessions.verify(token) != null) {
            return;
        }
        String machine = properties.getAdmin().getToken();
        if (machine != null && !machine.isBlank()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        machine.getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        throw unauthorized();
    }

    /** 是否仍在使用默认账号密码（供自检提示）。 */
    public boolean usingDefaultCredentials() {
        return "admin".equals(properties.getAdmin().getUsername())
                && "admin123".equals(properties.getAdmin().getPassword());
    }

    private byte[] pbkdf2(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    (password == null ? "" : password).toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希失败", e);
        }
    }

    private static GatewayException unauthorized() {
        return new GatewayException(ErrorCode.UNAUTHORIZED, "管理会话无效或已过期，请重新登录");
    }
}

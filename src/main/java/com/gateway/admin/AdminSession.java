package com.gateway.admin;

import com.gateway.infra.GatewayProperties;
import com.gateway.infra.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理登录会话：无状态签名令牌（HMAC-SHA256），不依赖 Redis。
 *
 * 令牌格式：{@code base64url(payloadJson) + "." + base64url(hmac)}，
 * payload 为 {@code {"u":"admin","exp":<epoch秒>}}。
 * 签名密钥由 {@code gateway.crypto.master-key} 派生（带域分隔），
 * 因此更换主密钥会导致所有会话失效（需重新登录）。
 *
 * 选择无状态而非 Redis 会话：管理操作低频，签名校验为零网络开销，
 * 且 Redis 故障时仍可登录处理（可用性优先）。
 */
@Component
public class AdminSession {

    private static final String DOMAIN = "gw-admin-session-v1";
    private static final String HMAC = "HmacSHA256";

    private final byte[] key;
    private final long ttlSeconds;

    public AdminSession(GatewayProperties properties) {
        String master = properties.getCrypto().getMasterKey();
        if (master == null || master.isBlank()) {
            throw new IllegalStateException("缺少 gateway.crypto.master-key，无法为管理会话签名");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(master), HMAC));
            // 派生：HMAC(masterKey, DOMAIN)，与渠道密钥加密用途隔离
            this.key = mac.doFinal(DOMAIN.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化管理会话密钥失败", e);
        }
        this.ttlSeconds = Math.max(1, properties.getAdmin().getSessionHours()) * 3600L;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String create(String username) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("u", username);
        payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
        String body = base64Url(JsonSupport.write(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + base64Url(hmac(body));
    }

    /** 校验签名与有效期；失败返回 null。 */
    public String verify(String token) {
        if (token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String body = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (Exception e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(body), actual)) {
            return null;
        }
        try {
            JsonNode json = JsonSupport.mapper().readTree(Base64.getUrlDecoder().decode(body));
            long exp = json.path("exp").asLong(0);
            if (Instant.now().getEpochSecond() > exp) {
                return null;
            }
            String user = json.path("u").asText(null);
            return (user == null || user.isBlank()) ? null : user;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("会话签名失败", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

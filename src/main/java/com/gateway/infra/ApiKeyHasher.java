package com.gateway.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 虚拟 Key 摘要。
 * 只做单向哈希（salt + 明文，SHA-256），库中永不留明文，且哈希必须加盐防彩虹表。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyHasher {

    private final GatewayProperties properties;

    public String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest((properties.getApiKeySalt() + rawKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成展示用前缀，例如 sk-gw-9f2c。 */
    public String prefixOf(String rawKey) {
        int len = Math.min(rawKey.length(), 10);
        return rawKey.substring(0, len);
    }

    /** 常量时间比较，避免时序侧信道。 */
    public boolean matches(String rawKey, String expectedHash) {
        return MessageDigest.isEqual(
                hash(rawKey).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}

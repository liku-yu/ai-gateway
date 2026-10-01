package com.gateway.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 上游 API Key 的对称加解密。
 * 使用 AES-256-GCM，格式：base64(iv(12) || ciphertext || tag)。
 *
 * 安全约定：
 * - 明文只在「即将调用上游」的那一刻解密，且只存在于方法局部变量中；
 * - 解密结果不写日志、不进响应、不进审计；
 * - 主密钥来自 gateway.crypto.master-key（生产环境应由 KMS/环境变量注入）。
 */
@Slf4j
@Component
public class SecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(GatewayProperties properties) {
        String master = properties.getCrypto().getMasterKey();
        if (master == null || master.isBlank()) {
            throw new IllegalStateException("缺少 gateway.crypto.master-key，无法完成渠道密钥加密");
        }
        byte[] raw = Base64.getDecoder().decode(master);
        if (raw.length != 32) {
            throw new IllegalStateException("gateway.crypto.master-key 必须是 32 字节的 Base64（AES-256）");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("渠道密钥加密失败", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 刻意不打印任何密文/明文片段
            log.error("渠道密钥解密失败（密文长度={}），请检查 master-key 是否被更换", encrypted.length());
            throw new IllegalStateException("渠道密钥解密失败", e);
        }
    }

    /** 仅用于脱敏展示：sk-****abcd。 */
    public static String maskTail(String secret) {
        if (secret == null || secret.length() < 8) {
            return "****";
        }
        return secret.substring(0, 3) + "****" + secret.substring(secret.length() - 4);
    }
}

package com.gateway.billing;

import com.gateway.domain.Price;
import com.gateway.infra.GatewayProperties;
import com.gateway.infra.SecretCipher;
import com.gateway.protocol.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 计费与密钥加密的核心逻辑测试。 */
class BillingCoreTest {

    private static final String MASTER_KEY = "ZGV2LW9ubHktbWFzdGVyLWtleS0zMmJ5dGVzISExMjM=";

    private Price price() {
        Price p = new Price();
        p.setProvider("openai");
        p.setModel("gpt-4o-mini");
        p.setInputPrice(new BigDecimal("0.001050"));
        p.setOutputPrice(new BigDecimal("0.004200"));
        p.setCurrency("CNY");
        return p;
    }

    @Test
    @DisplayName("成本 = 输入单价*tokens/1000 + 输出单价*tokens/1000")
    void computesCost() {
        // 1000 prompt + 1000 completion => 0.001050 + 0.004200
        BigDecimal cost = price().cost(Usage.of(1000, 1000));

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.005250"));
    }

    @Test
    @DisplayName("usage 为 null 时成本按 0 计")
    void nullUsageCostsZero() {
        assertThat(price().cost(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("金额换算成「分」时向上取整，避免少扣")
    void roundsUpToFen() {
        BillingService service = new BillingService(null, null);

        assertThat(service.toFen(new BigDecimal("0.000001"))).isEqualTo(1L);
        assertThat(service.toFen(new BigDecimal("1.005"))).isEqualTo(101L);
        assertThat(service.toFen(new BigDecimal("1.000"))).isEqualTo(100L);
    }

    @Test
    @DisplayName("渠道密钥加解密可往返，且密文不含明文")
    void secretCipherRoundTrip() {
        GatewayProperties props = new GatewayProperties();
        props.getCrypto().setMasterKey(MASTER_KEY);
        SecretCipher cipher = new SecretCipher(props);
        String plaintext = "sk-mock-not-a-real-key-0001";

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).doesNotContain(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("相同明文两次加密应产生不同密文（IV 随机）")
    void encryptionIsNonDeterministic() {
        GatewayProperties props = new GatewayProperties();
        props.getCrypto().setMasterKey(MASTER_KEY);
        SecretCipher cipher = new SecretCipher(props);

        assertThat(cipher.encrypt("same-value")).isNotEqualTo(cipher.encrypt("same-value"));
    }

    @Test
    @DisplayName("主密钥长度不合法应在启动阶段就失败（快速暴露配置事故）")
    void rejectsInvalidMasterKey() {
        GatewayProperties props = new GatewayProperties();
        props.getCrypto().setMasterKey("dG9vLXNob3J0");  // 9 字节

        assertThatThrownBy(() -> new SecretCipher(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("密钥展示应脱敏为 sk-****abcd")
    void masksSecretForDisplay() {
        assertThat(SecretCipher.maskTail("sk-abcdefghijklmn")).isEqualTo("sk-****klmn");
        assertThat(SecretCipher.maskTail("short")).isEqualTo("****");
        assertThat(SecretCipher.maskTail(null)).isEqualTo("****");
    }

    @Test
    @DisplayName("虚拟 Key 哈希应加盐且不可逆")
    void apiKeyHashingIsSalted() {
        GatewayProperties props = new GatewayProperties();
        props.setApiKeySalt("salt-a");
        com.gateway.infra.ApiKeyHasher hasherA = new com.gateway.infra.ApiKeyHasher(props);
        String raw = "sk-gw-dev-demo-0001";

        String hashA = hasherA.hash(raw);

        assertThat(hashA).hasSize(64).doesNotContain(raw);
        assertThat(hasherA.matches(raw, hashA)).isTrue();
        assertThat(hasherA.matches("sk-gw-dev-demo-0002", hashA)).isFalse();

        GatewayProperties other = new GatewayProperties();
        other.setApiKeySalt("salt-b");
        assertThat(new com.gateway.infra.ApiKeyHasher(other).hash(raw)).isNotEqualTo(hashA);
    }
}

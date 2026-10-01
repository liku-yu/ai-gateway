package com.gateway.masking;

import com.gateway.domain.MaskingPolicy;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.Message;
import com.gateway.infra.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏引擎测试。
 * 核心断言：**送上游的文本里绝不出现原始 PII**，且占位符语义可读。
 */
class MaskingEngineTest {

    private MaskingEngine engine() {
        GatewayProperties props = new GatewayProperties();
        props.getMasking().setEnabled(true);
        List<MaskingStrategy> strategies = List.of(
                new PhoneMaskingStrategy(),
                new IdCardMaskingStrategy(),
                new EmailMaskingStrategy(),
                new BankCardMaskingStrategy(),
                new ApiKeyMaskingStrategy());
        return new MaskingEngine(strategies, props);
    }

    private MaskingPolicy allPolicy() {
        return new MaskingPolicy(true, List.of("PHONE", "ID_CARD", "EMAIL", "BANK_CARD", "API_KEY"), false);
    }

    @Test
    @DisplayName("手机号应被替换为占位符，且原文不残留")
    void masksPhone() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "我的手机号是13812345678，请帮我查单")));

        MaskResult result = engine.maskChat(req, allPolicy());

        String sent = req.getMessages().get(0).contentAsText();
        assertThat(sent).doesNotContain("13812345678");
        assertThat(sent).contains("[PHONE_1]");
        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.originalOf("[PHONE_1]")).isEqualTo("13812345678");
    }

    @Test
    @DisplayName("身份证校验位不合法时不应误判")
    void ignoresInvalidIdCard() {
        MaskingEngine engine = engine();
        // 长度与格式符合，但校验位错误
        String fakeId = "110101199003071234";  // 校验位应为 3，此处 4 -> 非法
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "编号 " + fakeId)));

        MaskResult result = engine.maskChat(req, allPolicy());

        assertThat(req.getMessages().get(0).contentAsText()).contains(fakeId);
        assertThat(result.hitCount()).isZero();
    }

    @Test
    @DisplayName("合法身份证应被识别")
    void masksValidIdCard() {
        MaskingEngine engine = engine();
        String validId = "110101199003071233";  // 校验位 3，真实合法
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "身份证 " + validId)));

        MaskResult result = engine.maskChat(req, allPolicy());

        assertThat(req.getMessages().get(0).contentAsText()).doesNotContain(validId);
        assertThat(result.hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("银行卡需通过 Luhn 校验，随机数字不应误报")
    void bankCardUsesLuhn() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(
                Message.text("user", "有效卡号 4532015112830366，无效卡号 1234567890123456")));

        engine.maskChat(req, allPolicy());

        String sent = req.getMessages().get(0).contentAsText();
        assertThat(sent).doesNotContain("4532015112830366");
        assertThat(sent).contains("1234567890123456");
    }

    @Test
    @DisplayName("多条消息中的同类 PII 应生成递增占位符")
    void incrementsPlaceholderPerHit() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(
                Message.text("user", "第一个 13812345678"),
                Message.text("assistant", "收到"),
                Message.text("user", "第二个 13987654321")));

        MaskResult result = engine.maskChat(req, allPolicy());

        String all = req.getMessages().stream().map(Message::contentAsText).reduce("", String::concat);
        assertThat(all).contains("[PHONE_1]").contains("[PHONE_2]");
        assertThat(all).doesNotContain("13812345678").doesNotContain("13987654321");
        assertThat(result.hitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("策略关闭时不应改动请求")
    void respectsDisabledPolicy() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "手机号 13812345678")));

        MaskResult result = engine.maskChat(req, new MaskingPolicy(false, null, null));

        assertThat(req.getMessages().get(0).contentAsText()).contains("13812345678");
        assertThat(result.hitCount()).isZero();
    }

    @Test
    @DisplayName("只在策略允许的类型上生效")
    void respectsTypeAllowlist() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "手机 13812345678 邮箱 a@b.com")));

        engine.maskChat(req, new MaskingPolicy(true, List.of("EMAIL"), false));

        String sent = req.getMessages().get(0).contentAsText();
        assertThat(sent).contains("13812345678");
        assertThat(sent).doesNotContain("a@b.com");
    }

    @Test
    @DisplayName("回填应把占位符还原为原文")
    void restoresPlaceholders() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "手机 13812345678")));
        MaskResult result = engine.maskChat(req, allPolicy());

        String restored = engine.restore("我记下了 [PHONE_1]，稍后联系你", result);

        assertThat(restored).isEqualTo("我记下了 13812345678，稍后联系你");
    }

    @Test
    @DisplayName("MaskResult.toString 不应泄露明文")
    void maskResultToStringHidesPlaintext() {
        MaskingEngine engine = engine();
        ChatRequest req = new ChatRequest();
        req.setMessages(List.of(Message.text("user", "手机 13812345678")));
        MaskResult result = engine.maskChat(req, allPolicy());

        assertThat(result.toString()).doesNotContain("13812345678");
    }
}

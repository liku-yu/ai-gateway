package com.gateway.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gateway.protocol.Message;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地 token 估算。
 *
 * 用途仅限**预扣费与实时展示**：最终结算以上游返回的 usage 为准。
 * 对 OpenAI 系用 cl100k_base 精确匹配；通义/DeepSeek 的词表不同，用量级近似（误差可接受，
 * 因为差额会在结算时退还）。
 *
 * 两处性能取舍（都在请求热路径上）：
 * 1. 用**懒加载**注册表，只为 cl100k_base 加载一套词表；默认注册表会把 4 套词表全部载入，
 *    常驻约 70MB 堆（实测 45 万个 ByteArrayWrapper + 91 万个 HashMap$Node）；
 * 2. 分词结果短缓存：系统提示词、固定模板这类文本会被反复计数，缓存能直接省掉纯 CPU 分词。
 */
@Slf4j
@Component
public class TokenEstimator {

    /** 每条消息的固定开销（role + 分隔符），参考 OpenAI 的计数约定。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private static final int REPLY_PRIMING_OVERHEAD = 3;

    /** 超过该长度的文本不参与缓存：既占内存，又几乎不会重复命中。 */
    private static final int MAX_CACHEABLE_CHARS = 64 * 1024;

    private final Cache<String, Integer> tokenCache = Caffeine.newBuilder()
            .maximumSize(4096)
            .build();

    private Encoding encoding;

    @PostConstruct
    void init() {
        try {
            EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
            this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        } catch (Exception e) {
            log.warn("jtokkit 初始化失败，将退化为按字符估算: {}", e.getMessage());
        }
    }

    public int countText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.length() > MAX_CACHEABLE_CHARS) {
            return countTextUncached(text);
        }
        return tokenCache.get(text, this::countTextUncached);
    }

    private int countTextUncached(String text) {
        if (encoding == null) {
            return roughEstimate(text);
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            return roughEstimate(text);
        }
    }

    public int countMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = REPLY_PRIMING_OVERHEAD;
        for (Message m : messages) {
            total += PER_MESSAGE_OVERHEAD + countText(m.contentAsText());
            if (m.getToolCalls() != null) {
                total += countText(m.getToolCalls().toString());
            }
        }
        return total;
    }

    public int countStrings(List<String> texts) {
        if (texts == null) {
            return 0;
        }
        return texts.stream().mapToInt(this::countText).sum();
    }

    /** 兜底估算：中文按 1 字 ≈ 1 token，英文按 4 字符 ≈ 1 token，取保守上界。 */
    private int roughEstimate(String text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + Math.max(1, other / 4);
    }
}

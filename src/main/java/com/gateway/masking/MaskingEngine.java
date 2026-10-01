package com.gateway.masking;

import com.gateway.domain.MaskingPolicy;
import com.gateway.infra.GatewayProperties;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.EmbeddingRequest;
import com.gateway.protocol.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 脱敏引擎：两阶段「检测 -> 替换占位符」。
 *
 * 核心不变量：进入上游的文本里绝不出现原始 PII；占位符保持语义（[PHONE_1]）以尽量不劣化模型效果。
 * 若开启回填，模型回复中出现同一占位符时，网关在返回业务前还原。
 */
@Slf4j
@Component
public class MaskingEngine {

    private final List<MaskingStrategy> strategies;
    private final GatewayProperties properties;

    public MaskingEngine(List<MaskingStrategy> discovered, GatewayProperties properties) {
        // 按类型名排序，保证同一输入下占位符编号可复现（便于排查与测试）
        this.strategies = discovered.stream()
                .sorted(Comparator.comparing(MaskingStrategy::type))
                .collect(Collectors.toList());
        this.properties = properties;
    }

    public Set<String> supportedTypes() {
        return strategies.stream().map(MaskingStrategy::type).collect(Collectors.toSet());
    }

    public boolean isGloballyEnabled() {
        return properties.getMasking().isEnabled();
    }

    /** 对对话请求的所有文本字段做脱敏。 */
    public MaskResult maskChat(ChatRequest request, MaskingPolicy policy) {
        MaskResult result = new MaskResult();
        if (!shouldMask(policy)) {
            return result;
        }
        for (Message message : request.getMessages()) {
            String masked = maskText(message.contentAsText(), policy, result);
            message.setContentText(masked);
        }
        log.debug("脱敏完成: hits={}", result.hitCount());
        return result;
    }

    /** 对向量化请求的输入文本做脱敏。 */
    public MaskResult maskEmbedding(EmbeddingRequest request, MaskingPolicy policy) {
        MaskResult result = new MaskResult();
        if (!shouldMask(policy)) {
            return result;
        }
        List<String> texts = request.inputTexts();
        request.setInputTexts(texts.stream().map(t -> maskText(t, policy, result)).toList());
        return result;
    }

    /** 对任意文本脱敏（用于响应回填前的辅助判断与日志兜底）。 */
    public String maskText(String text, MaskingPolicy policy, MaskResult result) {
        if (text == null || text.isEmpty() || !shouldMask(policy)) {
            return text;
        }
        String current = text;
        for (MaskingStrategy strategy : strategies) {
            if (!policy.has(strategy.type())) {
                continue;
            }
            List<Span> spans = strategy.detect(current);
            if (spans.isEmpty()) {
                continue;
            }
            // 从后往前替换，避免前面的替换影响后面区间的下标
            StringBuilder sb = new StringBuilder(current);
            for (int i = spans.size() - 1; i >= 0; i--) {
                Span span = spans.get(i);
                String placeholder = result.nextPlaceholder(strategy.type());
                result.record(placeholder, span.text());
                sb.replace(span.start(), span.end(), placeholder);
            }
            current = sb.toString();
        }
        return current;
    }

    /**
     * 回填：把模型回复中的占位符还原为原文。
     * 注意：模型可能改写占位符格式，此处只做严格匹配，匹配不上就原样返回（宁可少还原，不可猜错）。
     */
    public String restore(String text, MaskResult result) {
        if (text == null || text.isEmpty() || result == null || result.isEmpty()) {
            return text;
        }
        String current = text;
        for (var entry : result.view().entrySet()) {
            if (current.contains(entry.getKey())) {
                current = current.replace(entry.getKey(), entry.getValue());
            }
        }
        return current;
    }

    public boolean shouldRestore(MaskingPolicy policy) {
        if (policy != null && policy.getRestore() != null) {
            return policy.getRestore();
        }
        return properties.getMasking().isRestorePlaceholders();
    }

    private boolean shouldMask(MaskingPolicy policy) {
        return properties.getMasking().isEnabled() && (policy == null || policy.isEnabled());
    }
}

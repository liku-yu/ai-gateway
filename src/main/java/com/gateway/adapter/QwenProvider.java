package com.gateway.adapter;

import com.gateway.protocol.Capability;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/**
 * 阿里云百炼（DashScope）OpenAI 兼容模式。
 * 与 OpenAI 的差异仅在 baseUrl（渠道级配置），协议本身一致，故复用基类。
 */
@Component
public class QwenProvider extends AbstractOpenAiCompatibleProvider {

    public QwenProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        super(webClient);
    }

    @Override
    public String code() {
        return "qwen";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.TOOL_CALL, Capability.STREAM);
    }
}

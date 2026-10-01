package com.gateway.adapter;

import com.gateway.protocol.Capability;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/**
 * 通用 OpenAI 兼容适配器。
 *
 * 任何「OpenAI 兼容」的供应商（自建 vLLM / LM Studio / 各种中转站）都无需写代码：
 * 在 gw_provider 里配 base_url，并把 adapter_class 设为
 * {@code openai-compatible}（或 {@code custom}/{@code generic}）即可。
 *
 * 与 openai 官方适配器的区别：本适配器不假设任何厂商特性，只做标准 Chat/Embedding 转发，
 * 因此适合作为「接入第三方」的默认落点。
 */
@Component
public class CustomOpenAiProvider extends AbstractOpenAiCompatibleProvider {

    public CustomOpenAiProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        super(webClient);
    }

    @Override
    public String code() {
        return "openai-compatible";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("custom", "generic", "compatible");
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.TOOL_CALL,
                Capability.STREAM, Capability.VISION);
    }
}

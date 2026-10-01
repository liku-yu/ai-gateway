package com.gateway.adapter;

import com.gateway.protocol.Capability;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/** OpenAI 官方，标准协议，直接复用兼容基类。 */
@Component
public class OpenAiProvider extends AbstractOpenAiCompatibleProvider {

    public OpenAiProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        super(webClient);
    }

    @Override
    public String code() {
        return "openai";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.VISION,
                Capability.TOOL_CALL, Capability.STREAM);
    }
}

package com.gateway.adapter;

import com.gateway.protocol.Capability;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/** DeepSeek 官方 API，OpenAI 兼容，复用基类。 */
@Component
public class DeepSeekProvider extends AbstractOpenAiCompatibleProvider {

    public DeepSeekProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        super(webClient);
    }

    @Override
    public String code() {
        return "deepseek";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.TOOL_CALL, Capability.STREAM);
    }
}

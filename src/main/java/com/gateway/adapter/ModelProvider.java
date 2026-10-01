package com.gateway.adapter;

import com.gateway.protocol.Capability;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 上游适配器（策略接口）。
 * 新增一家厂商 = 实现一个类并注册为 Bean，网关其余部分零改动。
 */
public interface ModelProvider {

    /** 适配器标识，与 gw_provider.code 对应。 */
    String code();

    /**
     * 额外别名：让 gw_provider.adapter_class 可以用更直观的名字解析到同一实现，
     * 例如 "openai-compatible"/"custom"/"generic" 都指向通用兼容适配器。
     */
    default java.util.Set<String> aliases() {
        return java.util.Set.of();
    }

    Set<Capability> capabilities();

    /** 非流式对话。 */
    Mono<ChatResponse> chat(UpstreamRequest request);

    /** 流式对话，逐块下发、不聚合缓冲。 */
    Flux<ChatChunk> chatStream(UpstreamRequest request);

    /** 向量化。 */
    Mono<EmbeddingResponse> embedding(UpstreamRequest request);
}

package com.gateway.adapter;

import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.EmbeddingRequest;

/**
 * 一次上游调用所需的全部材料。
 *
 * apiKey 是**短生命周期明文**：仅在适配器构造 HTTP 请求的瞬间存在，
 * 不写日志、不进响应、不进审计，绝不出现在异常信息里。
 */
public record UpstreamRequest(
        String baseUrl,
        String apiKey,
        String physicalModel,
        Integer timeoutMs,
        ChatRequest chatRequest,
        EmbeddingRequest embeddingRequest,
        String traceId,
        String requestId
) {
    public static UpstreamRequest chat(String baseUrl, String apiKey, String physicalModel,
                                       Integer timeoutMs, ChatRequest request,
                                       String traceId, String requestId) {
        return new UpstreamRequest(baseUrl, apiKey, physicalModel, timeoutMs, request, null, traceId, requestId);
    }

    public static UpstreamRequest embedding(String baseUrl, String apiKey, String physicalModel,
                                            Integer timeoutMs, EmbeddingRequest request,
                                            String traceId, String requestId) {
        return new UpstreamRequest(baseUrl, apiKey, physicalModel, timeoutMs, null, request, traceId, requestId);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

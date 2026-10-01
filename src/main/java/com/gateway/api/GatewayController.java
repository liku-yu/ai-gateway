package com.gateway.api;

import com.gateway.filter.RequestContext;
import com.gateway.filter.FilterChainFactory;
import com.gateway.infra.ConfigCache;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.EmbeddingRequest;
import com.gateway.protocol.ModelInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * OpenAI 兼容入口。
 *
 * 业务侧可以直接把 base_url 指到本网关，用现成的 OpenAI SDK 调用，迁移成本 ≈ 0。
 * 网关内部只做协议归一化 + 责任链处理，不做任何业务逻辑。
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class GatewayController {

    private final FilterChainFactory chainFactory;
    private final ConfigCache configCache;

    /** 对话（支持 stream）。 */
    @PostMapping(value = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Mono<org.springframework.http.ResponseEntity<?>> chat(@RequestBody ChatRequest request) {
        return runChain(buildContext(request))
                .flatMap(ctx -> {
                    if (ctx.isStream() && ctx.getStreamFlux() != null) {
                        Flux<ServerSentEvent<String>> sse = ctx.getStreamFlux()
                                .map(chunk -> ServerSentEvent.<String>builder()
                                        .data(com.gateway.infra.JsonSupport.write(chunk))
                                        .build())
                                // OpenAI 客户端约定以 [DONE] 结束
                                .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
                        return Mono.just(org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .header("X-Trace-Id", ctx.getTraceId())
                                .body(sse));
                    }
                    return Mono.just(org.springframework.http.ResponseEntity.ok()
                            .header("X-Trace-Id", ctx.getTraceId())
                            .body(ctx.getResponse()));
                });
    }

    /** 向量化。 */
    @PostMapping(value = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<org.springframework.http.ResponseEntity<?>> embeddings(@RequestBody EmbeddingRequest request) {
        RequestContext ctx = new RequestContext();
        ctx.setKind(RequestContext.Kind.EMBEDDING);
        ctx.setEmbeddingRequest(request);
        ctx.setRequestedModel(request.getModel());
        ctx.setTimeoutMs(request.timeoutMs());
        ctx.setMaxCost(request.maxCost());
        return runChain(ctx).map(c -> org.springframework.http.ResponseEntity.ok()
                .header("X-Trace-Id", c.getTraceId())
                .body(c.getResponse()));
    }

    /** 可用逻辑模型列表（只暴露逻辑名，不泄露上游渠道信息）。 */
    @GetMapping("/models")
    public Mono<org.springframework.http.ResponseEntity<?>> models() {
        long now = Instant.now().getEpochSecond();
        var list = configCache.current().models().values().stream()
                .filter(m -> m.isAvailable())
                .map(m -> new ModelInfo(m.getLogicalName(), "model", now, "ai-gateway",
                        java.util.List.of(m.getType()), m.getType()))
                .toList();
        return Mono.just(org.springframework.http.ResponseEntity.ok(java.util.Map.of("object", "list", "data", list)));
    }

    private RequestContext buildContext(ChatRequest request) {
        RequestContext ctx = new RequestContext();
        ctx.setKind(RequestContext.Kind.CHAT);
        ctx.setChatRequest(request);
        ctx.setRequestedModel(request.getModel());
        ctx.setRequestedModel(request.getModel());
        ctx.setStream(request.isStreaming());
        ctx.setTimeoutMs(request.timeoutMs());
        ctx.setMaxCost(request.maxCost());
        return ctx;
    }

    private Mono<RequestContext> runChain(RequestContext ctx) {
        return chainFactory.execute(ctx);
    }
}

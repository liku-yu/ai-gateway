package com.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.filter.FilterChainFactory;
import com.gateway.filter.RequestContext;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Anthropic Messages 兼容入口。
 *
 * Claude Code、Anthropic SDK 只需把 base URL 指向网关：
 * <pre>
 *   export ANTHROPIC_BASE_URL=http://127.0.0.1:8080
 *   export ANTHROPIC_AUTH_TOKEN=sk-gw-...   # 网关的虚拟 Key
 * </pre>
 * 网关把 Messages 请求翻译成内部 Chat 请求，走同一条责任链（鉴权/限流/计费/脱敏/路由），
 * 再按模型映射路由到任意上游（Anthropic、OpenAI、Gemini…）。
 */
@RestController
@RequestMapping("/v1")
public class AnthropicController {

    private final FilterChainFactory chainFactory;

    public AnthropicController(FilterChainFactory chainFactory) {
        this.chainFactory = chainFactory;
    }

    @PostMapping(value = "/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Mono<ResponseEntity<?>> messages(@RequestBody JsonNode body) {
        ChatRequest chat = AnthropicMessages.toChatRequest(body);
        boolean stream = chat.isStreaming();

        RequestContext ctx = new RequestContext();
        ctx.setKind(RequestContext.Kind.CHAT);
        ctx.setChatRequest(chat);
        ctx.setRequestedModel(chat.getModel());
        ctx.setStream(stream);
        ctx.setTimeoutMs(chat.timeoutMs());
        ctx.setMaxCost(chat.maxCost());

        return chainFactory.execute(ctx).flatMap(c -> {
            if (c.isStream() && c.getStreamFlux() != null) {
                Flux<ServerSentEvent<String>> sse = AnthropicMessages.streamEvents(
                        c.getStreamFlux(), chat.getModel());
                return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .header("X-Trace-Id", c.getTraceId())
                        .body(sse));
            }
            ChatResponse response = (ChatResponse) c.getResponse();
            ObjectNode out = AnthropicMessages.toAnthropicResponse(response, chat.getModel());
            return Mono.just(ResponseEntity.ok()
                    .header("X-Trace-Id", c.getTraceId())
                    .body(out));
        });
    }
}

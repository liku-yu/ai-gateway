package com.gateway.filter;

import com.gateway.infra.TraceIds;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 请求入口：生成 traceId、解析虚拟 Key 与客户端 IP，写入 Reactor Context。
 *
 * 用 Reactor Context 而非 ThreadLocal：WebFlux 中一个请求会跨多个线程，
 * ThreadLocal 会丢上下文，Context 才能可靠贯穿整条响应式链路。
 */
@Component
@Order(-200)
public class RequestContextWebFilter implements WebFilter {

    public static final String RAW_API_KEY_KEY = "rawApiKey";
    public static final String CLIENT_IP_KEY = "clientIp";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = TraceIds.generate();
        String incomingRequestId = exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER);
        String requestId = (incomingRequestId == null || incomingRequestId.isBlank()) ? traceId : incomingRequestId;
        String rawApiKey = extractBearer(exchange);
        String clientIp = resolveClientIp(exchange);

        exchange.getResponse().getHeaders().add(TraceIds.TRACE_HEADER, traceId);
        exchange.getResponse().getHeaders().add(TraceIds.HEADER, requestId);

        return chain.filter(exchange)
                .contextWrite(c -> c
                        .put(TraceIds.CONTEXT_KEY, traceId)
                        .put(TraceIds.REQUEST_ID_CONTEXT_KEY, requestId)
                        .put(RAW_API_KEY_KEY, rawApiKey == null ? "" : rawApiKey)
                        .put(CLIENT_IP_KEY, clientIp));
    }

    private String extractBearer(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            String prefix = "Bearer ";
            if (header.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String token = header.substring(prefix.length()).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        // Anthropic SDK 默认发 x-api-key；兼容它，便于 Claude 系客户端直接把 base URL 指过来
        String apiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? null : remote.getAddress().getHostAddress();
    }
}

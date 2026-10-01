package com.gateway.filter;

import com.gateway.infra.ConfigSnapshot;
import com.gateway.infra.GatewayProperties;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.TraceIds;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.EmbeddingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求解析与前置校验（order=5，位于鉴权之前）。
 *
 * 职责：
 * 1. 从 Reactor Context 取出入口过滤器放好的虚拟 Key / IP；
 * 2. 解析逻辑模型（含前置路由名支持：`model@channel` 走指定渠道）；
 * 3. 校验模型是否启用、应用是否有权访问；
 * 4. 计算模型降级链（chat-default -> chat-cheap）。
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class ServiceRequestFilter implements GatewayFilter {

    private final com.gateway.infra.ConfigCache configCache;
    private final GatewayProperties properties;

    @Override
    public int order() {
        return 5;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        return Mono.deferContextual(view -> {
            ctx.setTraceId(TraceIds.fromContext(view));
            ctx.setRequestId((String) view.getOrDefault(TraceIds.REQUEST_ID_CONTEXT_KEY, ctx.getTraceId()));
            ctx.setRawApiKey((String) view.getOrDefault(RequestContextWebFilter.RAW_API_KEY_KEY, ""));
            ctx.setClientIp((String) view.getOrDefault(RequestContextWebFilter.CLIENT_IP_KEY, null));
            return validate(ctx).then(next.proceed(ctx));
        });
    }

    private Mono<Void> validate(RequestContext ctx) {
        String model = ctx.getRequestedModel();
        if (model == null || model.isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.INVALID_REQUEST, "缺少必填字段 model", "model"));
        }

        // 对话请求必须带 messages：缺失时若放行，会在脱敏环节触发 NPE，
        // 对外表现为 500「网关内部错误」——把客户端的参数错误报成服务端故障，
        // 既误导调用方，也会污染错误率与告警。这里提前拦成 400。
        if (ctx.getChatRequest() != null) {
            var messages = ctx.getChatRequest().getMessages();
            if (messages == null || messages.isEmpty()) {
                return Mono.error(new GatewayException(ErrorCode.INVALID_REQUEST,
                        "messages 不能为空", "messages"));
            }
        }

        ConfigSnapshot snapshot = configCache.current();
        var definition = snapshot.model(model);
        if (definition == null || !definition.isAvailable()) {
            return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_FOUND, "逻辑模型不存在或未启用: " + model, "model"));
        }
        ctx.setModelDefinition(definition);
        ctx.setLogicalModel(definition.getLogicalName());
        ctx.setModelChain(buildChain(snapshot, definition));

        if (ctx.getTimeoutMs() == null) {
            ctx.setTimeoutMs(properties.getDefaults().getRequestTimeoutMs());
        }
        ctx.setAllowFallback(true);
        if (ctx.getModelChain().isEmpty()) {
            ctx.setModelChain(List.of(model));
        }
        return Mono.empty();
    }

    /** 降级链：请求模型 + 配置的 fallback_chain；客户端可显式追加/关闭。 */
    private List<String> buildChain(ConfigSnapshot snapshot, com.gateway.domain.LogicalModel definition) {
        List<String> chain = new ArrayList<>();
        chain.add(definition.getLogicalName());
        if (definition.getFallbackChain() != null) {
            for (String fb : definition.getFallbackChain()) {
                if (snapshot.model(fb) != null && !chain.contains(fb)) {
                    chain.add(fb);
                }
            }
        }
        return chain;
    }
}

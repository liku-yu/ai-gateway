package com.gateway.filter;

import com.gateway.infra.ConfigCache;
import com.gateway.infra.ConfigSnapshot;
import com.gateway.protocol.RoutingHint;
import com.gateway.routing.RoutingStrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由准备（order=50）。
 *
 * 职责边界（重要）：本环节**只做决策准备，不选具体渠道**。
 *
 * 为什么不在这里选渠道：候选集为空时，「换渠道重试」无从谈起，只能落到备用模型，
 * 而「失败 → 换渠道 → 换模型」是一整套协同逻辑，必须和重试预算、冷却、熔断放在一起。
 * 因此渠道选择统一收敛到 {@link UpstreamCallFilter}（经由 UpstreamInvoker），
 * 本环节只负责确定「用哪个策略、按什么模型链去试」。
 */
@Slf4j
@Component
@Order(FilterOrder.ROUTING)
@RequiredArgsConstructor
public class RoutingFilter implements GatewayFilter {

    private final RoutingStrategyRegistry strategies;
    private final ConfigCache configCache;

    @Override
    public int order() {
        return FilterOrder.ROUTING;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        ConfigSnapshot snapshot = configCache.current();
        RoutingHint hint = ctx.getChatRequest() != null ? ctx.getChatRequest().routing()
                : ctx.getEmbeddingRequest().routing();

        ctx.setRoutingStrategy(hint != null && hint.getStrategy() != null
                ? hint.getStrategy() : strategies.defaultCode());

        applyModelChain(ctx, snapshot, hint);

        log.debug("路由准备完成: model={}, strategy={}, 降级链={}",
                ctx.getRequestedModel(), ctx.getRoutingStrategy(), ctx.getModelChain());
        return next.proceed(ctx);
    }

    /** 降级链：业务显式指定 fallback_models 优先，否则用 gw_model.fallback_chain。 */
    private void applyModelChain(RequestContext ctx, ConfigSnapshot snapshot, RoutingHint hint) {
        if (hint != null && Boolean.FALSE.equals(hint.getAllowFallback())) {
            ctx.setModelChain(List.of(ctx.getRequestedModel()));
            return;
        }
        if (hint != null && hint.getFallbackModels() != null && !hint.getFallbackModels().isEmpty()) {
            List<String> chain = new ArrayList<>();
            chain.add(ctx.getRequestedModel());
            hint.getFallbackModels().stream()
                    .filter(m -> snapshot.model(m) != null && !chain.contains(m))
                    .forEach(chain::add);
            ctx.setModelChain(chain);
        }
    }
}

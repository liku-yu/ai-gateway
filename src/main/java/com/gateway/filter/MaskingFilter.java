package com.gateway.filter;

import com.gateway.domain.MaskingPolicy;
import com.gateway.masking.MaskResult;
import com.gateway.masking.MaskingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 入参脱敏（order=40）。
 *
 * 关键约束：脱敏必须发生在路由与调用之前，保证上游模型、日志、审计库
 * **任何一处都见不到原始 PII**。占位符映射只留在内存，请求结束即回收。
 */
@Component
@Order(FilterOrder.MASKING)
@RequiredArgsConstructor
public class MaskingFilter implements GatewayFilter {

    private final MaskingEngine maskingEngine;

    @Override
    public int order() {
        return FilterOrder.MASKING;
    }

    @Override
    public boolean enabled(RequestContext ctx) {
        return maskingEngine.isGloballyEnabled();
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        MaskingPolicy policy = resolvePolicy(ctx);
        ctx.setMaskingPolicy(policy);

        MaskResult result = ctx.getChatRequest() != null
                ? maskingEngine.maskChat(ctx.getChatRequest(), policy)
                : maskingEngine.maskEmbedding(ctx.getEmbeddingRequest(), policy);

        ctx.setMaskResult(result);
        ctx.setMaskedCount(result.hitCount());
        return next.proceed(ctx);
    }

    /** 优先级：请求级 extra_body.masking > 应用级 masking_policy > 全局默认。 */
    private MaskingPolicy resolvePolicy(RequestContext ctx) {
        MaskingPolicy appPolicy = ctx.getApp().getMaskingPolicy();
        var hint = ctx.getChatRequest() != null ? ctx.getChatRequest().masking()
                : ctx.getEmbeddingRequest().masking();

        MaskingPolicy base = appPolicy == null ? new MaskingPolicy(true, null, null) : appPolicy;
        if (hint == null) {
            return base;
        }
        MaskingPolicy merged = new MaskingPolicy(
                hint.getEnabled() != null ? hint.getEnabled() : base.getEnabled(),
                hint.getTypes() != null ? hint.getTypes() : base.getTypes(),
                hint.getRestore() != null ? hint.getRestore() : base.getRestore());
        return merged;
    }
}

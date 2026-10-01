package com.gateway.filter;

import com.gateway.billing.BillingService;
import com.gateway.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 准入控制（order=30）：限流 + 余额预扣 + 预算校验。
 *
 * 三者合并进一段 Lua、一次 Redis 往返完成。这既是性能考量（逐项校验的多次往返
 * 会把吞吐压到 ~750 req/s），也是正确性考量：两阶段脚本保证「要么全放行并全额扣减，
 * 要么完全不动」，不会出现「限流放行了但钱没扣」这类状态。
 *
 * 渠道维度的限流无法在此处判断（此时还不知道会选中哪个渠道），
 * 因此由 {@link UpstreamCallFilter} 在选出渠道后补一次校验。
 */
@Component
@Order(FilterOrder.ADMISSION)
@RequiredArgsConstructor
public class RateLimitFilter implements GatewayFilter {

    private final RateLimiterService rateLimiterService;
    private final BillingService billingService;

    @Override
    public int order() {
        return FilterOrder.ADMISSION;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        int estimatedTokens = PromptTokens.of(ctx, billingService);
        Long preChargeFen = ctx.getQuotaTicket() == null
                ? null : ctx.getQuotaTicket().estimatedFen();
        return rateLimiterService.acquire(ctx.getApp(), ctx.getApiKey(), ctx.getRequestedModel(),
                        null, estimatedTokens, preChargeFen)
                .flatMap(acquired -> {
                    ctx.setConcurrencyKeys(acquired.concurrencyKeys());
                    return next.proceed(ctx);
                });
    }

}

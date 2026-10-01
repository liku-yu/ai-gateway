package com.gateway.filter;

import com.gateway.audit.AuditService;
import com.gateway.billing.BillingService;
import com.gateway.billing.QuotaTicket;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayMetrics;
import com.gateway.protocol.Usage;
import com.gateway.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 请求终结器：结算 + 释放并发 + 日志落库的统一出口。
 *
 * 非流式与流式都走这里，只是触发时机不同：
 * - 非流式：拿到响应后立即结算；
 * - 流式：在流终止（完成/出错/客户端断连）时结算，此时才拿得到 usage。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestFinalizer {

    private final BillingService billingService;
    private final AuditService auditService;
    private final RateLimiterService rateLimiterService;
    private final GatewayMetrics metrics;

    /**
     * 幂等终结入口（**所有路径都必须走这里**）。
     *
     * 责任链的任何一环都可能失败：准入通过之后，脱敏、路由、上游调用、序列化都可能抛错。
     * 若终结逻辑只挂在某一条失败路径上，其他环节抛出的错误就会让责任链直接返回
     * Mono.error，于是：
     *   - gate.lua 已预扣的余额不会退还（资损）；
     *   - concurrency.lua 已占用的并发额度不会释放（泄漏，只能等 300s TTL）。
     * 这两者都是「静默」的，不查 Redis 根本发现不了。
     *
     * 因此把终结收敛成幂等操作，由 FilterChainFactory 在链尾以 doFinally 语义兜底调用，
     * 保证「恰好终结一次」：正常路径先到先得，兜底路径因标记已置位而成为空操作。
     */
    public Mono<Void> finalizeOnce(RequestContext ctx, ErrorCode errorCode) {
        return finalizeRequest(ctx, ctx.getQuotaTicket(), ctx.getUsage(),
                ctx.isPayloadDelivered(), errorCode);
    }

    /**
     * 结算并落库（幂等：同一请求重复调用只有第一次生效）。
     * usage 允许为 null（上游未返回且无法估算），此时按 0 结算并如实记录。
     */
    public Mono<Void> finalizeRequest(RequestContext ctx, QuotaTicket ticket, Usage usage,
                                      boolean success, ErrorCode errorCode) {
        if (!ctx.getFinalized().compareAndSet(false, true)) {
            return Mono.empty();
        }
        if (usage != null) {
            ctx.setUsage(usage);
            if (Boolean.TRUE.equals(usage.getEstimated())) {
                ctx.setUsageEstimated(true);
            }
        }
        ctx.setSuccess(success);
        if (errorCode != null) {
            ctx.setErrorCode(errorCode.name());
        }

        return settle(ctx, ticket)
                .then(rateLimiterService.release(ctx.getConcurrencyKeys()))
                .onErrorResume(e -> {
                    log.debug("释放并发额度失败（忽略）: {}", e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> {
                    recordMetrics(ctx, success, errorCode);
                    auditService.recordRequest(ctx, success, errorCode);
                    auditService.recordUsageHourly(ctx);
                }));
    }

    /**
     * 上报指标。
     * 与日志分开：日志是「事后追溯」，指标是「实时告警」，两者受众与保留期都不同。
     */
    private void recordMetrics(RequestContext ctx, boolean success, ErrorCode errorCode) {
        try {
            String provider = ctx.getChannel() == null || ctx.getChannel().getProvider() == null
                    ? null : ctx.getChannel().getProvider().getCode();
            String channelName = ctx.getChannel() == null ? null : ctx.getChannel().getName();
            String model = ctx.getPhysicalModel() == null ? ctx.getLogicalModel() : ctx.getPhysicalModel();

            metrics.recordRequest(provider, model, channelName, success, ctx.elapsedMs());
            if (ctx.getTtfbMs() != null) {
                metrics.recordTtfb(provider, model, ctx.getTtfbMs());
            }
            if (errorCode != null) {
                metrics.recordError(errorCode.name());
            }
            if (ctx.getUsage() != null) {
                metrics.recordTokens(provider, model,
                        ctx.getUsage().promptOrZero(), ctx.getUsage().completionOrZero());
                metrics.recordCacheTokens(provider, model,
                        ctx.getUsage().cachedOrZero(), ctx.getUsage().cacheCreationOrZero());
            }
            if (ctx.getApp() != null) {
                metrics.recordCost(String.valueOf(ctx.getApp().getTenantId()),
                        String.valueOf(ctx.getApp().getId()), model, ctx.getActualCost());
            }
            if (ctx.getMaskResult() != null && !ctx.getMaskResult().isEmpty()) {
                ctx.getMaskResult().view().keySet().forEach(placeholder -> {
                    int end = placeholder.indexOf('_');
                    if (end > 0) {
                        metrics.recordMaskHit(placeholder.substring(1, end), 1);
                    }
                });
            }
        } catch (Exception e) {
            // 指标上报失败绝不能影响业务响应
            log.debug("指标上报失败（忽略）: {}", e.getMessage());
        }
    }

    private Mono<Void> settle(RequestContext ctx, QuotaTicket ticket) {
        // 票据缺失或预扣为 0 表示本次未动过余额，无需（也不应）写一笔 0 元结算
        if (ticket == null || !ticket.isCharged()) {
            return Mono.empty();
        }
        Usage usage = ctx.getUsage() == null ? new Usage(0, 0, 0, true) : ctx.getUsage();
        return billingService.settle(ticket, usage, ctx.getPrice(),
                        ctx.getApp() == null ? null : ctx.getApp().getId())
                .doOnNext(ctx::setActualCost)
                .onErrorResume(e -> {
                    log.warn("结算失败，已留痕待对账: traceId={}, err={}", ctx.getTraceId(), e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}

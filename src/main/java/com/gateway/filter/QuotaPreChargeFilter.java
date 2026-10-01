package com.gateway.filter;

import com.gateway.billing.BillingService;
import com.gateway.domain.Channel;
import com.gateway.domain.Price;
import com.gateway.infra.ConfigCache;
import com.gateway.infra.ConfigSnapshot;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * 成本预估（order=20，纯 CPU，无任何网络/DB 调用）。
 *
 * 定价在路由确定之前是未知的（不同渠道单价不同），因此取**该模型所有候选渠道中的最高价**
 * 做保守预扣；真正选中的渠道更便宜时，差额会在结算阶段退还。
 *
 * 注意职责边界：本环节只算钱、只校验 max_cost，**不碰 Redis**。
 * 实际的余额扣减与预算校验由紧随其后的准入控制（order=30）一次性完成，
 * 这样即便是「已被限流」的请求也不会白白产生一次去 Redis 的往返。
 */
@Slf4j
@Component
@Order(FilterOrder.COST_ESTIMATE)
@RequiredArgsConstructor
public class QuotaPreChargeFilter implements GatewayFilter {

    private final BillingService billingService;
    private final ConfigCache configCache;

    @Override
    public int order() {
        return FilterOrder.COST_ESTIMATE;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        BigDecimal estimated = estimateCost(ctx);
        if (estimated != null && ctx.getMaxCost() != null && estimated.compareTo(ctx.getMaxCost()) > 0) {
            return Mono.error(new GatewayException(ErrorCode.INVALID_REQUEST,
                    "预估成本 " + estimated + " 超过 max_cost 限制 " + ctx.getMaxCost(), "max_cost"));
        }
        // 只落一个「预估金额」，真正的余额扣减与预算校验在准入控制里一次性完成
        ctx.setPreChargedCost(estimated == null ? BigDecimal.ZERO : estimated);
        ctx.setQuotaTicket(billingService.newTicket(ctx.getApp().getId(),
                estimated == null ? BigDecimal.ZERO : estimated));
        return next.proceed(ctx);
    }

    /**
     * 预估成本：取候选渠道中的最高单价（保守预扣），并顺带记录选中价供结算使用。
     * 若某渠道没有配价，则退化为 0 元（宁可少扣，也不因为缺价目表而拒绝请求）。
     */
    private BigDecimal estimateCost(RequestContext ctx) {
        ConfigSnapshot snapshot = configCache.current();
        Price highest = highestPrice(snapshot, ctx.getRequestedModel());

        if (ctx.getChatRequest() != null) {
            int prompt = PromptTokens.of(ctx, billingService);
            int maxOut = ctx.getChatRequest().effectiveMaxTokens();
            return billingService.estimateCost(highest, prompt, maxOut);
        }
        if (ctx.getEmbeddingRequest() != null) {
            int prompt = PromptTokens.of(ctx, billingService);
            return billingService.estimateCost(highest, prompt, 0);
        }
        return null;
    }

    private Price highestPrice(ConfigSnapshot snapshot, String logicalModel) {
        Price highest = null;
        for (Channel ch : snapshot.candidatesOf(logicalModel)) {
            if (ch.getProvider() == null) {
                continue;
            }
            Price p = snapshot.price(ch.getProvider().getCode(), ch.physicalModel(logicalModel));
            if (p == null) {
                continue;
            }
            if (highest == null || totalPrice(p).compareTo(totalPrice(highest)) > 0) {
                highest = p;
            }
        }
        return highest;
    }

    /** 单价之和：用于「取最贵」的保守比较，缓存读写价也必须纳入。 */
    private static BigDecimal totalPrice(Price p) {
        return nz(p.getInputPrice())
                .add(nz(p.getOutputPrice()))
                .add(nz(p.getCacheReadPrice()))
                .add(nz(p.getCacheWritePrice()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

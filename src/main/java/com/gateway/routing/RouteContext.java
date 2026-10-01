package com.gateway.routing;

import com.gateway.domain.Channel;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 路由决策所需的外部信息。
 * 策略实现只依赖它，不直接碰 Redis/DB，便于单测。
 */
public record RouteContext(
        String logicalModel,
        /** 选择哪个路由策略，null 表示用网关默认策略。 */
        String strategyCode,
        String appIdKey,
        String sessionKey,
        boolean allowFallback,
        BigDecimal maxCost,
        /** 渠道 id -> 当前在途请求数（最少连接策略用）。 */
        Map<Long, Integer> inFlight,
        /** 渠道 id -> 预估成本（成本最优策略用）。 */
        Map<Long, BigDecimal> estimatedCost
) {
    public static RouteContext of(String logicalModel, String strategyCode, String appIdKey, String sessionKey) {
        return new RouteContext(logicalModel, strategyCode, appIdKey, sessionKey, true, null, Map.of(), Map.of());
    }

    public int inFlightOf(Channel ch) {
        return inFlight.getOrDefault(ch.getId(), 0);
    }

    public BigDecimal costOf(Channel ch) {
        return estimatedCost.getOrDefault(ch.getId(), BigDecimal.ZERO);
    }
}

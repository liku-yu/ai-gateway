package com.gateway.routing;

import com.gateway.circuit.CircuitRegistry;
import com.gateway.circuit.CooldownTracker;
import com.gateway.domain.Channel;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.ConfigSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 渠道选择。
 *
 * 候选集过滤顺序（先便宜的后贵的）：
 * 1. 渠道支持该逻辑模型；
 * 2. 渠道状态为 ACTIVE；
 * 3. 不在冷却窗口（Redis，多实例共享）；
 * 4. 熔断器未 OPEN；
 * 5. 未被上游限流。
 * 过滤完再交给路由策略排序/挑选。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Router {

    private final RoutingStrategyRegistry strategies;
    private final CircuitRegistry circuitRegistry;
    private final CooldownTracker cooldownTracker;

    /**
     * 挑选渠道。
     *
     * @param exclude 已被本次请求尝试过并失败的渠道（重试时排除，避免重复踩坑）
     */
    public Mono<Channel> pick(ConfigSnapshot snapshot, String logicalModel, RouteContext ctx, List<Long> exclude) {
        List<Channel> candidates = snapshot.candidatesOf(logicalModel).stream()
                .filter(Channel::isActive)
                .filter(ch -> exclude == null || !exclude.contains(ch.getId()))
                .toList();

        if (candidates.isEmpty()) {
            return Mono.error(new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "逻辑模型 " + logicalModel + " 无可用渠道（可能未配置 Key 或全部被禁用/排除）"));
        }

        return Flux.fromIterable(candidates)
                .filterWhen(ch -> cooldownTracker.isCooling(ch.getId()).map(cooling -> !cooling))
                .filter(ch -> circuitRegistry.allowRequest(ch.getId()))
                .collectList()
                .flatMap(available -> {
                    if (available.isEmpty()) {
                        return Mono.error(new GatewayException(ErrorCode.CIRCUIT_OPEN,
                                "逻辑模型 " + logicalModel + " 的所有候选渠道均在冷却或熔断中"));
                    }
                    RoutingStrategy strategy = strategies.resolve(ctx == null ? null : ctx.strategyCode());
                    Channel selected = strategy.select(available, ctx)
                            .orElseThrow(() -> new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE, "路由策略未能选出渠道"));
                    log.debug("路由选中: model={}, channel={}({}), 候选数={}",
                            logicalModel, selected.getName(), selected.getId(), available.size());
                    return Mono.just(selected);
                });
    }

    /** 供管理接口/探活使用：列出某逻辑模型当前可用的渠道。 */
    public Mono<List<Channel>> availableChannels(ConfigSnapshot snapshot, String logicalModel) {
        return Flux.fromIterable(snapshot.candidatesOf(logicalModel))
                .filter(Channel::isActive)
                .filterWhen(ch -> cooldownTracker.isCooling(ch.getId()).map(c -> !c))
                .filter(ch -> !circuitRegistry.isOpen(ch.getId()))
                .collectList();
    }
}

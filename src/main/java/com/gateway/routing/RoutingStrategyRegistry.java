package com.gateway.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 路由策略注册表：Spring 收集所有实现，按 code 索引，未知策略回退到默认。 */
@Slf4j
@Component
public class RoutingStrategyRegistry {

    private final Map<String, RoutingStrategy> strategies;
    private final String defaultCode;

    public RoutingStrategyRegistry(List<RoutingStrategy> discovered,
                                   com.gateway.infra.GatewayProperties properties) {
        this.strategies = discovered.stream()
                .collect(Collectors.toMap(RoutingStrategy::code, Function.identity(), (a, b) -> a));
        this.defaultCode = properties.getDefaults().getRoutingStrategy();
        log.info("已注册路由策略: {}，默认={}", strategies.keySet(), defaultCode);
    }

    public RoutingStrategy resolve(String code) {
        if (code == null || code.isBlank()) {
            return strategies.getOrDefault(defaultCode, fallback());
        }
        return strategies.getOrDefault(code, strategies.getOrDefault(defaultCode, fallback()));
    }

    public String defaultCode() {
        return defaultCode;
    }

    public List<String> available() {
        return List.copyOf(strategies.keySet());
    }

    private RoutingStrategy fallback() {
        return strategies.values().iterator().next();
    }
}

package com.gateway.circuit;

import com.gateway.infra.GatewayProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按渠道维护熔断器（粒度：channelId）。
 *
 * 状态机：CLOSED -> (失败率超阈值且样本足够) -> OPEN(等待期) -> HALF_OPEN(放行探针) -> CLOSED/OPEN。
 * 与路由联动：OPEN 的渠道会从候选集里被剔除，从而自动切换到备用渠道，业务侧无感。
 */
@Slf4j
@Component
public class CircuitRegistry {

    private final CircuitBreakerRegistry registry;
    private final Map<Long, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitRegistry(GatewayProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .minimumNumberOfCalls(20)
                .slidingWindowSize(50)
                .permittedNumberOfCallsInHalfOpenState(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slowCallRateThreshold(80f)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(com.gateway.adapter.UpstreamException.class,
                        java.util.concurrent.TimeoutException.class,
                        java.io.IOException.class)
                .ignoreExceptions(com.gateway.infra.GatewayException.class)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    public CircuitBreaker breaker(Long channelId) {
        return breakers.computeIfAbsent(channelId, id -> {
            CircuitBreaker cb = registry.circuitBreaker("channel-" + id);
            cb.getEventPublisher().onStateTransition(e ->
                    log.warn("渠道熔断状态变化: channel={}, {} -> {}",
                            id, e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
            return cb;
        });
    }

    public boolean allowRequest(Long channelId) {
        return breaker(channelId).tryAcquirePermission();
    }

    public void onSuccess(Long channelId) {
        breaker(channelId).onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void onError(Long channelId, Throwable error) {
        breaker(channelId).onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, error);
    }

    public boolean isOpen(Long channelId) {
        return breaker(channelId).getState() == CircuitBreaker.State.OPEN;
    }

    public List<String> snapshot() {
        return breakers.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().getState())
                .toList();
    }

    public int openCount() {
        return (int) breakers.values().stream()
                .filter(cb -> cb.getState() == CircuitBreaker.State.OPEN)
                .count();
    }

    /** 人工重置某个渠道的熔断状态（管理接口用）。 */
    public void reset(Long channelId) {
        breaker(channelId).reset();
    }
}

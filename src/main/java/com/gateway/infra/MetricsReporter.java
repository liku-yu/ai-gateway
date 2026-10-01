package com.gateway.infra;

import com.gateway.audit.AsyncLogSink;
import com.gateway.circuit.CircuitRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 状态类指标上报。
 *
 * 为什么单独用定时任务：熔断 OPEN 数、冷却渠道数、日志队列深度都是**瞬时状态**而非事件，
 * 不适合在请求路径里累加；按秒采样一次，Prometheus 用 max/avg 聚合即可。
 * 采集频率是可观测性与 Redis 负载的折中（10s 一次，误差足够告警用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsReporter {

    private final GatewayMetrics metrics;
    private final CircuitRegistry circuitRegistry;
    private final AsyncLogSink logSink;
    private final ReactiveStringRedisTemplate redis;

    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void report() {
        try {
            metrics.updateCircuitOpen(circuitRegistry.openCount());
            metrics.updateLogQueueDepth(logSink.queueSize());
            redis.keys("gw:cool:ch:*")
                    .count()
                    .subscribe(count -> metrics.updateCoolingChannels(count.intValue()),
                            e -> log.debug("统计冷却渠道失败: {}", e.getMessage()));
        } catch (Exception e) {
            log.debug("状态指标上报失败（忽略）: {}", e.getMessage());
        }
    }
}

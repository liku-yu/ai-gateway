package com.gateway.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关自定义指标。
 *
 * 设计原则：
 * 1. **标签基数可控**：维度只用 provider / model / channel / status 这类有限枚举，
 *    绝不用 traceId、requestId、userId 做标签（否则会把 Prometheus 打爆）；
 * 2. **热路径开销极低**：Micrometer 的 Counter/Timer 都是无锁累加，实测纳秒级；
 * 3. 命名遵循 Micrometer 约定（`gateway.` 前缀 + 点分小写），自动转换为 Prometheus 的
 *    `gateway_xxx_total` / `gateway_xxx_seconds`。
 *
 * 覆盖文档第 14 节的监控维度：QPS/TTFB/P99、错误码分布、Token 与成本、
 * 熔断与冷却、限流拦截、脱敏命中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayMetrics {

    private final MeterRegistry registry;

    /** 熔断 OPEN 的渠道数（Gauge 需要持有可变引用）。 */
    private final AtomicInteger openCircuitCount = new AtomicInteger();
    /** 当前处于冷却窗口的渠道数。 */
    private final AtomicInteger coolingChannelCount = new AtomicInteger();
    /** 异步日志队列积压（用于发现日志落库跟不上）。 */
    private final AtomicInteger logQueueDepth = new AtomicInteger();

    private final ConcurrentHashMap<String, Timer> requestTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> rateLimitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> maskCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> fallbackCounters = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void registerGauges() {
        registry.gauge("gateway.circuit.open.channels", openCircuitCount);
        registry.gauge("gateway.channel.cooling.count", coolingChannelCount);
        registry.gauge("gateway.log.queue.depth", logQueueDepth);
        log.info("网关自定义指标已注册");
    }

    // ------------------------------------------------------------------
    // 请求耗时与 TTFB
    // ------------------------------------------------------------------

    /**
     * 记录一次请求的端到端耗时。
     * publishPercentiles 让 Prometheus 直接暴露 P50/P90/P99，无需在查询侧做分位数计算。
     */
    public void recordRequest(String provider, String model, String channel, boolean success, long costMs) {
        timer("gateway.request.duration", provider, model, channel, success)
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /** 首字节时间：流式体验的关键指标，单独度量。 */
    public void recordTtfb(String provider, String model, long ttfbMs) {
        timer("gateway.request.ttfb", provider, model, null, true)
                .record(ttfbMs, TimeUnit.MILLISECONDS);
    }

    private Timer timer(String name, String provider, String model, String channel, boolean success) {
        String key = name + "|" + provider + "|" + model + "|" + channel + "|" + success;
        return requestTimers.computeIfAbsent(key, k -> Timer.builder(name)
                .description("网关请求耗时")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .tag("channel", safe(channel))
                .tag("outcome", success ? "success" : "failure")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry));
    }

    // ------------------------------------------------------------------
    // 错误分布
    // ------------------------------------------------------------------

    /** 按统一错误码统计，用于区分 4xx / 5xx / 429 / 超时。 */
    public void recordError(String errorCode) {
        errorCounters.computeIfAbsent(errorCode, code -> Counter.builder("gateway.request.errors")
                .description("网关请求错误分布")
                .tag("error_code", code)
                .register(registry)).increment();
    }

    public void recordRateLimited(String dimension) {
        rateLimitCounters.computeIfAbsent(dimension, d -> Counter.builder("gateway.ratelimit.rejected")
                .description("限流拦截次数")
                .tag("dimension", d)
                .register(registry)).increment();
    }

    public void recordMaskHit(String type, int count) {
        if (count <= 0) {
            return;
        }
        maskCounters.computeIfAbsent(type, t -> Counter.builder("gateway.masking.hits")
                .description("脱敏命中次数（只统计类型与数量，不含明文）")
                .tag("type", t)
                .register(registry)).increment(count);
    }

    public void recordFallback(String fromModel, String toModel) {
        fallbackCounters.computeIfAbsent(fromModel + "->" + toModel,
                k -> Counter.builder("gateway.fallback.count")
                        .description("模型降级次数")
                        .tag("from_model", safe(fromModel))
                        .tag("to_model", safe(toModel))
                        .register(registry)).increment();
    }

    // ------------------------------------------------------------------
    // 用量与成本
    // ------------------------------------------------------------------

    /** Token 用量按输入/输出分别统计，便于画成本趋势图。 */
    public void recordTokens(String provider, String model, int promptTokens, int completionTokens) {
        DistributionSummary.builder("gateway.tokens.prompt")
                .description("输入 token 用量")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .register(registry)
                .record(promptTokens);
        DistributionSummary.builder("gateway.tokens.completion")
                .description("输出 token 用量")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .register(registry)
                .record(completionTokens);
    }

    /**
     * Prompt Cache 用量：命中（读）与写入分别统计。
     * 只统计 &gt;0 的量，避免给每个请求都注册一个恒为 0 的 meter。
     */
    public void recordCacheTokens(String provider, String model, int cachedTokens, int cacheCreationTokens) {
        if (cachedTokens > 0) {
            DistributionSummary.builder("gateway.tokens.cached")
                    .description("缓存读 token 用量")
                    .tag("provider", safe(provider))
                    .tag("model", safe(model))
                    .register(registry)
                    .record(cachedTokens);
        }
        if (cacheCreationTokens > 0) {
            DistributionSummary.builder("gateway.tokens.cache.write")
                    .description("缓存写 token 用量")
                    .tag("provider", safe(provider))
                    .tag("model", safe(model))
                    .register(registry)
                    .record(cacheCreationTokens);
        }
    }

    /** 成本累计（单位：元）。用 Counter 累计便于按租户/模型做预算告警。 */
    public void recordCost(String tenantId, String appId, String model, BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) {
            return;
        }
        Counter.builder("gateway.cost.total")
                .description("累计成本（元）")
                .tag("tenant", safe(tenantId))
                .tag("app", safe(appId))
                .tag("model", safe(model))
                .register(registry)
                .increment(cost.doubleValue());
    }

    // ------------------------------------------------------------------
    // 状态类指标
    // ------------------------------------------------------------------

    public void updateCircuitOpen(int openCount) {
        openCircuitCount.set(openCount);
    }

    public void updateCoolingChannels(int coolingCount) {
        coolingChannelCount.set(coolingCount);
    }

    public void updateLogQueueDepth(int depth) {
        logQueueDepth.set(depth);
    }

    /** 避免 null 标签值（Micrometer 不接受 null）。 */
    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

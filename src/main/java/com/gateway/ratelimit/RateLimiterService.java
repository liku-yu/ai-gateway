package com.gateway.ratelimit;

import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.domain.Channel;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.GatewayMetrics;
import com.gateway.infra.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 多维度限流编排。
 *
 * 维度（从外到内依次收紧）：虚拟 Key → Key 并发 → 应用 → 单模型 → TPM → 渠道 → 全局。
 *
 * 关键设计：
 * 1. **一次往返**：所有维度合并进一段 Lua 原子执行。逐维度串行调用会产生 N 次 Redis RTT，
 *    高并发下这会成为吞吐瓶颈（实测 50 并发时 P50 从 ~4ms 恶化到 >100ms）；
 * 2. **并发维度需显式释放**：占用的并发键随上下文传递，由 RequestFinalizer 统一释放，
 *    并通过 Lua 中的 TTL 防止客户端异常退出导致的计数泄漏；
 * 3. **渠道维度后置**：渠道的 RPM/TPM/并发都必须等 Router 选出具体渠道后才有键可算，
 *    因此单独提供 {@link #acquireChannelQuota(Channel, int)} 在调用上游前补一道闸门，
 *    三个维度合并进同一次 Lua 往返（不额外增加 RTT）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final int CONCURRENCY_TTL_SECONDS = 300;

    private final RedisLimiter redisLimiter;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;

    /** 需要释放的并发键 + 判定结果。 */
    public record Acquired(List<String> concurrencyKeys, RateLimitDecision decision) {
    }

    /** 维度名，用于指标标签（保持低基数）与错误提示。 */
    private record Dimension(String name, RedisLimiter.LimitSpec spec) {
    }

    public Mono<Acquired> acquire(App app, ApiKey apiKey, String logicalModel, Channel channel, int estimatedTokens) {
        return acquire(app, apiKey, logicalModel, channel, estimatedTokens, null);
    }

    /**
     * 准入控制：限流 + 余额预扣 + 预算校验，合并为一次 Redis 往返。
     *
     * @param preCharge 预扣金额（分），null 或 <=0 表示本次不涉及计费预扣
     */
    public Mono<Acquired> acquire(App app, ApiKey apiKey, String logicalModel, Channel channel,
                                  int estimatedTokens, Long preCharge) {
        List<Dimension> dimensions = buildDimensions(app, apiKey, logicalModel, channel, estimatedTokens);

        // 余额预扣与预算校验并入同一次调用；顺序上放在限流之后，避免为「已被限流」的请求白扣钱
        if (preCharge != null && preCharge > 0 && app != null) {
            dimensions.add(new Dimension("balance",
                    RedisLimiter.LimitSpec.balancePreCharge(BalanceKeys.balance(app.getId()), preCharge)));
        }
        if (app != null && app.getDailyBudget() != null) {
            dimensions.add(new Dimension("budget_daily",
                    RedisLimiter.LimitSpec.budgetCheck(BalanceKeys.daily(app.getId()), toFen(app.getDailyBudget()))));
        }
        if (app != null && app.getMonthlyBudget() != null) {
            dimensions.add(new Dimension("budget_monthly",
                    RedisLimiter.LimitSpec.budgetCheck(BalanceKeys.monthly(app.getId()), toFen(app.getMonthlyBudget()))));
        }

        return redisLimiter.checkAndCharge(dimensions.stream().map(Dimension::spec).toList())
                .flatMap(bulk -> {
                    if (!bulk.allowed()) {
                        String failed = bulk.failedIndex() > 0 && bulk.failedIndex() <= dimensions.size()
                                ? dimensions.get(bulk.failedIndex() - 1).name()
                                : "unknown";
                        metrics.recordRateLimited(failed);
                        // 并发键由 Lua 两阶段保证「全通过才扣减」，因此这里无需回滚
                        return Mono.error(new GatewayException(ErrorCode.RATE_LIMITED,
                                "触发限流（维度：" + failed + "），请 " + bulk.retryAfterSeconds() + "s 后重试",
                                null, null, bulk.retryAfterSeconds()));
                    }
                    List<String> acquiredKeys = new ArrayList<>();
                    dimensions.stream()
                            .filter(d -> d.spec().type() == RedisLimiter.LimitSpec.Type.CONCURRENCY)
                            .forEach(d -> acquiredKeys.add(d.spec().key()));
                    return Mono.just(new Acquired(List.copyOf(acquiredKeys),
                            RateLimitDecision.pass("all", 0, 0)));
                });
    }

    /**
     * 组装各维度规格。
     * 顺序即优先级：排在前面的维度先被校验，失败提示也更贴近真实原因。
     */
    private List<Dimension> buildDimensions(App app, ApiKey apiKey, String logicalModel,
                                            Channel channel, int estimatedTokens) {
        GatewayProperties.Defaults defaults = properties.getDefaults();
        List<Dimension> dims = new ArrayList<>(8);

        if (apiKey != null && apiKey.getRpmLimit() != null) {
            dims.add(new Dimension("api_key",
                    RedisLimiter.LimitSpec.slidingWindow("rl:key:" + apiKey.getId(),
                            apiKey.getRpmLimit(), 60_000)));
        }
        if (apiKey != null && apiKey.getConcurrencyLimit() != null) {
            dims.add(new Dimension("api_key_concurrency",
                    RedisLimiter.LimitSpec.concurrency("rl:conc:key:" + apiKey.getId(),
                            apiKey.getConcurrencyLimit(), CONCURRENCY_TTL_SECONDS)));
        }
        if (app != null) {
            dims.add(new Dimension("app",
                    RedisLimiter.LimitSpec.slidingWindow("rl:app:" + app.getId(),
                            defaults.getAppRpmLimit(), 60_000)));
        }
        if (app != null && logicalModel != null) {
            dims.add(new Dimension("app_model",
                    RedisLimiter.LimitSpec.slidingWindow(
                            "rl:app:" + app.getId() + ":model:" + logicalModel,
                            defaults.getAppModelRpmLimit(), 60_000)));
        }
        if (app != null && estimatedTokens > 0) {
            long tpm = defaults.getAppTpmLimit();
            dims.add(new Dimension("app_tpm",
                    RedisLimiter.LimitSpec.tokenBucket("rl:tpm:app:" + app.getId(),
                            tpm, tpm / 60.0, estimatedTokens)));
        }
        if (channel != null && channel.getRpmLimit() != null) {
            dims.add(new Dimension("channel",
                    RedisLimiter.LimitSpec.slidingWindow("rl:channel:" + channel.getId(),
                            channel.getRpmLimit(), 60_000)));
        }
        dims.add(new Dimension("global",
                RedisLimiter.LimitSpec.slidingWindow("rl:global", defaults.getGlobalRpmLimit(), 60_000)));
        return dims;
    }

    /**
     * 渠道级闸门：并发 + RPM + TPM（在 Router 选出渠道之后调用）。
     *
     * 为什么不能并入准入控制那一次 Lua：那时还不知道会选中哪个渠道。渠道路由由
     * 候选集 + 冷却 + 熔断 + 策略共同决定，只能等选出之后才拿得到渠道键，
     * 所以这里单开一次往返 —— 但三个维度共用这一次，是「正确性优先于极致往返数」的取舍。
     *
     * 失败一律抛 429：它是**网关侧背压**（请求没到上游），调用方应换渠道重试，
     * 但不该计入该渠道的熔断统计（见 UpstreamException#countsTowardCircuit）。
     *
     * @param estimatedTokens 本次请求的 prompt token 估算，用于渠道 TPM 令牌桶预扣。
     *                        与「输入即扣」的既有语义一致：completion 不在预扣内，
     *                        闸门宁可偏松也不误伤正常业务。
     */
    public Mono<Acquired> acquireChannelQuota(Channel channel, int estimatedTokens) {
        if (channel == null) {
            return Mono.just(new Acquired(List.of(), RateLimitDecision.pass("channel", 0, 0)));
        }
        GatewayProperties.Defaults defaults = properties.getDefaults();
        List<Dimension> dims = new ArrayList<>(3);

        boolean concurrencyOn = defaults.isChannelConcurrencyEnabled()
                && channel.getConcurrencyLimit() != null && channel.getConcurrencyLimit() > 0;
        String concurrencyKey = concurrencyOn ? "rl:conc:channel:" + channel.getId() : null;
        if (concurrencyOn) {
            dims.add(new Dimension("channel_concurrency", RedisLimiter.LimitSpec.concurrency(
                    concurrencyKey, channel.getConcurrencyLimit(), CONCURRENCY_TTL_SECONDS)));
        }
        if (defaults.isChannelQuotaEnabled()) {
            if (channel.getRpmLimit() != null && channel.getRpmLimit() > 0) {
                dims.add(new Dimension("channel_rpm", RedisLimiter.LimitSpec.slidingWindow(
                        "rl:channel:" + channel.getId(), channel.getRpmLimit(), 60_000)));
            }
            if (channel.getTpmLimit() != null && channel.getTpmLimit() > 0 && estimatedTokens > 0) {
                long tpm = channel.getTpmLimit();
                dims.add(new Dimension("channel_tpm", RedisLimiter.LimitSpec.tokenBucket(
                        "rl:tpm:channel:" + channel.getId(), tpm, tpm / 60.0, estimatedTokens)));
            }
        }
        if (dims.isEmpty()) {
            return Mono.just(new Acquired(List.of(), RateLimitDecision.pass("channel", 0, 0)));
        }

        return redisLimiter.checkAndCharge(dims.stream().map(Dimension::spec).toList())
                .flatMap(bulk -> {
                    if (!bulk.allowed()) {
                        String failed = bulk.failedIndex() > 0 && bulk.failedIndex() <= dims.size()
                                ? dims.get(bulk.failedIndex() - 1).name() : "channel";
                        metrics.recordRateLimited(failed);
                        return Mono.error(new GatewayException(ErrorCode.RATE_LIMITED,
                                "渠道 " + channel.getName() + " 触发渠道级限流（维度：" + failed + "），请 "
                                        + bulk.retryAfterSeconds() + "s 后重试",
                                null, null, bulk.retryAfterSeconds()));
                    }
                    return Mono.just(new Acquired(
                            concurrencyKey == null ? List.of() : List.of(concurrencyKey),
                            RateLimitDecision.pass("channel", 0, 0)));
                });
    }

    private long toFen(java.math.BigDecimal yuan) {
        return yuan.multiply(java.math.BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
    }

    /** 释放并发占用；由响应完成的 doFinally 调用，保证异常路径也能释放。 */
    public Mono<Void> release(List<String> concurrencyKeys) {
        if (concurrencyKeys == null || concurrencyKeys.isEmpty()) {
            return Mono.empty();
        }
        return reactor.core.publisher.Flux.fromIterable(concurrencyKeys)
                .concatMap(redisLimiter::releaseConcurrency)
                .then();
    }
}

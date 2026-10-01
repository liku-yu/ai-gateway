package com.gateway.ratelimit;

import com.gateway.infra.TraceIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Redis Lua 限流执行器。
 *
 * 所有「判定 + 扣减」都收敛在一段 Lua 里原子完成，避免「先查后扣」的竞态。
 * 脚本加载在启动时一次完成，运行期不读文件。
 */
@Slf4j
@Component
public class RedisLimiter {

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> slidingWindow;
    private final RedisScript<List> tokenBucket;
    private final RedisScript<List> concurrency;
    private final RedisScript<List> releaseConcurrency;
    private final RedisScript<List> gate;

    /** 故障日志节流状态：避免 fail-open 路径刷屏日志反而拖垮吞吐。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 10_000L;
    private final java.util.concurrent.atomic.AtomicLong lastFailureLogAt =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong failureCount =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public RedisLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.slidingWindow = script("lua/sliding_window.lua");
        this.tokenBucket = script("lua/token_bucket.lua");
        this.concurrency = script("lua/concurrency.lua");
        this.releaseConcurrency = script("lua/release_concurrency.lua");
        this.gate = script("lua/gate.lua");
    }

    private static RedisScript<List> script(String path) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        s.setResultType(List.class);
        return s;
    }

    /** 滑动窗口计数：用于 RPM 类「次数」限流。 */
    public Mono<RateLimitDecision> slidingWindow(String key, long limit, long windowMs) {
        String member = TraceIds.generate();
        return redis.execute(slidingWindow, List.of(key),
                        String.valueOf(limit), String.valueOf(windowMs),
                        String.valueOf(System.currentTimeMillis()), member)
                .next()
                .map(result -> {
                    boolean allowed = asLong(result.get(0)) == 1L;
                    long current = asLong(result.get(1));
                    long wait = result.size() > 2 ? asLong(result.get(2)) : 0L;
                    return allowed
                            ? RateLimitDecision.pass(key, current, limit)
                            : RateLimitDecision.reject(key, current, limit, wait);
                })
                .onErrorResume(e -> failOpen(key, "滑动窗口", e));
    }

    /** 令牌桶：用于 TPM 类「按 token 量」限流。 */
    public Mono<RateLimitDecision> tokenBucket(String key, long capacity, double refillPerSec, long cost) {
        return redis.execute(tokenBucket, List.of(key),
                        String.valueOf(capacity), String.valueOf(refillPerSec),
                        String.valueOf(cost), String.valueOf(System.currentTimeMillis()))
                .next()
                .map(result -> {
                    boolean allowed = asLong(result.get(0)) == 1L;
                    long current = asLong(result.get(1));
                    long wait = result.size() > 2 ? asLong(result.get(2)) : 0L;
                    return allowed
                            ? RateLimitDecision.pass(key, current, capacity)
                            : RateLimitDecision.reject(key, current, capacity, wait);
                })
                .onErrorResume(e -> failOpen(key, "令牌桶", e));
    }

    /** 占用一个并发额度（须在请求结束时释放）。 */
    public Mono<RateLimitDecision> acquireConcurrency(String key, long limit, long ttlSeconds) {
        return redis.execute(concurrency, List.of(key), String.valueOf(limit), String.valueOf(ttlSeconds))
                .next()
                .map(result -> {
                    boolean allowed = asLong(result.get(0)) == 1L;
                    long current = asLong(result.get(1));
                    return allowed
                            ? RateLimitDecision.pass(key, current, limit)
                            : RateLimitDecision.reject(key, current, limit, 1000);
                })
                .onErrorResume(e -> failOpen(key, "并发", e));
    }

    /**
     * 释放并发额度。
     * 该脚本返回单个整数（非列表），且不同编解码器可能给出 String / Number / 单元素集合，
     * 因此这里做宽松解析；失败只记日志，因为这是清理动作，不应影响响应结果。
     */
    public Mono<Long> releaseConcurrency(String key) {
        return redis.execute(releaseConcurrency, List.of(key))
                .next()
                .map(RedisLimiter::toLongLoose)
                .onErrorResume(e -> {
                    log.warn("释放并发计数失败 key={}: {}", key, e.getMessage());
                    return Mono.just(0L);
                });
    }

    /**
     * 失败日志节流。
     *
     * 为什么必须节流：fail-open 的设计前提是「限流组件故障时不影响业务」，
     * 但如果每次请求都同步打印一条 ERROR，磁盘 I/O 与日志框架开销会远超限流本身，
     * 结果就是「为了避免拒绝流量，反而把吞吐拖垮」。这里保证：
     * 首次故障立即上报（便于告警），其后按时间窗收敛为每 10 秒最多一条，其余降级为 DEBUG。
     */
    private void logFailureThrottled(String component, String dimension, Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastFailureLogAt.get();
        long total = failureCount.incrementAndGet();
        if (now - last > FAILURE_LOG_INTERVAL_MS && lastFailureLogAt.compareAndSet(last, now)) {
            log.error("{}组件异常，按放行(fail-open)处理（累计 {} 次，后续同类日志每 {}s 最多一条）dim={} key 明细见 DEBUG: {}",
                    component, total, FAILURE_LOG_INTERVAL_MS / 1000, dimension, e.getMessage());
            log.debug("{}组件异常明细", component, e);
        } else {
            log.debug("{}组件异常（已节流，第 {} 次）: {}", component, total, e.getMessage());
        }
    }

    /** 宽松解析：兼容 Number / String / 单元素集合（"[3]" 这类 Base64 解码残留）。 */
    private static long toLongLoose(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String text = raw.toString().trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.isEmpty() ? 0L : Long.parseLong(text);
    }

    /**
     * 限流依赖（Redis）不可用时的策略：放行（fail-open）。
     * 取舍说明：限流是「保护性」能力，若因它本身故障而拒绝所有流量，故障面更大；
     * 因此这里选择放行并打高优先级日志/指标，由熔断与预算做第二道防线。
     */
    private Mono<RateLimitDecision> failOpen(String key, String dimension, Throwable e) {
        logFailureThrottled("单维度限流", dimension, e);
        return Mono.just(RateLimitDecision.pass(key, -1, -1));
    }

    private static long asLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }

    // ==================================================================
    // 规格与结果定义
    // ==================================================================

    /**
     * 单条准入规格：一次 Lua 调用里所有维度共用同一种编码。
     *
     * p2 对令牌桶而言是「每秒补充速率」，可能是小数（如 TPM/60），因此用 double 而非 long，
     * 避免截断导致补充速率偏低、把健康流量误判为超额。
     */
    public record LimitSpec(Type type, String key, long p1, double p2, long p3) {

        /** 类型编码需与 gate.lua 中的分支严格一致。 */
        public enum Type {
            /** 滑动窗口计数（RPM 类）：p1=limit, p2=windowMs。 */
            SLIDING_WINDOW("1"),
            /** 令牌桶（TPM 类）：p1=capacity, p2=refillPerSec, p3=cost。 */
            TOKEN_BUCKET("2"),
            /** 并发计数：p1=limit, p2=ttlSeconds。 */
            CONCURRENCY("3"),
            /** 余额预扣：p1=amountFen。 */
            BALANCE_PRE_CHARGE("4"),
            /** 预算校验（只读）：p1=limitFen。 */
            BUDGET_CHECK("5");

            private final String code;

            Type(String code) {
                this.code = code;
            }

            public String code() {
                return code;
            }
        }

        public static LimitSpec slidingWindow(String key, long limit, long windowMs) {
            return new LimitSpec(Type.SLIDING_WINDOW, key, limit, windowMs, 0);
        }

        public static LimitSpec tokenBucket(String key, long capacity, double refillPerSec, long cost) {
            return new LimitSpec(Type.TOKEN_BUCKET, key, capacity, refillPerSec, cost);
        }

        public static LimitSpec concurrency(String key, long limit, long ttlSeconds) {
            return new LimitSpec(Type.CONCURRENCY, key, limit, ttlSeconds, 0);
        }

        public static LimitSpec balancePreCharge(String key, long amountFen) {
            return new LimitSpec(Type.BALANCE_PRE_CHARGE, key, amountFen, 0, 0);
        }

        public static LimitSpec budgetCheck(String key, long limitFen) {
            return new LimitSpec(Type.BUDGET_CHECK, key, limitFen, 0, 0);
        }
    }

    /** 批量结果：failedIndex 为 1-based 的失败规格下标，0 表示全部通过。 */
    public record BulkDecision(boolean allowed, int failedIndex, long retryAfterMs) {
        public int retryAfterSeconds() {
            return (int) Math.max(1, Math.ceil(retryAfterMs / 1000.0));
        }
    }

    // ==================================================================
    // 准入控制（限流 + 余额 + 预算，一次往返）
    // ==================================================================

    /**
     * 一次性完成「限流校验 + 余额预扣 + 预算校验」。
     *
     * 把「限流校验 + 余额预扣 + 预算校验」合并进同一次 Lua 执行，
     * 使热路径的 Redis 往返从 14 次降到 1 次。失败时不做任何扣减（两阶段 Lua 保证）。
     */
    public Mono<BulkDecision> checkAndCharge(List<LimitSpec> specs) {
        return executeGate(gate, specs);
    }

    private Mono<BulkDecision> executeGate(RedisScript<List> script, List<LimitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return Mono.just(new BulkDecision(true, 0, 0));
        }
        List<String> args = new java.util.ArrayList<>(specs.size() * 5 + 2);
        args.add(String.valueOf(specs.size()));
        for (LimitSpec spec : specs) {
            args.add(spec.type().code());
            args.add(spec.key());
            // 用不带千分位、无科学计数法的十进制字符串传给 Lua（Lua 侧再 tonumber）
            args.add(String.valueOf(spec.p1()));
            args.add(java.math.BigDecimal.valueOf(spec.p2()).toPlainString());
            args.add(String.valueOf(spec.p3()));
        }
        args.add(String.valueOf(System.currentTimeMillis()));

        return redis.execute(script, List.of(), args)
                .next()
                .map(result -> new BulkDecision(
                        asLong(result.get(0)) == 1L,
                        result.size() > 1 ? (int) asLong(result.get(1)) : 0,
                        result.size() > 2 ? asLong(result.get(2)) : 0L))
                .onErrorResume(e -> {
                    // fail-open 是可用性取舍，但绝不能变成日志洪水：
                    // 每请求一条 ERROR 日志会把「限流降级」直接放大成性能事故（实测吞吐腰斩）。
                    logFailureThrottled("准入控制", null, e);
                    return Mono.just(new BulkDecision(true, 0, 0));
                });
    }
}

package com.gateway.ratelimit;

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
 * 结算存储：余额差额调整 + 日/月预算累计，合并为一次 Redis 往返。
 *
 * 键名统一从 {@link BalanceKeys} 取，避免「预扣扣在 A 键、结算退在 B 键」的资损问题。
 */
@Slf4j
@Component
public class SettlementStore {

    private static final long DAY_TTL_SECONDS = 40L * 24 * 3600;
    private static final long MONTH_TTL_SECONDS = 400L * 24 * 3600;

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> settleScript;

    public SettlementStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/settle.lua")));
        s.setResultType(List.class);
        this.settleScript = s;
    }

    /**
     * @param deltaFen 正数=退还，负数=补扣
     * @param costFen  本次实际成本（计入日/月预算；&lt;=0 则不累加）
     * @return [余额, 日累计, 月累计]
     */
    public Mono<long[]> settle(String balanceKey, Long appId, long deltaFen, long costFen, long baselineFen) {
        return execute(balanceKey, appId, deltaFen, costFen, baselineFen);
    }

    /** 不涉及预算累计的结算（例如 appId 缺失的兜底路径）。 */
    public Mono<long[]> settleWithoutBudget(String balanceKey, long deltaFen, long costFen, long baselineFen) {
        return execute(balanceKey, null, deltaFen, costFen, baselineFen);
    }

    private Mono<long[]> execute(String balanceKey, Long appId, long deltaFen, long costFen, long baselineFen) {
        // appId 缺失时用余额键派生一个占位键，避免 Lua 侧 KEYS 数量不一致
        String dayKey = appId == null ? balanceKey + ":nobudget:d" : BalanceKeys.daily(appId);
        String monthKey = appId == null ? balanceKey + ":nobudget:m" : BalanceKeys.monthly(appId);

        return redis.execute(settleScript, List.of(balanceKey, dayKey, monthKey),
                        String.valueOf(deltaFen),
                        String.valueOf(appId == null ? 0 : costFen),
                        String.valueOf(DAY_TTL_SECONDS),
                        String.valueOf(MONTH_TTL_SECONDS),
                        String.valueOf(baselineFen))
                .next()
                .map(result -> new long[]{asLong(result.get(0)), asLong(result.get(1)), asLong(result.get(2))})
                .onErrorResume(e -> {
                    log.warn("结算脚本执行失败（已留痕待对账）: {}", e.getMessage());
                    return Mono.just(new long[]{0, 0, 0});
                });
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
}

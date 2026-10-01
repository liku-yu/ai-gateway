package com.gateway.circuit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gateway.infra.GatewayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道冷却窗口。
 *
 * 与熔断的关系：熔断是「统计意义上的不可用」，冷却是「刚明确失败过，先躲开一小段」。
 * 偶发 429 不足以触发熔断，但足以让该渠道冷却几十秒 —— 两者互补。
 *
 * 两级存储（性能关键）：
 * - 真相来源在 Redis，保证多实例一致（否则 A 实例冷却了、B 实例还在往上打）；
 * - 每个实例另有一份**短 TTL 本地缓存**：冷却判断在每次路由时都要做，
 *   若每请求都问一次 Redis，就会在热路径上多出一次往返。本地缓存以秒级 TTL 兜底，
 *   最坏情况下多打该渠道 1~2 秒，收益是每请求省一次 Redis 往返。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CooldownTracker {

    private static final String KEY_PREFIX = "gw:cool:ch:";
    /** 本地缓存 TTL：足够短以保证及时感知，足够长以吸收高频读。 */
    private static final long LOCAL_TTL_MS = 2000L;

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;

    /** channelId -> 本地记录（过期时间戳 + 原因），仅作快速否定判断。 */
    private final Map<Long, LocalEntry> localCache = new ConcurrentHashMap<>();
    private Cache<Long, Boolean> negativeCache;

    private record LocalEntry(long expireAt, String reason) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    @PostConstruct
    void init() {
        this.negativeCache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofMillis(LOCAL_TTL_MS))
                .build();
    }

    public static String key(Long channelId) {
        return KEY_PREFIX + channelId;
    }

    public Mono<Boolean> cool(Long channelId, String reason) {
        return cool(channelId, reason, properties.getDefaults().getChannelCooldownSeconds());
    }

    public Mono<Boolean> cool(Long channelId, String reason, int seconds) {
        log.warn("渠道进入冷却: channel={}, 时长={}s, 原因={}", channelId, seconds, reason);
        // 先写本地，保证当前实例立即生效（不必等 Redis 往返完成）
        localCache.put(channelId, new LocalEntry(System.currentTimeMillis() + seconds * 1000L, reason));
        return redis.opsForValue()
                .set(key(channelId), reason == null ? "unknown" : reason, Duration.ofSeconds(seconds));
    }

    /**
     * 是否处于冷却中。
     * 快路径：本地缓存命中（含「明确不冷却」的否定缓存）直接返回，零网络开销。
     */
    public Mono<Boolean> isCooling(Long channelId) {
        LocalEntry entry = localCache.get(channelId);
        if (entry != null) {
            if (!entry.expired()) {
                return Mono.just(true);
            }
            localCache.remove(channelId);
        }
        // 短时间内已确认未冷却则直接复用结论，避免每请求都问 Redis
        if (Boolean.TRUE.equals(negativeCache.getIfPresent(channelId))) {
            return Mono.just(false);
        }
        return redis.hasKey(key(channelId))
                .doOnNext(cooling -> {
                    if (!cooling) {
                        negativeCache.put(channelId, true);
                    }
                })
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> release(Long channelId) {
        localCache.remove(channelId);
        negativeCache.invalidate(channelId);
        return redis.delete(key(channelId)).map(v -> v > 0);
    }

    /** 供管理接口展示当前本地已知的冷却渠道。 */
    public int localCoolingCount() {
        return (int) localCache.values().stream().filter(e -> !e.expired()).count();
    }
}

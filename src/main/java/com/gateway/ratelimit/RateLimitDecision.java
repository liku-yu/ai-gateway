package com.gateway.ratelimit;

/** 单次限流判定结果。retryAfterMs 用于回填 Retry-After 建议值。 */
public record RateLimitDecision(boolean allowed, String dimension, long current, long limit, long retryAfterMs) {

    public static RateLimitDecision pass(String dimension, long current, long limit) {
        return new RateLimitDecision(true, dimension, current, limit, 0);
    }

    public static RateLimitDecision reject(String dimension, long current, long limit, long retryAfterMs) {
        return new RateLimitDecision(false, dimension, current, limit, retryAfterMs);
    }

    public int retryAfterSeconds() {
        return (int) Math.max(1, Math.ceil(retryAfterMs / 1000.0));
    }
}

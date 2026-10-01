package com.gateway.admin;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流（防口令爆破）。
 *
 * 规则：同一用户名在 {@link #WINDOW} 内失败达到 {@link #MAX_FAILURES} 次后，
 * 在窗口剩余时间内拒绝继续尝试验证（返回 429），成功登录后清空计数。
 *
 * 内存实现：管理登录频率极低，无需引入 Redis；进程重启即重置，
 * 对「限速爆破」这一目标足够。按用户名分桶，避免一个账号被锁影响其他账号。
 */
@Component
public class LoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long WINDOW_MS = WINDOW.toMillis();

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    /** 尝试前调用：若处于封禁窗口则抛 429。 */
    public void check(String username) {
        String key = key(username);
        Deque<Long> window = failures.get(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            prune(window);
            if (window.size() >= MAX_FAILURES) {
                long retryAfterMs = WINDOW_MS - (System.currentTimeMillis() - window.peekFirst());
                int retryAfter = (int) Math.max(1, Math.ceil(retryAfterMs / 1000.0));
                throw new GatewayException(ErrorCode.RATE_LIMITED,
                        "登录失败次数过多，请 " + retryAfter + " 秒后重试", null, null, retryAfter);
            }
        }
    }

    public void onFailure(String username) {
        String key = key(username);
        Deque<Long> window = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window);
            window.addLast(System.currentTimeMillis());
        }
    }

    public void onSuccess(String username) {
        failures.remove(key(username));
    }

    private static void prune(Deque<Long> window) {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
    }

    private static String key(String username) {
        return username == null || username.isBlank() ? "<empty>" : username.toLowerCase();
    }
}

package com.gateway.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置本地缓存。
 * 刷新触发：管理端变更后发 Redis Pub/Sub 广播；另有定时兜底，防止广播丢失。
 * 读取是纯内存操作，无锁、无网络。
 */
@Slf4j
@Component
public class ConfigCache {

    private final ConfigRepository repository;
    private final AtomicLong version = new AtomicLong();
    private volatile ConfigSnapshot snapshot = ConfigSnapshot.empty();

    public ConfigCache(ConfigRepository repository) {
        this.repository = repository;
    }

    public ConfigSnapshot current() {
        return snapshot;
    }

    /** 启动时先同步加载一次，避免首批请求打空。 */
    public synchronized void loadInitial() {
        reload();
    }

    /** 重新装配配置快照并整体替换。 */
    public synchronized void reload() {
        long next = version.incrementAndGet();
        try {
            ConfigSnapshot fresh = repository.load(next);
            this.snapshot = fresh;
        } catch (Exception e) {
            log.error("配置刷新失败，继续沿用旧快照(version={})", snapshot.version(), e);
        }
    }

    @Scheduled(fixedDelayString = "${gateway.config.refresh-interval:30s}")
    public void scheduledRefresh() {
        reload();
    }
}

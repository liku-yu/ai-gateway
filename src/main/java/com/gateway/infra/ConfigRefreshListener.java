package com.gateway.infra;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 配置热更新监听。
 *
 * 为什么需要它：渠道/Key/定价变更后若只能等定时刷新，最长会有 30s 的空窗期
 * （例如刚禁用某个泄露的 Key，却要等半分钟才生效）。这里用 Redis Pub/Sub 做即时广播，
 * 定时刷新仅作为兜底（防止广播丢失或实例临时离线）。
 *
 * 多实例一致性：广播的是「刷新指令」而非「配置内容」，各实例都从 DB 重新装配，
 * 保证任意时刻所有实例看到的是同一份数据，且不需要在广播里传输敏感配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigRefreshListener {

    public static final String REFRESH_MESSAGE = "refresh";

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final ConfigCache configCache;
    private final GatewayProperties properties;
    private final com.gateway.admin.SettingsService settingsService;

    private ReactiveRedisMessageListenerContainer container;
    private Disposable subscription;

    @PostConstruct
    void subscribe() {
        this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
        this.subscription = container
                .receive(ChannelTopic.of(properties.getConfig().getRedisChannel()))
                .doOnNext(message -> {
                    log.info("收到配置刷新广播，重载配置快照与运行时设置");
                    configCache.reload();
                    // 运行时设置（重试/超时/熔断/限流等）也要一起回到最新值，保证多实例一致
                    settingsService.applyAll();
                })
                .doOnError(e -> log.error("配置刷新订阅异常，将依赖定时兜底刷新", e))
                .subscribe();
        log.info("已订阅配置刷新频道: {}", properties.getConfig().getRedisChannel());
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (container != null) {
            container.destroy();
        }
    }
}

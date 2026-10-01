package com.gateway.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 应用就绪后立即预热配置缓存，保证第一个请求就走内存快照。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayBootstrap {

    private final ConfigCache configCache;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        configCache.loadInitial();
        log.info("AI 网关启动完成，配置版本={}，渠道数={}，虚拟Key数={}",
                configCache.current().version(),
                configCache.current().channelCount(),
                configCache.current().keyCount());
    }
}

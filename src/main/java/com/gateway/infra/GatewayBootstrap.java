package com.gateway.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 应用就绪后立即预热配置缓存，保证第一个请求就走内存快照。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayBootstrap {

    private final ConfigCache configCache;
    private final Environment environment;
    private final com.gateway.admin.SettingsService settingsService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        // 此时日志系统已就绪；放在这里而非 EnvironmentPostProcessor，保证告警一定可见
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            log.warn("已激活 dev profile：正在使用开发默认凭据（admin/admin123、开发主密钥/盐值）。"
                    + "仅限本地开发，切勿用于生产。");
        }
        configCache.loadInitial();
        // 应用数据库中持久化的运行时设置覆盖（若之前改过重试/超时/熔断等）
        settingsService.applyAll();
        log.info("AI 网关启动完成，配置版本={}，渠道数={}，虚拟Key数={}",
                configCache.current().version(),
                configCache.current().channelCount(),
                configCache.current().keyCount());
    }
}

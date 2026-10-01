package com.gateway.filter;

import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.infra.ApiKeyHasher;
import com.gateway.infra.ConfigCache;
import com.gateway.infra.ConfigSnapshot;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 鉴权：虚拟 Key -> 租户/应用。
 *
 * 业务系统只持有虚拟 Key，真实上游 Key 永远不会离开网关，
 * 因此「Key 泄露」的影响面从「直接烧钱」降级为「可在网关侧一键吊销」。
 */
@Slf4j
@Component
@Order(FilterOrder.AUTH)
@RequiredArgsConstructor
public class AuthFilter implements GatewayFilter {

    private final ConfigCache configCache;
    private final ApiKeyHasher hasher;

    @Override
    public int order() {
        return FilterOrder.AUTH;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        String rawKey = ctx.getRawApiKey();
        if (rawKey == null || rawKey.isBlank()) {
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED, "缺少 Authorization: Bearer {虚拟Key}"));
        }

        ConfigSnapshot snapshot = configCache.current();
        String hash = hasher.hash(rawKey);
        ApiKey apiKey = snapshot.keysByHash().get(hash);
        if (apiKey == null) {
            // 不回显 Key 内容，避免把疑似凭据写进日志/错误体
            log.warn("虚拟 Key 校验失败: prefix={}", hasher.prefixOf(rawKey));
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED, "虚拟 Key 无效"));
        }
        if (!apiKey.isAvailable()) {
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED, "虚拟 Key 已被禁用"));
        }
        if (apiKey.isExpired()) {
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED, "虚拟 Key 已过期"));
        }

        App app = snapshot.apps().get(apiKey.getAppId());
        if (app == null || !app.isAvailable()) {
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED, "虚拟 Key 关联的应用不存在或已停用"));
        }

        ctx.setApiKey(apiKey).setApp(app);
        return next.proceed(ctx);
    }
}

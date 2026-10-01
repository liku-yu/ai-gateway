package com.gateway.admin;

import com.gateway.domain.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 传统管理端点（{@code /admin/**}）。
 *
 * 保留给脚本/CI 使用（与 README 早期文档一致）；Web 控制台改用 {@link AdminApiController}
 * 的 {@code /admin/api/**}。两者都委托 {@link AdminService}，行为完全一致。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService admin;
    private final AdminAuth auth;

    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::status);
    }

    @GetMapping("/channels")
    public Mono<List<Map<String, Object>>> channels(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::channelViews);
    }

    @PostMapping("/config/refresh")
    public Mono<Map<String, Object>> refresh(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(() -> Map.of("refreshed", true, "configVersion", admin.refreshConfig()));
    }

    @PutMapping(value = "/channels/{id}/key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateChannelKey(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody KeyUpdateRequest body) {
        auth.require(token);
        return async(() -> {
            Channel channel = admin.setChannelKey(id, body.apiKey(), body.baseUrl(),
                    body.activate() == null || body.activate());
            return Map.of(
                    "channelId", id,
                    "keyMasked", com.gateway.infra.SecretCipher.maskTail(body.apiKey()),
                    "status", channel.getStatus(),
                    "configVersion", admin.status().get("configVersion"));
        });
    }

    @PutMapping(value = "/channels/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateChannelStatus(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody StatusUpdateRequest body) {
        auth.require(token);
        return async(() -> {
            admin.setChannelStatus(id, body.status());
            return Map.of("channelId", id, "status", body.status().toUpperCase());
        });
    }

    @DeleteMapping("/channels/{id}/circuit")
    public Mono<Map<String, Object>> resetCircuit(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(() -> Map.of("channelId", id, "cooldownReleased", admin.resetCircuit(id)));
    }

    private <T> Mono<T> async(Supplier<T> supplier) {
        // MyBatis-Plus 与 ConfigCache.reload 都是阻塞 JDBC，不能跑在 Netty 事件循环上
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }

    public record KeyUpdateRequest(String apiKey, String baseUrl, Boolean activate) {
    }

    public record StatusUpdateRequest(String status) {
    }
}

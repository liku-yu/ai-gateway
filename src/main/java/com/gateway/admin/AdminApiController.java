package com.gateway.admin;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 管理 JSON API（Web 控制台后端）。
 *
 * 路径前缀 {@code /admin/api}，全部需要 {@code X-Admin-Token}。
 * 每个方法都是对 {@link AdminService} 的薄封装；阻塞调用统一切到 boundedElastic，
 * 避免在 Netty 事件循环上执行 MyBatis / 配置重载。
 */
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminApiController {

    private final AdminService admin;
    private final AdminAuth auth;
    private final LoginThrottle loginThrottle;

    // ==================================================================
    // 登录
    // ==================================================================

    /** 账号密码登录，成功后返回会话令牌（后续请求放在 X-Admin-Token 里）。 */
    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody LoginReq body) {
        String username = body == null ? null : body.username();
        loginThrottle.check(username);
        if (body == null || !auth.authenticate(body.username(), body.password())) {
            loginThrottle.onFailure(username);
            throw new GatewayException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        loginThrottle.onSuccess(username);
        String token = auth.createSession(body.username());
        return Mono.just(Map.of(
                "token", token,
                "username", body.username(),
                "expiresInSeconds", auth.sessionTtlSeconds()));
    }

    /** 校验当前会话并返回登录用户名（供控制台判断是否已登录）。 */
    @GetMapping("/me")
    public Mono<Map<String, Object>> me(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return Mono.just(Map.of("username", auth.usernameOf(token)));
    }

    /** 退出登录（会话是无状态签名令牌，服务端无需操作，由客户端丢弃）。 */
    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout() {
        return Mono.just(Map.of("loggedOut", true));
    }

    // ==================================================================
    // 概览 / 自检 / 配置
    // ==================================================================

    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::status);
    }

    @GetMapping("/doctor")
    public Mono<List<AdminService.Check>> doctor(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::doctor);
    }

    @PostMapping("/config/refresh")
    public Mono<Map<String, Object>> refresh(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(() -> Map.of("refreshed", true, "configVersion", admin.refreshConfig()));
    }

    // ==================================================================
    // 供应商
    // ==================================================================

    @GetMapping("/providers")
    public Mono<List<Map<String, Object>>> providers(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::providerViews);
    }

    @PostMapping("/providers")
    public Mono<Map<String, Object>> addProvider(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody ProviderReq body) {
        auth.require(token);
        return async(() -> {
            var p = admin.addProvider(body.code(), body.name(), body.baseUrl(), body.adapter(),
                    Boolean.TRUE.equals(body.disabled()));
            return Map.<String, Object>of("id", p.getId(), "code", p.getCode());
        });
    }

    @PutMapping("/providers/{id}")
    public Mono<Map<String, Object>> updateProvider(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody ProviderReq body) {
        auth.require(token);
        return async(() -> {
            admin.updateProvider(id, body.name(), body.baseUrl(), body.adapter(), body.disabled() == null ? null : !body.disabled());
            return Map.of("id", id, "updated", true);
        });
    }

    @DeleteMapping("/providers/{id}")
    public Mono<Map<String, Object>> deleteProvider(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id) {
        auth.require(token);
        return async(() -> {
            admin.deleteProvider(id);
            return Map.of("id", id, "deleted", true);
        });
    }

    @PostMapping("/channels/{id}/test")
    public Mono<AdminService.TestResult> testProvider(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody(required = false) TestReq body) {
        auth.require(token);
        TestReq req = body == null ? new TestReq(null, null) : body;
        return async(() -> admin.testProvider(id, req.model(), req.timeout() == null ? 30 : req.timeout()));
    }

    // ==================================================================
    // 渠道
    // ==================================================================

    @GetMapping("/channels")
    public Mono<List<Map<String, Object>>> channels(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::channelViews);
    }

    @PostMapping("/channels")
    public Mono<Map<String, Object>> addChannel(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody ChannelReq body) {
        auth.require(token);
        return async(() -> {
            var c = admin.addChannel(body.provider(), body.name(), body.models(), body.baseUrl(),
                    body.weight(), body.priority(), body.rpm(), body.tpm(), body.concurrency(),
                    body.timeoutMs(), body.status());
            return Map.<String, Object>of("id", c.getId(), "status", c.getStatus());
        });
    }

    @PutMapping("/channels/{id}/key")
    public Mono<Map<String, Object>> setChannelKey(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody KeyReq body) {
        auth.require(token);
        return async(() -> {
            var c = admin.setChannelKey(id, body.apiKey(), body.baseUrl(), body.activate() == null || body.activate());
            return Map.<String, Object>of(
                    "channelId", id,
                    "keyMasked", com.gateway.infra.SecretCipher.maskTail(body.apiKey()),
                    "status", c.getStatus(),
                    "configVersion", admin.status().get("configVersion"));
        });
    }

    @PutMapping("/channels/{id}/status")
    public Mono<Map<String, Object>> setChannelStatus(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody StatusReq body) {
        auth.require(token);
        return async(() -> {
            admin.setChannelStatus(id, body.status());
            return Map.of("channelId", id, "status", body.status().toUpperCase());
        });
    }

    @DeleteMapping("/channels/{id}/circuit")
    public Mono<Map<String, Object>> resetCircuit(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id) {
        auth.require(token);
        return async(() -> Map.of("channelId", id, "cooldownReleased", admin.resetCircuit(id)));
    }

    @DeleteMapping("/channels/{id}")
    public Mono<Map<String, Object>> deleteChannel(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id) {
        auth.require(token);
        return async(() -> {
            admin.deleteChannel(id);
            return Map.of("id", id, "deleted", true);
        });
    }

    @PostMapping("/channels/{id}/copy")
    public Mono<Map<String, Object>> copyChannel(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody(required = false) CopyReq body) {
        auth.require(token);
        return async(() -> {
            var c = admin.copyChannel(id, body == null ? null : body.name());
            return Map.<String, Object>of("id", c.getId(), "name", c.getName(), "status", c.getStatus());
        });
    }

    @PostMapping("/channels/test-all")
    public Mono<List<Map<String, Object>>> testAllChannels(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody(required = false) TestReq body) {
        auth.require(token);
        TestReq req = body == null ? new TestReq(null, null) : body;
        return async(() -> admin.testAllChannels(req.model(), req.timeout() == null ? 15 : req.timeout()));
    }

    // ==================================================================
    // 逻辑模型
    // ==================================================================

    @GetMapping("/models")
    public Mono<List<com.gateway.domain.LogicalModel>> models(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::models);
    }

    @PostMapping("/models")
    public Mono<Map<String, Object>> addModel(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody ModelReq body) {
        auth.require(token);
        return async(() -> {
            var m = admin.addModel(body.name(), body.type(), body.description(), body.fallback());
            return Map.<String, Object>of("name", m.getLogicalName(), "type", m.getType());
        });
    }

    @PutMapping("/models/{name}/fallback")
    public Mono<Map<String, Object>> setFallback(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String name,
            @RequestBody FallbackReq body) {
        auth.require(token);
        return async(() -> {
            var m = admin.setFallback(name, body.models());
            return Map.of("name", name,
                    "fallback", m.getFallbackChain() == null ? List.of() : m.getFallbackChain());
        });
    }

    @PutMapping("/models/{name}/status")
    public Mono<Map<String, Object>> setModelStatus(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String name,
            @RequestBody EnabledReq body) {
        auth.require(token);
        return async(() -> {
            admin.setModelEnabled(name, !Boolean.FALSE.equals(body.enabled()));
            return Map.of("name", name, "enabled", !Boolean.FALSE.equals(body.enabled()));
        });
    }

    // ==================================================================
    // 应用
    // ==================================================================

    @GetMapping("/apps")
    public Mono<List<com.gateway.domain.App>> apps(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::apps);
    }

    @PostMapping("/apps")
    public Mono<Map<String, Object>> addApp(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AppReq body) {
        auth.require(token);
        return async(() -> {
            var a = admin.addApp(body.name(), body.tenantId(), body.daily(), body.monthly(), body.models());
            return Map.<String, Object>of("id", a.getId(), "name", a.getName());
        });
    }

    @GetMapping("/apps/{id}/balance")
    public Mono<Map<String, Object>> balance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id) {
        auth.require(token);
        return async(() -> Map.of("appId", id, "balanceFen", admin.balance(id), "balanceYuan",
                java.math.BigDecimal.valueOf(admin.balance(id)).movePointLeft(2)));
    }

    @PostMapping("/apps/{id}/balance")
    public Mono<Map<String, Object>> setBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody BalanceReq body) {
        auth.require(token);
        return async(() -> {
            long fen = body.fen() != null ? body.fen()
                    : (body.yuan() == null ? 0L : body.yuan().movePointRight(2).longValueExact());
            admin.setBalance(id, fen);
            return Map.of("appId", id, "balanceFen", fen);
        });
    }

    @PutMapping("/apps/{id}/status")
    public Mono<Map<String, Object>> setAppStatus(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody EnabledReq body) {
        auth.require(token);
        return async(() -> {
            admin.setAppStatus(id, !Boolean.FALSE.equals(body.enabled()));
            return Map.of("id", id, "enabled", !Boolean.FALSE.equals(body.enabled()));
        });
    }

    // ==================================================================
    // 虚拟 Key
    // ==================================================================

    @GetMapping("/keys")
    public Mono<List<Map<String, Object>>> keys(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) Long appId) {
        auth.require(token);
        return async(() -> admin.keyViews(appId));
    }

    @PostMapping("/keys")
    public Mono<Map<String, Object>> createKey(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody CreateKeyReq body) {
        auth.require(token);
        return async(() -> {
            LocalDate expire = parseDate(body.expire());
            var created = admin.createKey(body.appId(), body.rpm(), body.tpm(), body.concurrency(), expire);
            return Map.of(
                    "id", created.id(),
                    "appId", created.appId(),
                    "apiKey", created.key(),
                    "expireAt", created.expireAt() == null ? "" : created.expireAt().toLocalDate().toString());
        });
    }

    @DeleteMapping("/keys/{id}")
    public Mono<Map<String, Object>> revokeKey(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long id) {
        auth.require(token);
        return async(() -> {
            admin.revokeKey(id);
            return Map.of("id", id, "revoked", true);
        });
    }

    // ==================================================================
    // 定价
    // ==================================================================

    @GetMapping("/prices")
    public Mono<List<com.gateway.domain.Price>> prices(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::prices);
    }

    @PostMapping("/prices")
    public Mono<Map<String, Object>> setPrice(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody PriceReq body) {
        auth.require(token);
        return async(() -> {
            admin.setPrice(body.provider(), body.model(), body.input(), body.output(),
                    body.cacheRead(), body.cacheWrite(), body.currency());
            return Map.of("provider", body.provider(), "model", body.model());
        });
    }

    @DeleteMapping("/prices")
    public Mono<Map<String, Object>> removePrice(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam String provider,
            @RequestParam String model) {
        auth.require(token);
        return async(() -> {
            admin.removePrice(provider, model);
            return Map.of("provider", provider, "model", model, "removed", true);
        });
    }

    // ==================================================================
    // 用量
    // ==================================================================

    @GetMapping("/usage")
    public Mono<Map<String, Object>> usage(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) Long appId,
            @RequestParam(defaultValue = "7") int days) {
        auth.require(token);
        return async(() -> admin.usage(appId, days));
    }

    // ==================================================================
    // 运行时设置（Web 控制台可视化配置）
    // ==================================================================

    @GetMapping("/settings")
    public Mono<List<Map<String, Object>>> settings(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        auth.require(token);
        return async(admin::settings);
    }

    /** 批量更新：请求体 { "values": { "defaults.max-retries": "3", ... } }。 */
    @PutMapping("/settings")
    public Mono<Map<String, Object>> updateSettings(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody SettingsReq body) {
        auth.require(token);
        return async(() -> {
            List<String> updated = admin.updateSettings(body == null ? null : body.values());
            return Map.<String, Object>of("updated", updated);
        });
    }

    /** 恢复某项为 application.yml / 环境变量的默认值。 */
    @DeleteMapping("/settings/{key}")
    public Mono<Map<String, Object>> resetSetting(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String key) {
        auth.require(token);
        return async(() -> {
            admin.resetSetting(key);
            return Map.of("key", key, "reset", true);
        });
    }

    // ==================================================================

    private <T> Mono<T> async(Supplier<T> supplier) {
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "日期格式应为 yyyy-MM-dd: " + value);
        }
    }

    // ==================================================================
    // 请求体
    // ==================================================================

    public record LoginReq(String username, String password) {
    }

    public record SettingsReq(Map<String, String> values) {
    }

    public record ProviderReq(String code, String name, String baseUrl, String adapter, Boolean disabled) {
    }

    public record TestReq(String model, Integer timeout) {
    }

    public record CopyReq(String name) {
    }

    public record ChannelReq(String provider, String name, Map<String, String> models, String baseUrl,
                             Integer weight, Integer priority, Integer rpm, Integer tpm,
                             Integer concurrency, Integer timeoutMs, String status) {
    }

    public record KeyReq(String apiKey, String baseUrl, Boolean activate) {
    }

    public record StatusReq(String status) {
    }

    public record ModelReq(String name, String type, String description, List<String> fallback) {
    }

    public record FallbackReq(List<String> models) {
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record AppReq(String name, Long tenantId, BigDecimal daily, BigDecimal monthly, List<String> models) {
    }

    public record BalanceReq(Long fen, BigDecimal yuan) {
    }

    public record CreateKeyReq(Long appId, Integer rpm, Integer tpm, Integer concurrency, String expire) {
    }

    public record PriceReq(String provider, String model, BigDecimal input, BigDecimal output,
                           BigDecimal cacheRead, BigDecimal cacheWrite, String currency) {
    }
}

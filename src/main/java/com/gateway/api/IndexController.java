package com.gateway.api;

import com.gateway.infra.ConfigCache;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口清单（JSON）。
 *
 * 根路径 `/` 现在由静态 Web 控制台（static/index.html）占用；本接口迁到 `/api/info`，
 * 保留可被脚本/监控消费的入口索引，只暴露路径与所需请求头，不含任何密钥。
 */
@RestController
@RequiredArgsConstructor
public class IndexController {

    private final ConfigCache configCache;

    @GetMapping("/api/info")
    public Mono<Map<String, Object>> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "ai-gateway");
        body.put("message", "AI 网关运行中：/v1 是 OpenAI 兼容入口，/admin 是管理口，/actuator 是观测口");
        body.put("configVersion", configCache.current().version());

        Map<String, String> open = new LinkedHashMap<>();
        open.put("health", "GET /actuator/health");
        open.put("metrics", "GET /actuator/metrics");
        open.put("prometheus", "GET /actuator/prometheus");
        body.put("openEndpoints", open);

        Map<String, String> authorized = new LinkedHashMap<>();
        authorized.put("models", "GET /v1/models（Authorization: Bearer <虚拟Key>）");
        authorized.put("chat", "POST /v1/chat/completions（同一请求头，支持 stream）");
        authorized.put("embeddings", "POST /v1/embeddings（同一请求头）");
        authorized.put("adminStatus", "GET /admin/status（X-Admin-Token: <管理令牌>）");
        authorized.put("adminChannels", "GET /admin/channels（同上，密钥只回掩码）");
        body.put("authorizedEndpoints", authorized);

        body.put("notes", List.of(
                "带鉴权的接口用 curl 或 OpenAI SDK 调用；浏览器直接打开会返回 401。",
                "接口清单与使用方式见仓库 README.md。"));
        return Mono.just(body);
    }
}

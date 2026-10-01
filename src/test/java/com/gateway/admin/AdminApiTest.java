package com.gateway.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * 管理 JSON API 的登录、鉴权与只读端点冒烟测试（Web 控制台后端）。
 * 依赖本机 Redis/MariaDB（与 GatewayEndToEndTest 相同的前提）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AdminApiTest {

    @Autowired
    private WebTestClient webTestClient;

    @SuppressWarnings("unchecked")
    private String loginToken() {
        Map<String, Object> body = webTestClient.post().uri("/admin/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        return (String) body.get("token");
    }

    @Test
    @DisplayName("登录：错误口令 401，正确口令返回会话令牌")
    void login() {
        webTestClient.post().uri("/admin/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "wrong"))
                .exchange().expectStatus().isUnauthorized();

        String token = loginToken();
        org.assertj.core.api.Assertions.assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("同一用户名连续失败会被限流（429）")
    void loginThrottled() {
        // 用独立用户名，避免影响其它用例对 admin 的登录
        for (int i = 0; i < 5; i++) {
            webTestClient.post().uri("/admin/api/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", "nobody", "password", "x"))
                    .exchange().expectStatus().isUnauthorized();
        }
        webTestClient.post().uri("/admin/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nobody", "password", "x"))
                .exchange().expectStatus().isEqualTo(429);
    }

    @Test
    @DisplayName("缺少/错误会话令牌一律 401；有效会话返回用户名")
    void requiresSession() {
        webTestClient.get().uri("/admin/api/status").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/admin/api/status")
                .header("X-Admin-Token", "not-a-valid-session").exchange().expectStatus().isUnauthorized();

        webTestClient.get().uri("/admin/api/me").header("X-Admin-Token", loginToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.username").isEqualTo("admin");
    }

    @Test
    @DisplayName("status 返回适配器与数量")
    void status() {
        webTestClient.get().uri("/admin/api/status").header("X-Admin-Token", loginToken())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.adapters").isArray()
                .jsonPath("$.channels").exists()
                .jsonPath("$.configVersion").exists();
    }

    @Test
    @DisplayName("providers / channels / models / apps / keys / prices 均可读（空表也是数组）")
    void readOnlyResources() {
        String token = loginToken();
        for (String path : new String[]{"/admin/api/providers", "/admin/api/channels",
                "/admin/api/models", "/admin/api/apps", "/admin/api/keys", "/admin/api/prices"}) {
            webTestClient.get().uri(path).header("X-Admin-Token", token)
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$").isArray();
        }
    }

    @Test
    @DisplayName("doctor 返回检查项列表")
    void doctor() {
        webTestClient.get().uri("/admin/api/doctor").header("X-Admin-Token", loginToken())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").exists()
                .jsonPath("$[0].level").exists();
    }
}

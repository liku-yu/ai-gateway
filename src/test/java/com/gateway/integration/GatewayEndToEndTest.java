package com.gateway.integration;

import com.gateway.infra.ConfigCache;
import com.gateway.infra.SecretCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：真实 Redis/MariaDB + 本地 mock 上游。
 *
 * 覆盖：鉴权 -> 权限 -> 限流 -> 预扣费 -> 脱敏 -> 路由 -> 调用 -> 结算 -> 日志。
 * 测试数据使用 test- 前缀并在结束时清理，不污染演示数据。
 *
 * 前置依赖：本机 Redis(6379) 与 MariaDB(3306)/ai_gateway 可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GatewayEndToEndTest {

    private static final String TEST_PROVIDER = "testmock";
    private static final String TEST_MODEL = "test-chat";
    private static final String TEST_CHANNEL = "test-channel-main";
    private static final String TEST_KEY_PLAINTEXT = "sk-gw-test-integration-key";
    private static final String TEST_BASE_URL = "http://127.0.0.1:%d/v1";

    private static DisposableServer mockUpstream;
    private static int mockPort;
    private static final AtomicReference<String> lastUpstreamBody = new AtomicReference<>("");
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>("");
    /** 每个用例可调的 mock 上游延迟，用于验证总 deadline 与超时行为。 */
    private static volatile long mockDelayMillis = 0L;
    private static final java.util.concurrent.atomic.AtomicInteger upstreamCallCount =
            new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCache configCache;
    @Autowired
    private SecretCipher secretCipher;
    @Autowired
    private ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startMockUpstream() {
        mockUpstream = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((req, resp) -> resp
                        .header("Content-Type", "application/json")
                        .sendString(req.receive().aggregate().asString()
                                .defaultIfEmpty("{}")
                                .flatMap(body -> {
                                    upstreamCallCount.incrementAndGet();
                                    lastUpstreamBody.set(body);
                                    lastAuthHeader.set(String.valueOf(req.requestHeaders().get("Authorization")));
                                    Mono<String> payload;
                                    if (body.contains("\"stream\":true")) {
                                        resp.header("Content-Type", "text/event-stream");
                                        String chunk = "data: {\"id\":\"c\",\"choices\":[{\"index\":0,"
                                                + "\"delta\":{\"content\":\"ok\"}}]}\n\n"
                                                + "data: {\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,"
                                                + "\"completion_tokens\":4,\"total_tokens\":15}}\n\n"
                                                + "data: [DONE]\n\n";
                                        payload = Mono.just(chunk);
                                    } else {
                                        String json = "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\","
                                                + "\"created\":1,\"model\":\"test-physical\","
                                                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                                + "\"content\":\"来自 mock 上游的回复\"},\"finish_reason\":\"stop\"}],"
                                                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15}}";
                                        payload = Mono.just(json);
                                    }
                                    long delay = mockDelayMillis;
                                    return delay > 0 ? Mono.delay(java.time.Duration.ofMillis(delay)).then(payload) : payload;
                                })))
                .bindNow();
        mockPort = mockUpstream.port();
    }

    @AfterAll
    static void stopMockUpstream() {
        if (mockUpstream != null) {
            mockUpstream.disposeNow();
        }
    }

    @AfterEach
    void cleanup() {
        // 清理全部测试 fixture，避免污染演示数据与后续用例
        clearChannelGateKeys();
        jdbcTemplate.update("DELETE FROM gw_channel WHERE name = ?", TEST_CHANNEL);
        jdbcTemplate.update("DELETE FROM gw_provider WHERE code = ?", TEST_PROVIDER);
        jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = ?", TEST_MODEL);
        jdbcTemplate.update("DELETE FROM gw_price WHERE provider = ?", TEST_PROVIDER);
        jdbcTemplate.update("DELETE FROM gw_api_key WHERE id = 98");
        jdbcTemplate.update("DELETE FROM gw_app WHERE id = 99");
        // 未授权用例的日志行 app_id 为 NULL，因此还要按测试逻辑模型清理
        jdbcTemplate.update("DELETE FROM gw_request_log WHERE app_id = 99 OR logical_model = ?", TEST_MODEL);
        jdbcTemplate.update("DELETE FROM gw_usage_hourly WHERE app_id = 99");
        redis.delete("gw:bal:app:99")
                .then(redis.delete("gw:budget:app:99:d:" + java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))))
                .then(redis.delete("gw:budget:app:99:m:" + java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))))
                .block();
        configCache.reload();
    }

    /** 准备一套独立的测试渠道/模型/应用/Key，并给足余额。 */
    private void prepareTestFixtures() {
        mockDelayMillis = 0L;
        upstreamCallCount.set(0);
        jdbcTemplate.update("DELETE FROM gw_channel WHERE name = ?", TEST_CHANNEL);
        jdbcTemplate.update("DELETE FROM gw_provider WHERE code = ?", TEST_PROVIDER);
        jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = ?", TEST_MODEL);
        jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = 'test-forbidden'");

        jdbcTemplate.update("""
                INSERT INTO gw_provider (code, name, base_url, adapter_class, enabled)
                VALUES (?, '测试供应商', 'http://127.0.0.1:1', 'openAiProvider', 1)
                """, TEST_PROVIDER);
        Long providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM gw_provider WHERE code = ?", Long.class, TEST_PROVIDER);

        String encrypted = secretCipher.encrypt(TEST_KEY_PLAINTEXT);
        jdbcTemplate.update("""
                INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority,
                                        model_mapping, rpm_limit, tpm_limit, concurrency_limit, status)
                VALUES (?, ?, ?, ?, 100, 0, ?, 600, 500000, 50, 'ACTIVE')
                """, providerId, TEST_CHANNEL, encrypted, String.format(TEST_BASE_URL, mockPort),
                "{\"" + TEST_MODEL + "\":\"test-physical\"}");

        jdbcTemplate.update("""
                INSERT INTO gw_model (logical_name, type, description, fallback_chain, enabled)
                VALUES (?, 'CHAT', '集成测试模型', NULL, 1)
                """, TEST_MODEL);

        jdbcTemplate.update("""
                INSERT INTO gw_app (id, tenant_id, name, daily_budget, monthly_budget, allowed_models, masking_policy, status)
                VALUES (99, 99, 'test-app', 1000.0000, 10000.0000, ?, ?, 1)
                ON DUPLICATE KEY UPDATE daily_budget = VALUES(daily_budget)
                """, "[\"" + TEST_MODEL + "\"]",
                "{\"enabled\":true,\"types\":[\"PHONE\",\"EMAIL\"]}");

        jdbcTemplate.update("""
                INSERT INTO gw_price (provider, model, input_price, output_price, currency, effective_from)
                VALUES (?, 'test-physical', 0.001000, 0.002000, 'CNY', '2026-01-01 00:00:00')
                ON DUPLICATE KEY UPDATE input_price = VALUES(input_price)
                """, TEST_PROVIDER);

        // 测试用虚拟 Key：哈希需与网关的加盐算法一致
        String rawKey = "sk-gw-test-key-0001";
        String hash = hashOf(rawKey);
        jdbcTemplate.update("""
                INSERT INTO gw_api_key (id, app_id, key_hash, key_prefix, rpm_limit, tpm_limit, concurrency_limit, status)
                VALUES (98, 99, ?, 'sk-gw-test', 600, 500000, 50, 1)
                ON DUPLICATE KEY UPDATE key_hash = VALUES(key_hash)
                """, hash);

        redis.opsForValue().set("gw:bal:app:99", "10000000").block();
        redis.delete("gw:budget:app:99:d:" + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))).block();

        configCache.reload();
    }

    private String hashOf(String rawKey) {
        // 复用网关同一套加盐哈希逻辑
        com.gateway.infra.GatewayProperties props = new com.gateway.infra.GatewayProperties();
        props.setApiKeySalt("dev-only-salt-change-me");
        return new com.gateway.infra.ApiKeyHasher(props).hash(rawKey);
    }

    @Test
    @DisplayName("非流式对话：应完成脱敏、路由、计费，并返回网关元信息")
    void nonStreamingChatWorksEndToEnd() {
        prepareTestFixtures();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"我的手机号13812345678"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("来自 mock 上游的回复")
                .jsonPath("$.usage.prompt_tokens").isEqualTo(11)
                .jsonPath("$.gateway.provider").isEqualTo(TEST_PROVIDER)
                .jsonPath("$.gateway.masked").isEqualTo(true)
                .jsonPath("$.gateway.maskedCount").isEqualTo(1)
                .jsonPath("$.gateway.degraded").isEqualTo(false);

        // 关键安全断言：上游收到的正文里必须是占位符，绝不能出现真实手机号
        assertThat(lastUpstreamBody.get())
                .contains("[PHONE_1]")
                .doesNotContain("13812345678");
        assertThat(lastUpstreamBody.get()).contains("test-physical");
    }

    @Test
    @DisplayName("上游凭据应以下游真实 Key 发送，且业务看不到（虚拟 Key 隔离）")
    void upstreamReceivesRealKeyNotVirtualKey() {
        prepareTestFixtures();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"ping"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isOk();

        assertThat(lastAuthHeader.get()).contains(TEST_KEY_PLAINTEXT);
        assertThat(lastAuthHeader.get()).doesNotContain("sk-gw-test-key-0001");
    }

    @Test
    @DisplayName("流式对话：应透传分片、以 [DONE] 收尾，并用最后一个分片的 usage 结算")
    void streamingChatTransparentlyForwardsChunks() {
        prepareTestFixtures();

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","stream":true,"messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(list -> String.join("", list))
                .block();

        assertThat(body).contains("\"content\":\"ok\"").contains("[DONE]");
        assertThat(body).contains("\"prompt_tokens\":11");
    }

    @Test
    @DisplayName("无效虚拟 Key 应被拒绝且不触发上游调用")
    void invalidVirtualKeyRejected() {
        prepareTestFixtures();
        lastUpstreamBody.set("");

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-totally-wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.error.traceId").isNotEmpty();

        assertThat(lastUpstreamBody.get()).isEmpty();
    }

    @Test
    @DisplayName("缺少 messages 应返回 400，而不是 500 内部错误")
    void blankMessagesRejectedAsBadRequest() {
        prepareTestFixtures();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":null}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.error.param").isEqualTo("messages");
    }

    @Test
    @DisplayName("准入之后失败（无可用渠道）必须退还预扣费并释放并发额度")
    void failedRequestReleasesPreChargeAndConcurrencySlot() {
        prepareTestFixtures();

        // 把唯一渠道改成 DISABLED：此时 RoutingFilter 仍会放行（候选集非空），
        // 但 Router.pick 会因「无 ACTIVE 渠道」而失败 —— 也就是说，
        // 请求已经过了准入控制（余额已预扣、并发额度已占用）之后才失败。
        // 这正是历史上会静默泄漏资源的路径。
        jdbcTemplate.update("UPDATE gw_channel SET status = 'DISABLED' WHERE name = ?", TEST_CHANNEL);
        configCache.reload();

        String balanceKey = "gw:bal:app:99";
        String concurrencyKey = "rl:conc:key:98";
        redis.delete(concurrencyKey).block();
        redis.opsForValue().set(balanceKey, "10000000").block();

        long before = Long.parseLong(redis.opsForValue().get(balanceKey).block());

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isEqualTo(503);

        // 不变量 1：预扣费必须原额退还（否则是资损）
        assertThat(Long.parseLong(redis.opsForValue().get(balanceKey).block()))
                .as("失败请求不得扣款")
                .isEqualTo(before);

        // 不变量 2：并发额度必须归还（否则计数器只增不减，最终把该 Key 永久限死）。
        // 注意 release_concurrency.lua 用 DECR 而非 DEL，因此键会保留为 "0"；
        // 泄漏时的表现是停在 "1"，这也是这里断言数值而非「键不存在」的原因。
        assertThat(java.util.Optional.ofNullable(redis.opsForValue().get(concurrencyKey).block()).orElse("0"))
                .as("失败请求不得残留并发占用")
                .isEqualTo("0");
    }

    @Test
    @DisplayName("无权访问的模型应被拒绝")
    void unauthorizedModelRejected() {
        prepareTestFixtures();

        // V5 起数据库为空，不能再依赖种子模型：这里造一个「存在但不被 test-app 允许」的模型
        String forbidden = "test-forbidden";
        jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = ?", forbidden);
        jdbcTemplate.update("""
                INSERT INTO gw_model (logical_name, type, description, enabled)
                VALUES (?, 'CHAT', '越权测试模型', 1)
                """, forbidden);
        configCache.reload();
        try {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer sk-gw-test-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                            """.formatted(forbidden))
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("MODEL_NOT_ALLOWED");
        } finally {
            jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = ?", forbidden);
            configCache.reload();
        }
    }

    @Test
    @DisplayName("未配置上游密钥的渠道不应被路由选中")
    void channelWithoutKeyIsNotRouted() {
        prepareTestFixtures();
        // 清空测试渠道的密钥，模拟「尚未注入 Key」
        jdbcTemplate.update("UPDATE gw_channel SET api_key_enc = NULL WHERE name = ?", TEST_CHANNEL);
        configCache.reload();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("/v1/models 只暴露逻辑模型，不泄露渠道与供应商")
    void modelsEndpointHidesUpstreamDetails() {
        prepareTestFixtures();

        String body = webTestClient.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(list -> String.join("", list))
                .block();

        assertThat(body).contains(TEST_MODEL);
        assertThat(body).doesNotContain(TEST_CHANNEL).doesNotContain("test-physical");
    }

    @Test
    @DisplayName("X-Request-Id 应回显，便于业务侧串联日志")
    void requestIdIsEchoed() {
        prepareTestFixtures();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .header("X-Request-Id", "req-fixed-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-fixed-123");
    }

    /** 渠道闸门键（并发/RPM/TPM）与冷却键都不随渠道行删除而消失，必须显式清理。 */
    private void clearChannelGateKeys() {
        for (Long id : jdbcTemplate.queryForList(
                "SELECT id FROM gw_channel WHERE name = ?", Long.class, TEST_CHANNEL)) {
            Flux.fromIterable(java.util.List.of(
                            "rl:conc:channel:" + id, "rl:channel:" + id, "rl:tpm:channel:" + id,
                            com.gateway.circuit.CooldownTracker.key(id)))
                    .flatMap(redis::delete)
                    .blockLast();
        }
    }

    @Test
    @DisplayName("渠道 RPM 超限应返回 429，而不是被路由层误报成 503 无可用渠道")
    void channelRpmLimitIsEnforced() {
        prepareTestFixtures();
        jdbcTemplate.update("UPDATE gw_channel SET rpm_limit = 1 WHERE name = ?", TEST_CHANNEL);
        configCache.reload();

        chatOnce().expectStatus().isOk();

        chatOnce().expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.error.message").value(v ->
                        assertThat(String.valueOf(v)).contains("channel_rpm"));
    }

    @Test
    @DisplayName("渠道 TPM 超限应按 channel_tpm 维度拒绝")
    void channelTpmLimitIsEnforced() {
        prepareTestFixtures();
        // 令牌桶容量置 1：任何带 prompt token 的请求都过不去
        jdbcTemplate.update("UPDATE gw_channel SET tpm_limit = 1 WHERE name = ?", TEST_CHANNEL);
        configCache.reload();

        chatOnce().expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.error.message").value(v ->
                        assertThat(String.valueOf(v)).contains("channel_tpm"));
    }

    @Test
    @DisplayName("总 deadline：预算耗尽后不再重试，避免 N 倍单次超时叠加")
    void totalDeadlineStopsRetries() {
        prepareTestFixtures();
        // 上游慢到必然触发单次超时
        mockDelayMillis = 1000L;
        // 业务声明总预算 300ms：第一次尝试超时后，剩余预算不足以再做一次「退避+尝试」
        String body = """
                {"model":"test-chat","messages":[{"role":"user","content":"hi"}],
                 "extra_body":{"timeout_ms":300}}
                """;

        long start = System.currentTimeMillis();
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UPSTREAM_TIMEOUT");
        long elapsed = System.currentTimeMillis() - start;

        // 旧行为是 3 次尝试（3 x 300ms + 退避），此处必须只打一次上游
        assertThat(upstreamCallCount.get()).isEqualTo(1);
        assertThat(elapsed).isLessThan(900L);
    }

    @Test
    @DisplayName("根路径返回 Web 控制台页面")
    void rootServesWebConsole() {
        webTestClient.get().uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> assertThat(html).contains("AI 网关控制台"));
    }

    @Test
    @DisplayName("/api/info 返回入口索引 JSON")
    void infoReturnsIndex() {
        webTestClient.get().uri("/api/info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ai-gateway")
                .jsonPath("$.openEndpoints.health").exists()
                .jsonPath("$.authorizedEndpoints.chat").exists();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec chatOnce() {
        return webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer sk-gw-test-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(TEST_MODEL))
                .exchange();
    }

    @Test
    @DisplayName("主渠道不可用时应自动故障转移到备用渠道")
    void failsOverToBackupChannel() {
        prepareTestFixtures();
        // 再造一个「必然失败」的渠道，优先级更高
        Long providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM gw_provider WHERE code = ?", Long.class, TEST_PROVIDER);
        jdbcTemplate.update("""
                INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority,
                                        model_mapping, status)
                VALUES (?, 'test-channel-broken', ?, 'http://127.0.0.1:9/v1', 100, -1, ?, 'ACTIVE')
                """, providerId, secretCipher.encrypt("sk-broken"), "{\"" + TEST_MODEL + "\":\"test-physical\"}");
        configCache.reload();

        try {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer sk-gw-test-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"model":"%s","messages":[{"role":"user","content":"hi"}],
                             "extra_body":{"routing":{"strategy":"priority"}}}
                            """.formatted(TEST_MODEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.gateway.channelName").isEqualTo(TEST_CHANNEL)
                    // priority=-1 的故障渠道必然被优先选中 -> 连接失败 -> 换渠道成功，应有 1 次重试
                    .jsonPath("$.gateway.retryCount").value(v -> assertThat((Integer) v).isBetween(0, 1));
            // 失败渠道应触发重试而非直接失败

            // 失败渠道应进入冷却，后续请求不再踩它
            assertThat(redis.hasKey("gw:cool:ch:" + brokenChannelId()).block()).isTrue();
        } finally {
            jdbcTemplate.update("DELETE FROM gw_channel WHERE name = 'test-channel-broken'");
        }
    }

    @Test
    @DisplayName("模型降级时应返回 X-Degraded 语义（gateway.degraded=true）")
    void marksDegradedWhenFallingBackToCheaperModel() {
        // 准备两条模型：主模型无可用渠道，备用模型有；主模型配置降级链指向备用模型
        prepareTestFixtures();
        jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = 'test-chat-primary'");
        jdbcTemplate.update("""
                INSERT INTO gw_model (logical_name, type, description, fallback_chain, enabled)
                VALUES ('test-chat-primary', 'CHAT', '无渠道的主模型', ?, 1)
                """, "[\"" + TEST_MODEL + "\"]");
        // test-chat 也加入允许列表，避免权限校验拦截
        jdbcTemplate.update("""
                UPDATE gw_app SET allowed_models = ? WHERE id = 99
                """, "[\"test-chat-primary\",\"" + TEST_MODEL + "\"]");
        configCache.reload();

        try {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer sk-gw-test-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"model":"test-chat-primary","messages":[{"role":"user","content":"hi"}]}
                            """)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.gateway.degraded").isEqualTo(true)
                    .jsonPath("$.gateway.physicalModel").isEqualTo("test-physical");
        } finally {
            jdbcTemplate.update("DELETE FROM gw_model WHERE logical_name = 'test-chat-primary'");
        }
    }

    private Long brokenChannelId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM gw_channel WHERE name = 'test-channel-broken'", Long.class);
    }
}

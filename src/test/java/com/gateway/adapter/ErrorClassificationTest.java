package com.gateway.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游错误分类测试。
 * 分类直接决定「是否换渠道重试」「是否熔断」，是可用性的关键路径，必须锁住语义。
 */
class ErrorClassificationTest {

    @Test
    @DisplayName("429 限流应归类为可重试")
    void tooManyRequestsIsRetryable() {
        UpstreamException e = UpstreamException.from(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"rate limit\"}}");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.RETRYABLE);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    @DisplayName("5xx 服务端错误应可重试")
    void serverErrorIsRetryable() {
        assertThat(UpstreamException.from(HttpStatus.INTERNAL_SERVER_ERROR, "boom").retryable()).isTrue();
        assertThat(UpstreamException.from(HttpStatus.BAD_GATEWAY, "bad gw").retryable()).isTrue();
    }

    @Test
    @DisplayName("401 鉴权失败不可重试（重试只是浪费）")
    void unauthorizedIsNotRetryable() {
        UpstreamException e = UpstreamException.from(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"invalid key\"}}");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.NON_RETRYABLE);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    @DisplayName("余额不足应归类为配额耗尽，需熔断该渠道")
    void insufficientBalanceTripsCircuit() {
        UpstreamException e = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"Insufficient balance in account\"}}");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.QUOTA_EXHAUSTED);
        assertThat(e.shouldTripCircuit()).isTrue();
        assertThat(e.retryable()).isTrue();
    }

    @Test
    @DisplayName("内容安全拦截不重试且透传业务")
    void contentFilterIsNotRetryable() {
        UpstreamException e = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"content filter triggered by safety policy\"}}");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.CONTENT_FILTER);
        assertThat(e.shouldTripCircuit()).isFalse();
    }

    @Test
    @DisplayName("上下文超长不重试")
    void contextTooLongIsNotRetryable() {
        UpstreamException e = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"This model's maximum context length is exceeded\"}}");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.CONTEXT_TOO_LONG);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    @DisplayName("超时应归类为 TIMEOUT 且可重试")
    void timeoutIsRetryable() {
        UpstreamException e = UpstreamException.from(HttpStatus.GATEWAY_TIMEOUT, "");

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    @DisplayName("渠道饱和属于网关侧背压：可换渠道重试，但不得计入渠道熔断")
    void channelSaturationDoesNotBlameChannel() {
        UpstreamException e = new UpstreamException(UpstreamException.Kind.CHANNEL_SATURATED,
                "渠道并发已达上限", null);

        // 可以换渠道重试，这样同模型的其他健康渠道仍能承接流量
        assertThat(e.retryable()).isTrue();
        // 但绝不能计入熔断：否则「网关自己限流」会把健康的渠道熔断掉
        assertThat(e.countsTowardCircuit()).isFalse();
        // 对外语义是 429，而不是 5xx
        assertThat(e.toErrorCode()).isEqualTo(com.gateway.infra.ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("上游自身故障应计入渠道熔断")
    void upstreamFailuresCountTowardCircuit() {
        assertThat(UpstreamException.from(HttpStatus.INTERNAL_SERVER_ERROR, "boom")
                .countsTowardCircuit()).isTrue();
        assertThat(UpstreamException.from(HttpStatus.GATEWAY_TIMEOUT, "")
                .countsTowardCircuit()).isTrue();
        assertThat(UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"Insufficient balance\"}}")
                .countsTowardCircuit()).isTrue();
    }

    @Test
    @DisplayName("请求内容类 4xx 不得计入渠道熔断：用户刷超长/被审核请求不该熔断健康渠道")
    void requestContentErrorsDoNotBlameChannel() {
        UpstreamException contentFilter = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"content filter triggered by safety policy\"}}");
        UpstreamException contextTooLong = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"maximum context length is exceeded\"}}");
        UpstreamException malformedRequest = UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"invalid temperature value\"}}");

        assertThat(contentFilter.countsTowardCircuit()).isFalse();
        assertThat(contextTooLong.countsTowardCircuit()).isFalse();
        // 普通 400 归因不明（也可能是请求形态问题），宁可漏报也不误杀健康渠道
        assertThat(malformedRequest.countsTowardCircuit()).isFalse();
    }

    @Test
    @DisplayName("渠道配置事故（401/403/404）计入熔断，并识别为需要立即冷却")
    void channelConfigurationErrorsCountAndCooldown() {
        UpstreamException invalidKey = UpstreamException.from(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"invalid api key\"}}");
        UpstreamException forbidden = UpstreamException.from(HttpStatus.FORBIDDEN, "forbidden");
        UpstreamException modelMissing = UpstreamException.from(HttpStatus.NOT_FOUND, "model not found");

        for (UpstreamException e : java.util.List.of(invalidKey, forbidden, modelMissing)) {
            assertThat(e.countsTowardCircuit()).isTrue();
            assertThat(e.isChannelConfigurationError()).isTrue();
        }
        // 内容类 4xx 不是配置事故，仍走普通业务透传
        assertThat(UpstreamException.from(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"content filter triggered\"}}").isChannelConfigurationError()).isFalse();
    }

    @Test
    @DisplayName("网关侧背压与上游故障的归因必须区分开")
    void saturationIsNotAChannelHealthSignal() {
        UpstreamException saturated = new UpstreamException(UpstreamException.Kind.CHANNEL_SATURATED, "渠道饱和", null);
        UpstreamException upstreamBoom = UpstreamException.from(HttpStatus.SERVICE_UNAVAILABLE, "boom");

        assertThat(saturated.countsTowardCircuit()).isFalse();
        assertThat(upstreamBoom.countsTowardCircuit()).isTrue();
    }

    @Test
    @DisplayName("传输层超时（Reactor TimeoutException 消息里没有 timeout 字样）也必须归为 TIMEOUT")
    void transportTimeoutIsClassifiedAsTimeout() {
        Throwable reactorTimeout = new java.util.concurrent.TimeoutException(
                "Did not observe any item or terminal signal within 300ms in 'timeout'");

        UpstreamException e = UpstreamException.fromTransportError(reactorTimeout);

        assertThat(e.getKind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
        assertThat(e.toErrorCode()).isEqualTo(com.gateway.infra.ErrorCode.UPSTREAM_TIMEOUT);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    @DisplayName("Netty 读超时被包在 WebClientRequestException 里时，仍要沿 cause 链识别出超时")
    void nestedTimeoutIsStillTimeout() {
        Throwable nested = new RuntimeException("connection reset",
                new java.util.concurrent.TimeoutException("ReadTimeoutException"));

        assertThat(UpstreamException.fromTransportError(nested).getKind())
                .isEqualTo(UpstreamException.Kind.TIMEOUT);
        // 普通连接失败仍是可重试，而不是超时
        assertThat(UpstreamException.fromTransportError(new RuntimeException("connection refused")).getKind())
                .isEqualTo(UpstreamException.Kind.RETRYABLE);
    }

    @Test
    @DisplayName("错误信息应截断，避免把上游长正文（可能含用户数据）原样带出")
    void errorMessageIsTruncated() {
        String longBody = "x".repeat(2000);

        UpstreamException e = UpstreamException.from(HttpStatus.BAD_REQUEST, longBody);

        assertThat(e.getMessage().length()).isLessThan(400);
    }
}

package com.gateway.infra;

import com.gateway.adapter.UpstreamException;
import com.gateway.protocol.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 把内部异常统一翻译成 OpenAI 兼容错误体，并带上 traceId 便于排查。
 * traceId 从 Reactor Context 读取（由 RequestContextWebFilter 注入）。
 */
@Slf4j
@Order(-100)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public Mono<ErrorResponse> handleGateway(GatewayException ex, ServerHttpResponse response) {
        ErrorCode code = ex.getCode();
        response.setRawStatusCode(code.getHttpStatus().value());
        log.warn("网关异常: code={}, msg={}", code.name(), ex.getMessage());
        return Mono.deferContextual(ctx -> Mono.just(new ErrorResponse(new ErrorResponse.ErrorDetail(
                ex.getMessage(), code.getType(), code.name(), ex.getParam(),
                TraceIds.fromContext(ctx), ex.getRetryAfter()))));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ErrorResponse> handleStatus(ResponseStatusException ex, ServerHttpResponse response) {
        response.setRawStatusCode(ex.getStatusCode().value());
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return Mono.deferContextual(ctx -> Mono.just(ErrorResponse.of(
                ex.getReason(), code.getType(), code.name(), TraceIds.fromContext(ctx))));
    }

    /**
     * 上游调用失败（含网关侧渠道背压）。
     *
     * 归一的 {@link UpstreamException.Kind} 已经决定了语义，这里只做「错误码 -> HTTP 状态 + 错误体」
     * 的翻译，绝不能再让上游/限流异常落到 handleOther 被兜成 500 —— 那会让业务把
     * 「渠道被限流」「上游 5xx」全部读成「网关崩了」。
     */
    @ExceptionHandler(UpstreamException.class)
    public Mono<ErrorResponse> handleUpstream(UpstreamException ex, ServerHttpResponse response) {
        ErrorCode code = ex.toErrorCode();
        response.setRawStatusCode(code.getHttpStatus().value());
        log.warn("上游异常: kind={}, code={}, upstreamStatus={}, msg={}",
                ex.getKind(), code.name(), ex.getUpstreamStatus(), ex.getMessage());
        return Mono.deferContextual(ctx -> Mono.just(new ErrorResponse(new ErrorResponse.ErrorDetail(
                ex.getMessage(), code.getType(), code.name(), null,
                TraceIds.fromContext(ctx), ex.getRetryAfter()))));
    }

    @ExceptionHandler(Throwable.class)
    public Mono<ErrorResponse> handleOther(Throwable ex, ServerHttpResponse response) {
        response.setRawStatusCode(ErrorCode.INTERNAL_ERROR.getHttpStatus().value());
        log.error("网关未预期异常", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return Mono.deferContextual(ctx -> Mono.just(ErrorResponse.of(
                code.getDefaultMessage(), code.getType(), code.name(), TraceIds.fromContext(ctx))));
    }
}

package com.gateway.infra;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 统一错误分类。
 * 分类决定处理策略：RETRYABLE 才允许换渠道重试，QUOTA_EXHAUSTED 会连带熔断该渠道。
 */
@Getter
public enum ErrorCode {

    INVALID_REQUEST("invalid_request_error", HttpStatus.BAD_REQUEST, "请求参数不合法", false),
    UNAUTHORIZED("authentication_error", HttpStatus.UNAUTHORIZED, "虚拟 Key 无效或已过期", false),
    MODEL_NOT_ALLOWED("permission_error", HttpStatus.FORBIDDEN, "该应用无权访问此模型", false),
    MODEL_NOT_FOUND("invalid_request_error", HttpStatus.NOT_FOUND, "逻辑模型不存在或未启用", false),
    INSUFFICIENT_BALANCE("billing_error", HttpStatus.PAYMENT_REQUIRED, "余额或预算不足", false),
    RATE_LIMITED("rate_limit_error", HttpStatus.TOO_MANY_REQUESTS, "触发限流", true),
    CONTENT_FILTER("content_filter_error", HttpStatus.BAD_REQUEST, "内容安全拦截", false),
    CONTEXT_TOO_LONG("invalid_request_error", HttpStatus.BAD_REQUEST, "上下文超出模型上限", false),
    RETRYABLE_UPSTREAM("upstream_error", HttpStatus.BAD_GATEWAY, "上游可重试错误", true),
    NON_RETRYABLE_UPSTREAM("upstream_error", HttpStatus.BAD_GATEWAY, "上游不可重试错误", false),
    UPSTREAM_TIMEOUT("upstream_error", HttpStatus.GATEWAY_TIMEOUT, "上游超时", true),
    UPSTREAM_UNAVAILABLE("upstream_error", HttpStatus.SERVICE_UNAVAILABLE, "无可用渠道", true),
    CIRCUIT_OPEN("upstream_error", HttpStatus.SERVICE_UNAVAILABLE, "渠道熔断中", true),
    INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR, "网关内部错误", false);

    private final String type;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
    /** 是否属于可重试（可换渠道）错误。 */
    private final boolean retryable;

    ErrorCode(String type, HttpStatus httpStatus, String defaultMessage, boolean retryable) {
        this.type = type;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }
}

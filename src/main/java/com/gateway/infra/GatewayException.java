package com.gateway.infra;

import lombok.Getter;

/** 网关业务异常：携带统一错误码，由全局异常处理器转成 OpenAI 风格错误体。 */
@Getter
public class GatewayException extends RuntimeException {

    private final ErrorCode code;
    private final String param;
    private final Integer retryAfter;

    public GatewayException(ErrorCode code) {
        this(code, code.getDefaultMessage(), null, null, null);
    }

    public GatewayException(ErrorCode code, String message) {
        this(code, message, null, null, null);
    }

    /** 带 param 的构造（避免与 cause 重载混淆时显式声明）。 */
    public GatewayException(ErrorCode code, String message, String param) {
        this(code, message, param, null, null);
    }

    public GatewayException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause, null);
    }

    public GatewayException(ErrorCode code, String message, String param, Throwable cause, Integer retryAfter) {
        super(message, cause);
        this.code = code;
        this.param = param;
        this.retryAfter = retryAfter;
    }

    public static GatewayException of(ErrorCode code, String message) {
        return new GatewayException(code, message);
    }

    public static GatewayException internal(Throwable cause) {
        return new GatewayException(ErrorCode.INTERNAL_ERROR, cause.getMessage(), cause);
    }
}

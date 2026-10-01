package com.gateway.adapter;

import com.gateway.infra.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/**
 * 上游错误归一化。
 * Kind 直接决定「是否换渠道重试」以及「是否触发熔断」，所以必须在适配器层收敛，
 * 不能让各厂商五花八门的错误码散落到路由/熔断逻辑里。
 */
@Getter
public class UpstreamException extends RuntimeException {

    public enum Kind {
        /** 429、5xx、连接失败：可换渠道重试。 */
        RETRYABLE,
        /** 400、401 等：重试无意义，直接失败。 */
        NON_RETRYABLE,
        /** 上游余额/配额耗尽：除重试外还要熔断该渠道。 */
        QUOTA_EXHAUSTED,
        /** 内容安全拦截：透传业务，不重试。 */
        CONTENT_FILTER,
        /** 上下文超长：不重试。 */
        CONTEXT_TOO_LONG,
        /** 超时：可重试。 */
        TIMEOUT,
        /**
         * 渠道并发已饱和（网关自身的闸门拦下的，请求根本没到上游）。
         * 可以换渠道重试，但**绝不能**算作该渠道的健康问题。
         */
        CHANNEL_SATURATED
    }

    private final Kind kind;
    private final Integer upstreamStatus;
    private final String upstreamCode;
    /** 可选：给业务的重试建议（秒），由网关侧限流等场景透传。 */
    private final Integer retryAfter;

    public UpstreamException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null, null, null);
    }

    public UpstreamException(Kind kind, String message, Throwable cause, Integer upstreamStatus, String upstreamCode) {
        this(kind, message, cause, upstreamStatus, upstreamCode, null);
    }

    public UpstreamException(Kind kind, String message, Throwable cause, Integer upstreamStatus,
                             String upstreamCode, Integer retryAfter) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return kind == Kind.RETRYABLE || kind == Kind.TIMEOUT
                || kind == Kind.QUOTA_EXHAUSTED || kind == Kind.CHANNEL_SATURATED;
    }

    /**
     * 是否应计入该渠道的熔断统计。
     *
     * 判据只有一条：**这次失败是否反映了「渠道自身」的健康状况**。
     * 只有在失败与渠道无关、却计入失败率时，才会出现「健康渠道被无关原因熔断」——
     * 例如用户反复提交超长/被审核的请求，把这只有渠道唯一能承接的流量打到熔断。
     *
     * 决策表（显式穷举，新增 Kind 时编译器会强制表态）：
     * - CHANNEL_SATURATED：本网关自己的闸门拦下的，请求没发到上游 → 不计入
     *   （与 ferro-labs/ai-gateway「背压不计入熔断」一致）；
     * - CONTENT_FILTER / CONTEXT_TOO_LONG：由用户输入决定，换任何渠道结果一样 → 不计入；
     * - NON_RETRYABLE：默认视为请求形态问题（如某渠道不接受该参数组合）→ 不计入；
     *   唯独 401/403/404 是渠道自身配置事故（密钥失效 / 无权 / 模型或端点映射错误），必须计入；
     * - RETRYABLE / TIMEOUT / QUOTA_EXHAUSTED：上游确实不健康 → 计入。
     */
    public boolean countsTowardCircuit() {
        return switch (kind) {
            case CHANNEL_SATURATED, CONTENT_FILTER, CONTEXT_TOO_LONG -> false;
            case RETRYABLE, TIMEOUT, QUOTA_EXHAUSTED -> true;
            case NON_RETRYABLE -> isChannelConfigurationError();
        };
    }

    /**
     * 是否属于「渠道配置事故」：密钥失效 / 无权访问 / 模型或端点映射错误。
     *
     * 这类错误重试无意义，但也不能按普通 4xx 处理 —— 渠道在修好之前会持续失败，
     * 应当立即冷却、把流量让给其他渠道。
     */
    public boolean isChannelConfigurationError() {
        return kind == Kind.NON_RETRYABLE && upstreamStatus != null
                && (upstreamStatus == 401 || upstreamStatus == 403 || upstreamStatus == 404);
    }

    /** QUOTA_EXHAUSTED 属于「渠道自身有问题」，需要熔断 + 冷却。 */
    public boolean shouldTripCircuit() {
        return kind == Kind.QUOTA_EXHAUSTED;
    }

    public ErrorCode toErrorCode() {
        return switch (kind) {
            case RETRYABLE -> ErrorCode.RETRYABLE_UPSTREAM;
            case NON_RETRYABLE -> ErrorCode.NON_RETRYABLE_UPSTREAM;
            case QUOTA_EXHAUSTED -> ErrorCode.RETRYABLE_UPSTREAM;
            case CONTENT_FILTER -> ErrorCode.CONTENT_FILTER;
            case CONTEXT_TOO_LONG -> ErrorCode.CONTEXT_TOO_LONG;
            case TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
            case CHANNEL_SATURATED -> ErrorCode.RATE_LIMITED;
        };
    }

    /** 把上游 HTTP 状态码与错误体翻译成统一分类。 */
    public static UpstreamException from(HttpStatusCode status, String body) {
        int code = status.value();
        String snippet = snippet(body);
        String upstreamCode = extractUpstreamCode(body);

        Kind kind = switch (code) {
            case 429 -> Kind.RETRYABLE;
            case 401, 403 -> Kind.NON_RETRYABLE;
            case 400 -> classifyBadRequest(body, upstreamCode);
            case 404 -> Kind.NON_RETRYABLE;
            case 408, 504 -> Kind.TIMEOUT;
            default -> code >= 500 ? Kind.RETRYABLE : Kind.NON_RETRYABLE;
        };
        return new UpstreamException(kind,
                "上游返回 " + code + ": " + snippet, null, code, upstreamCode);
    }

    private static Kind classifyBadRequest(String body, String upstreamCode) {
        String lower = body == null ? "" : body.toLowerCase();
        if (lower.contains("content") && (lower.contains("filter") || lower.contains("policy")
                || lower.contains("safety") || lower.contains("risk"))) {
            return Kind.CONTENT_FILTER;
        }
        if (lower.contains("context") && (lower.contains("length") || lower.contains("long")
                || lower.contains("too many tokens"))) {
            return Kind.CONTEXT_TOO_LONG;
        }
        if (lower.contains("insufficient") || lower.contains("quota") || lower.contains("balance")
                || lower.contains("arrears")) {
            return Kind.QUOTA_EXHAUSTED;
        }
        if (upstreamCode != null && (upstreamCode.contains("Quota") || upstreamCode.contains("Throttling"))) {
            return Kind.QUOTA_EXHAUSTED;
        }
        return Kind.NON_RETRYABLE;
    }

    private static String extractUpstreamCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            var node = com.gateway.infra.JsonSupport.mapper().readTree(body);
            if (node.hasNonNull("code")) {
                return node.get("code").asText();
            }
            if (node.has("error") && node.get("error").hasNonNull("code")) {
                return node.get("error").get("code").asText();
            }
        } catch (Exception ignored) {
            // 上游错误体不是标准 JSON 时忽略
        }
        return null;
    }

    /**
     * 把**传输层**异常（Reactor 的 TimeoutException、Netty 的 ReadTimeoutException、
     * 连接失败、读中断等）归一成分类。
     *
     * 之所以要按类型判断而不能只看 message：Reactor 的 {@code .timeout()} 抛出的
     * {@link java.util.concurrent.TimeoutException} 消息是
     * "Did not observe any item or terminal signal within 300ms..."，里面**没有 timeout 这个词**，
     * 只看 message 会把「上游超时」误判成普通可重试错误，对外返回 502 而不是 504。
     */
    public static UpstreamException fromTransportError(Throwable error) {
        String msg = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        return isTimeout(error, msg)
                ? new UpstreamException(Kind.TIMEOUT, "上游调用超时: " + snippet(msg), error)
                : new UpstreamException(Kind.RETRYABLE, msg, error);
    }

    /** 沿 cause 链判断是否超时（Netty/Reactor 常把根因包在 WebClientRequestException 里）。 */
    private static boolean isTimeout(Throwable error, String message) {
        int depth = 0;
        for (Throwable t = error; t != null && depth++ < 8; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return message.toLowerCase().contains("timeout");
    }

    /** 截断错误体，避免把上游返回的长正文（可能含用户数据）整段带进日志。 */
    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...(已截断)";
    }
}

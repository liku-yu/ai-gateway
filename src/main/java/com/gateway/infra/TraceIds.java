package com.gateway.infra;

import reactor.util.context.ContextView;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** traceId 贯穿工具：Reactor Context 为唯一真相来源，不依赖 ThreadLocal。 */
public final class TraceIds {

    public static final String CONTEXT_KEY = "traceId";
    public static final String REQUEST_ID_CONTEXT_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String fromContext(ContextView ctx) {
        Object v = ctx.getOrDefault(CONTEXT_KEY, null);
        return v == null ? "unknown" : v.toString();
    }

    /** 简易短 ID，用于占位符编号等场景。 */
    public static String shortId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong(0x1_0000_0000L));
    }
}

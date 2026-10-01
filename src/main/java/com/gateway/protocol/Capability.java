package com.gateway.protocol;

/** 上游能力声明：用于路由前置过滤（渠道不支持某能力则跳过）。 */
public enum Capability {
    CHAT,
    EMBEDDING,
    RERANK,
    VISION,
    TOOL_CALL,
    STREAM
}

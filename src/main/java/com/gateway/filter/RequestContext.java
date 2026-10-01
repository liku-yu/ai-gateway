package com.gateway.filter;

import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.domain.Channel;
import com.gateway.domain.LogicalModel;
import com.gateway.domain.MaskingPolicy;
import com.gateway.domain.Price;
import com.gateway.infra.TraceIds;
import com.gateway.masking.MaskResult;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.EmbeddingRequest;
import com.gateway.protocol.GatewayMeta;
import com.gateway.protocol.Usage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求上下文：整条责任链共享的可变状态。
 * 每个过滤器只读写自己负责的字段，避免过滤器之间隐式耦合。
 */
@Data
@Accessors(chain = true)
public class RequestContext {

    public enum Kind { CHAT, EMBEDDING }

    // ---- 链路标识 ----
    private String traceId;
    private String requestId;
    private String clientIp;
    private final long startNanos = System.nanoTime();
    private Integer ttfbMs;

    // ---- 鉴权产物 ----
    private String rawApiKey;
    private ApiKey apiKey;
    private App app;

    // ---- 请求内容 ----
    private Kind kind;
    private String logicalModel;
    private String requestedModel;
    private ChatRequest chatRequest;
    private EmbeddingRequest embeddingRequest;
    private LogicalModel modelDefinition;

    // ---- 脱敏 ----
    private MaskingPolicy maskingPolicy;
    private MaskResult maskResult;
    private Integer maskedCount;

    // ---- 路由 ----
    private String routingStrategy;
    private boolean allowFallback = true;
    private List<String> fallbackModels = new ArrayList<>();
    private Channel channel;
    private String physicalModel;
    /** 实际执行的逻辑模型（降级后可能与 requestedModel 不同），用于准确标记 degraded。 */
    private String executedModel;

    // ---- 上游调用结果 ----
    private Object response;
    private Usage usage;
    private int retryCount;
    private boolean degraded;
    private boolean success;
    /**
     * 是否已把完整响应交到客户端（决定终结时的 success 语义）。
     * 正常路径在写入响应前置 true；任何在它之前失败/被取消的请求都视为未交付。
     */
    private boolean payloadDelivered;
    private String errorCode;
    private Integer statusCode;
    private List<String> attemptedChannels = new ArrayList<>();

    // ---- 计费 ----
    /** prompt token 估算缓存：同一请求只分词一次，详见 PromptTokens。 */
    private Integer estimatedPromptTokens;

    private Price price;
    private BigDecimal preChargedCost;
    private com.gateway.billing.QuotaTicket quotaTicket;
    private BigDecimal actualCost;
    private boolean usageEstimated;

    // ---- 链路资源（需释放）----
    /** 已占用的 Redis 并发键，必须由响应完成回调释放。 */
    private List<String> concurrencyKeys = new ArrayList<>();
    /**
     * 是否已完成终结（结算 + 释放并发 + 落日志）。
     *
     * 为什么需要这个标记：责任链上任何一环出错都可能触发终结，而正常路径也会终结一次。
     * 若不做幂等，会出现「重复结算」；若不做兜底，则会出现「准入后失败导致预扣费与
     * 并发额度双双泄漏」。两者都不可接受，因此终结入口统一收敛到 RequestFinalizer.finalizeOnce()，
     * 由本标记保证「无论成功、失败、超时，恰好终结一次」。
     */
    private final java.util.concurrent.atomic.AtomicBoolean finalized = new java.util.concurrent.atomic.AtomicBoolean(false);

    // ---- 其他 ----
    private org.springframework.web.server.ServerWebExchange exchange;
    private boolean stream;
    private reactor.core.publisher.Flux<com.gateway.protocol.ChatChunk> streamFlux;
    /** 模型降级链：首个为业务请求的模型，后续为备用模型。 */
    private List<String> modelChain = new ArrayList<>();
    private Integer timeoutMs;
    private BigDecimal maxCost;
    private final List<String> warnings = new ArrayList<>();

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public void markTtfb() {
        if (ttfbMs == null) {
            ttfbMs = (int) elapsedMs();
        }
    }

    public void recordAttempt(Channel ch) {
        attemptedChannels.add(ch.getId() + ":" + ch.getName());
    }

    public GatewayMeta toGatewayMeta() {
        GatewayMeta meta = new GatewayMeta();
        meta.setTraceId(traceId);
        meta.setRequestId(requestId);
        meta.setRetryCount(retryCount);
        meta.setDegraded(degraded);
        meta.setMasked(maskedCount != null && maskedCount > 0);
        meta.setMaskedCount(maskedCount);
        meta.setLatencyMs((int) elapsedMs());
        meta.setTtfbMs(ttfbMs);
        meta.setCost(actualCost != null ? actualCost : preChargedCost);
        if (channel != null) {
            meta.setChannelId(channel.getId());
            meta.setChannelName(channel.getName());
            meta.setProvider(channel.getProvider() == null ? null : channel.getProvider().getCode());
        }
        meta.setPhysicalModel(physicalModel);
        if (price != null) {
            meta.setCurrency(price.getCurrency());
        }
        return meta;
    }

    public static String newTraceId() {
        return TraceIds.generate();
    }
}

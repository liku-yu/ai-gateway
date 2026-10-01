package com.gateway.filter;

import com.gateway.adapter.UpstreamInvoker;
import com.gateway.domain.Channel;
import com.gateway.infra.ConfigCache;
import com.gateway.infra.ErrorCode;
import com.gateway.masking.MaskingEngine;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingResponse;
import com.gateway.protocol.GatewayMeta;
import com.gateway.protocol.Usage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调用上游（order=70，链尾）。
 *
 * 两条路径：
 * - 非流式：拿到完整响应 -> 回填占位符 -> 结算 -> 写响应；
 * - 流式：把上游分片直接转成 SSE 下发（不缓冲），在流终止时结算。
 *
 * 流式的关键工程点：
 * 1. 首字节尽早返回（不等待首个 token 之外的东西）；
 * 2. 客户端断连立即取消上游（避免继续计费）；
 * 3. usage 在最后一个分片才出现，因此结算挂在 doFinally 上，且保证只执行一次。
 */
@Slf4j
@Component
@Order(FilterOrder.UPSTREAM_CALL)
@RequiredArgsConstructor
public class UpstreamCallFilter implements GatewayFilter {

    private final UpstreamInvoker invoker;
    private final ConfigCache configCache;
    private final RequestFinalizer finalizer;
    private final MaskingEngine maskingEngine;
    private final com.gateway.billing.BillingService billingService;

    @Override
    public int order() {
        return FilterOrder.UPSTREAM_CALL;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        if (ctx.getKind() == RequestContext.Kind.EMBEDDING) {
            return handleEmbedding(ctx);
        }
        return ctx.isStream() ? handleStream(ctx) : handleChat(ctx);
    }

    // ==================================================================
    // 非流式对话
    // ==================================================================
    private Mono<RequestContext> handleChat(RequestContext ctx) {
        return invoker.chatWithFailover(configCache.current(), ctx.getModelChain(),
                        ctx.getChatRequest(), String.valueOf(ctx.getApp().getId()), sessionKey(ctx),
                        billingEstimate(ctx))
                .flatMap(outcome -> {
                    ctx.markTtfb();
                    applyOutcome(ctx, outcome.channel(), outcome.physicalModel(), outcome.logicalModel(), outcome.attempts());
                    ChatResponse response = outcome.response();
                    if (response.getUsage() != null) {
                        ctx.setUsage(response.getUsage());
                    } else {
                        ctx.setUsage(estimateChatUsage(ctx));
                    }
                    restorePlaceholders(ctx, response);
                    ctx.setResponse(response);
                    ctx.setStatusCode(200);
                    ctx.setPayloadDelivered(true);
                    // gateway 元信息必须在结算之后生成，否则 cost 回告的是「预扣费」而非真实结算额
                    return finalizer.finalizeOnce(ctx, null)
                            .then(Mono.fromRunnable(() -> response.setGateway(ctx.toGatewayMeta())))
                            .thenReturn(ctx);
                })
                .onErrorResume(e -> failFast(ctx, e));
    }

    // ==================================================================
    // 向量化
    // ==================================================================
    private Mono<RequestContext> handleEmbedding(RequestContext ctx) {
        return invoker.embeddingWithFailover(configCache.current(), ctx.getRequestedModel(),
                        ctx.getEmbeddingRequest(), String.valueOf(ctx.getApp().getId()), billingEstimate(ctx))
                .flatMap(outcome -> {
                    ctx.markTtfb();
                    applyOutcome(ctx, outcome.channel(), outcome.physicalModel(), outcome.logicalModel(), outcome.attempts());
                    EmbeddingResponse response = outcome.response();
                    ctx.setUsage(response.getUsage() == null
                            ? Usage.estimated(billingEstimate(ctx), 0) : response.getUsage());
                    ctx.setResponse(response);
                    ctx.setStatusCode(200);
                    ctx.setPayloadDelivered(true);
                    return finalizer.finalizeOnce(ctx, null)
                            .then(Mono.fromRunnable(() -> response.setGateway(ctx.toGatewayMeta())))
                            .thenReturn(ctx);
                })
                .onErrorResume(e -> failFast(ctx, e));
    }

    // ==================================================================
    // 流式对话
    // ==================================================================
    private Mono<RequestContext> handleStream(RequestContext ctx) {
        AtomicReference<Channel> channelRef = new AtomicReference<>();
        AtomicInteger attemptsRef = new AtomicInteger();
        AtomicBoolean ttfb = new AtomicBoolean(false);
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        AtomicReference<String> logicalModelRef = new AtomicReference<>(ctx.getRequestedModel());
        AtomicBoolean finalized = new AtomicBoolean(false);

        Flux<ChatChunk> upstream = invoker.chatStreamWithFailover(
                configCache.current(), ctx.getModelChain(), ctx.getChatRequest(),
                String.valueOf(ctx.getApp().getId()), sessionKey(ctx),
                channelRef, attemptsRef, ttfb, logicalModelRef, billingEstimate(ctx));

        Flux<ChatChunk> withSettlement = upstream
                .doOnNext(chunk -> {
                    ctx.markTtfb();
                    if (chunk.getUsage() != null) {
                        usageRef.set(chunk.getUsage());
                    }
                    if (chunk.getGateway() == null) {
                        chunk.setGateway(null);
                    }
                })
                // 上游流在最后一个分片带 usage；这里把原样分片继续下发（含 usage，业务可选读）
                .doFinally(signal -> {
                    if (!finalized.compareAndSet(false, true)) {
                        return;
                    }
                    applyStreamOutcome(ctx, channelRef.get(), attemptsRef.get(), logicalModelRef.get());
                    Usage usage = usageRef.get() != null ? usageRef.get() : estimateChatUsage(ctx);
                    boolean ok = signal == SignalType.ON_COMPLETE;
                    ErrorCode errorCode = switch (signal) {
                        case ON_COMPLETE -> null;
                        case CANCEL -> ErrorCode.INTERNAL_ERROR;   // 客户端断连
                        default -> ErrorCode.RETRYABLE_UPSTREAM;
                    };
                    ctx.setStatusCode(ok ? 200 : com.gateway.infra.ErrorCode
                            .valueOf(errorCode == null ? "INTERNAL_ERROR" : errorCode.name())
                            .getHttpStatus().value());
                    // 只有正常走完的流才算交付成功；取消/中断按失败结算
                    ctx.setPayloadDelivered(ok);
                    finalizer.finalizeRequest(ctx, ctx.getQuotaTicket(), usage, ok, errorCode)
                            .doOnError(e -> log.warn("流式结算异常: {}", e.getMessage()))
                            .subscribe();
                });

        ctx.setStream(true);
        ctx.setStreamFlux(withSettlement);
        ctx.setStatusCode(200);
        return Mono.just(ctx);
    }

    // ==================================================================
    // 公共
    // ==================================================================

    private void applyOutcome(RequestContext ctx, Channel channel, String physicalModel,
                              String logicalModel, int attempts) {
        ctx.setChannel(channel);
        ctx.setPhysicalModel(physicalModel);
        ctx.setRetryCount(Math.max(0, attempts - 1));
        // 降级 = 实际执行的模型不是业务请求的那个（例如 chat-default 失败后落到 chat-cheap）
        ctx.setDegraded(!logicalModel.equals(ctx.getRequestedModel()));
        ctx.setExecutedModel(logicalModel);
        // 定价依赖「最终选中的渠道 + 实际模型」，只有到这里才能确定，否则结算会算成 0 元
        ctx.setPrice(resolvePrice(channel, logicalModel));
        ctx.recordAttempt(channel);
    }

    private com.gateway.domain.Price resolvePrice(Channel channel, String logicalModel) {
        if (channel == null || channel.getProvider() == null) {
            return null;
        }
        String physical = channel.physicalModel(logicalModel);
        if (physical == null) {
            return null;
        }
        return configCache.current().price(channel.getProvider().getCode(), physical);
    }

    private void applyStreamOutcome(RequestContext ctx, Channel channel, int attempts, String executedModel) {
        if (channel != null) {
            ctx.setChannel(channel);
            ctx.setPhysicalModel(channel.physicalModel(executedModel));
            ctx.setPrice(resolvePrice(channel, executedModel));
            ctx.recordAttempt(channel);
        }
        ctx.setExecutedModel(executedModel);
        ctx.setDegraded(!executedModel.equals(ctx.getRequestedModel()));
        ctx.setRetryCount(Math.max(0, attempts - 1));
    }

    /** 响应回填：模型回复里的占位符还原为原文（默认关闭，可按租户开启）。 */
    private void restorePlaceholders(RequestContext ctx, ChatResponse response) {
        if (!maskingEngine.shouldRestore(ctx.getMaskingPolicy()) || ctx.getMaskResult() == null) {
            return;
        }
        if (response.getChoices() == null) {
            return;
        }
        for (ChatResponse.ChatChoice choice : response.getChoices()) {
            if (choice.getMessage() != null) {
                choice.getMessage().setContentText(
                        maskingEngine.restore(choice.getMessage().contentAsText(), ctx.getMaskResult()));
            }
        }
    }

    private Usage estimateChatUsage(RequestContext ctx) {
        int prompt = billingEstimate(ctx);
        return Usage.estimated(prompt, 0);
    }

    /** 估算 prompt tokens，仅在无法获取上游 usage 时用于结算兜底。 */
    private int billingEstimate(RequestContext ctx) {
        if (ctx.getChatRequest() == null && ctx.getEmbeddingRequest() == null) {
            return 0;
        }
        return PromptTokens.of(ctx, billingService);
    }

    private String sessionKey(RequestContext ctx) {
        var hint = ctx.getChatRequest().routing();
        return hint != null && hint.getSessionKey() != null
                ? hint.getSessionKey() : String.valueOf(ctx.getApp().getId());
    }

    private Mono<RequestContext> failFast(RequestContext ctx, Throwable error) {
        ErrorCode code;
        if (error instanceof com.gateway.adapter.UpstreamException ue) {
            code = ue.toErrorCode();
        } else if (error instanceof com.gateway.infra.GatewayException ge) {
            code = ge.getCode();
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }
        ctx.setStatusCode(code.getHttpStatus().value());
        // 终结（退还预扣 + 释放并发 + 落日志）统一由 FilterChainFactory 兜底执行，
        // 这里只负责设置状态码并向上抛出，避免两处各写一遍终结算逻辑而出现分歧。
        return Mono.error(error);
    }
}

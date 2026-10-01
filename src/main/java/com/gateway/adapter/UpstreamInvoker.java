package com.gateway.adapter;

import com.gateway.circuit.CircuitRegistry;
import com.gateway.circuit.CooldownTracker;
import com.gateway.domain.Channel;
import com.gateway.infra.ConfigSnapshot;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.GatewayMetrics;
import com.gateway.infra.GatewayProperties;
import com.gateway.infra.SecretCipher;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingRequest;
import com.gateway.protocol.EmbeddingResponse;
import com.gateway.ratelimit.RateLimiterService;
import com.gateway.routing.RouteContext;
import com.gateway.routing.Router;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 上游调用编排：路由 -> 解密密钥 -> 调用 -> 失败分类 -> 冷却/熔断 -> 换渠道重试。
 *
 * 三条硬约束：
 * 1. **总 deadline**：重试与模型降级共享同一份业务超时预算，避免「越重试越慢」。
 *    非流式在预算耗尽时立即停止重试与降级；流式只在**首字节之前**受预算约束，
 *    首字节之后仅保留逐块 idle 超时，避免正常的长时间生成被总预算掐断；
 * 2. **流式首字节后不重试**：已经吐给客户端的内容无法撤回，重试会造成重复输出；
 * 3. **密钥只在此处解密**：明文仅存活于本次调用栈内，失败日志不含任何密钥信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamInvoker {

    private final Router router;
    private final RateLimiterService rateLimiterService;
    private final ProviderRegistry providerRegistry;
    private final SecretCipher secretCipher;
    private final CircuitRegistry circuitRegistry;
    private final CooldownTracker cooldownTracker;
    private final GatewayProperties properties;
    private final GatewayMetrics metrics;

    /**
     * 一次调用的结果。
     * logicalModel 是**实际执行**的逻辑模型（可能因降级而不同于业务请求的那个），
     * 业务据此才能正确感知「降级」，因此必须随结果一并返回。
     */
    public record ChatOutcome(ChatResponse response, Channel channel, String physicalModel,
                              String logicalModel, int attempts) {
    }

    public record EmbeddingOutcome(EmbeddingResponse response, Channel channel, String physicalModel,
                                    String logicalModel, int attempts) {
    }

    // ==================================================================
    // 非流式：可安全重试（换渠道 / 降级模型）
    // ==================================================================

    public Mono<ChatOutcome> chatWithFailover(ConfigSnapshot snapshot, List<String> modelChain,
                                              ChatRequest request, String appIdKey, String sessionKey,
                                              int estimatedTokens) {
        int maxRetries = properties.getDefaults().getMaxRetries();
        List<Long> tried = new ArrayList<>();
        long deadlineAt = deadlineAt(request.timeoutMs());

        return attemptChat(snapshot, modelChain, 0, 0, maxRetries, tried, request, appIdKey, sessionKey,
                estimatedTokens, deadlineAt);
    }

    private Mono<ChatOutcome> attemptChat(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                          int attempt, int maxRetries, List<Long> tried,
                                          ChatRequest request, String appIdKey, String sessionKey,
                                          int estimatedTokens, long deadlineAt) {
        if (budgetExhausted(deadlineAt)) {
            return Mono.error(budgetExhaustedError());
        }
        String logicalModel = modelChain.get(modelIndex);
        RouteContext ctx = RouteContext.of(logicalModel, request.routing() == null ? null : request.routing().getStrategy(),
                appIdKey, sessionKey);

        return router.pick(snapshot, logicalModel, ctx, tried)
                .flatMap(channel -> {
                    tried.add(channel.getId());
                    String physical = channel.physicalModel(logicalModel);
                    UpstreamRequest upstream = UpstreamRequest.chat(
                            channel.resolveBaseUrl(), decryptKey(channel), physical,
                            attemptTimeoutMs(resolveTimeout(request, channel), deadlineAt), request, null, null);

                    return withChannelSlot(channel, estimatedTokens, invokeChat(channel, upstream))
                            .doOnSuccess(r -> onSuccess(channel))
                            .map(r -> new ChatOutcome(r, channel, physical, logicalModel, attempt + 1))
                            .onErrorResume(e -> handleFailure(snapshot, modelChain, modelIndex, attempt, maxRetries,
                                    tried, channel, e, request, appIdKey, sessionKey, estimatedTokens, deadlineAt));
                })
                .onErrorResume(e -> degradeIfNoChannel(snapshot, modelChain, modelIndex, e,
                        () -> attemptChat(snapshot, modelChain, modelIndex + 1, 0, maxRetries,
                                new ArrayList<>(), request, appIdKey, sessionKey, estimatedTokens, deadlineAt)));
    }

    /**
     * 主模型的候选渠道**完全不可用**（未配 Key/全部禁用/全部熔断）时，直接落到降级链的下一个模型。
     *
     * 为什么需要这一步：渠道级重试只能在「有候选渠道」时发生。若主模型一个可用渠道都没有，
     * router.pick 会立刻抛错，此时若不接管，业务拿到的就是 503，而明明备用模型是健康的。
     * 这正是降级链存在的意义。
     */
    private boolean noChannelAvailable(Throwable error) {
        return error instanceof GatewayException ge
                && (ge.getCode() == ErrorCode.UPSTREAM_UNAVAILABLE || ge.getCode() == ErrorCode.CIRCUIT_OPEN);
    }

    private <T> Mono<T> degradeIfNoChannel(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                           Throwable error, java.util.function.Supplier<Mono<T>> degradeAction) {
        if (noChannelAvailable(error) && modelIndex < modelChain.size() - 1) {
            log.warn("模型 {} 无可用渠道（{}），降级到备用模型 {}", modelChain.get(modelIndex),
                    error.getMessage(), modelChain.get(modelIndex + 1));
            metrics.recordFallback(modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
            return degradeAction.get();
        }
        return Mono.error(error);
    }

    private Flux<ChatChunk> degradeStreamIfNoChannel(List<String> modelChain, int modelIndex,
                                                     Throwable error,
                                                     java.util.function.Supplier<Flux<ChatChunk>> degradeAction) {
        if (noChannelAvailable(error) && modelIndex < modelChain.size() - 1) {
            log.warn("模型 {} 无可用渠道（{}），流式请求降级到备用模型 {}", modelChain.get(modelIndex),
                    error.getMessage(), modelChain.get(modelIndex + 1));
            metrics.recordFallback(modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
            return degradeAction.get();
        }
        return Flux.error(error);
    }

    /**
     * 渠道级闸门（非流式 / 向量化）：并发 + RPM + TPM，一次 Redis 往返。
     *
     * 用 usingWhen 把「占用 -> 调用 -> 归还」绑成一体：无论调用是成功、失败还是被取消，
     * 归还动作一定会执行。这一点很关键 —— 若改成手工在成功分支里释放，
     * 任何一条异常路径都会造成并发额度泄漏。
     */
    private <T> Mono<T> withChannelSlot(Channel channel, int estimatedTokens, Mono<T> call) {
        if (!hasChannelGate(channel)) {
            return call;
        }
        return Mono.usingWhen(
                rateLimiterService.acquireChannelQuota(channel, estimatedTokens)
                        .map(RateLimiterService.Acquired::concurrencyKeys),
                keys -> call,
                rateLimiterService::release);
    }

    /** 流式版本：并发额度一直占到流终止（完成/出错/客户端取消）才归还。 */
    private Flux<ChatChunk> withChannelSlotFlux(Channel channel, int estimatedTokens, Flux<ChatChunk> call) {
        if (!hasChannelGate(channel)) {
            return call;
        }
        return Flux.usingWhen(
                rateLimiterService.acquireChannelQuota(channel, estimatedTokens)
                        .map(RateLimiterService.Acquired::concurrencyKeys),
                keys -> call,
                rateLimiterService::release);
    }

    /** 该渠道是否配置了任何一道闸门（并发 / RPM / TPM）且对应开关打开。 */
    private boolean hasChannelGate(Channel channel) {
        if (channel == null) {
            return false;
        }
        GatewayProperties.Defaults defaults = properties.getDefaults();
        boolean concurrency = defaults.isChannelConcurrencyEnabled()
                && channel.getConcurrencyLimit() != null && channel.getConcurrencyLimit() > 0;
        boolean quota = defaults.isChannelQuotaEnabled()
                && ((channel.getRpmLimit() != null && channel.getRpmLimit() > 0)
                    || (channel.getTpmLimit() != null && channel.getTpmLimit() > 0));
        return concurrency || quota;
    }

    private Mono<ChatResponse> invokeChat(Channel channel, UpstreamRequest upstream) {
        ModelProvider provider = providerRegistry.requireFor(channel.getProvider());
        return provider.chat(upstream);
    }

    private Mono<ChatOutcome> handleFailure(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                            int attempt, int maxRetries, List<Long> tried,
                                            Channel channel, Throwable error,
                                            ChatRequest request, String appIdKey, String sessionKey,
                                            int estimatedTokens, long deadlineAt) {
        UpstreamException ue = asUpstreamException(error);
        onFailure(channel, ue);

        boolean canRetrySameModel = ue.retryable() && attempt < maxRetries
                && canRetryWithinBudget(attempt, deadlineAt);
        // 预算耗尽时不再降级：下一次尝试只会立刻撞上总 deadline，
        // 白白多打一次上游（还会记一条并未真正发生的「降级」指标）。
        boolean canFallbackModel = modelIndex < modelChain.size() - 1 && !budgetExhausted(deadlineAt);

        if (canRetrySameModel) {
            log.warn("上游失败，换渠道重试: channel={}, attempt={}/{}, kind={}, msg={}",
                    channel.getId(), attempt + 1, maxRetries, ue.getKind(), ue.getMessage());
            // 轻微退避 + 抖动，避免瞬时重试打在同一个坏节点上
            long backoff = (long) properties.getDefaults().getRetryBackoffMs() * (attempt + 1);
            return Mono.delay(Duration.ofMillis(backoff + (long) (Math.random() * 50)))
                    .then(attemptChat(snapshot, modelChain, modelIndex, attempt + 1, maxRetries,
                            tried, request, appIdKey, sessionKey, estimatedTokens, deadlineAt))
                    // 重试时若该模型已无候选渠道：能降级就降级，否则透传**最初那次上游失败**。
                    // 若把 router 的「无可用渠道」直接抛出去，上游 429/500 会被误报成 503，
                    // 客户端会把「渠道被限流」读成「网关整体不可用」。
                    .onErrorResume(next -> {
                        if (!noChannelAvailable(next)) {
                            return Mono.error(next);
                        }
                        return canFallbackModel
                                ? degradeToNextModel(snapshot, modelChain, modelIndex, maxRetries, tried,
                                        request, appIdKey, sessionKey, estimatedTokens, deadlineAt)
                                : Mono.error(ue);
                    });
        }

        if (canFallbackModel) {
            return degradeToNextModel(snapshot, modelChain, modelIndex, maxRetries, tried,
                    request, appIdKey, sessionKey, estimatedTokens, deadlineAt);
        }

        return Mono.error(ue);
    }

    /** 换渠道重试耗尽后的模型降级：切到降级链的下一个模型，并重置已试渠道。 */
    private Mono<ChatOutcome> degradeToNextModel(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                                 int maxRetries, List<Long> tried,
                                                 ChatRequest request, String appIdKey, String sessionKey,
                                                 int estimatedTokens, long deadlineAt) {
        log.warn("渠道重试耗尽，降级到备用模型: {} -> {}",
                modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
        metrics.recordFallback(modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
        tried.clear();
        return attemptChat(snapshot, modelChain, modelIndex + 1, 0, maxRetries, tried,
                request, appIdKey, sessionKey, estimatedTokens, deadlineAt);
    }

    // ==================================================================
    // 流式：首字节后不重试
    // ==================================================================

    /**
     * 流式调用。
     * 重试只发生在「拿到 HTTP 2xx 之前」；一旦开始下发分片，任何错误都直接终止流，
     * 因为已发送的内容无法撤回，重试会产生重复输出。
     */
    public Flux<ChatChunk> chatStreamWithFailover(ConfigSnapshot snapshot, List<String> modelChain,
                                                  ChatRequest request, String appIdKey, String sessionKey,
                                                  java.util.concurrent.atomic.AtomicReference<Channel> channelRef,
                                                  java.util.concurrent.atomic.AtomicInteger attemptsRef,
                                                  java.util.concurrent.atomic.AtomicBoolean ttfbRef,
                                                  java.util.concurrent.atomic.AtomicReference<String> logicalModelRef,
                                                  int estimatedTokens) {
        int maxRetries = properties.getDefaults().getMaxRetries();
        List<Long> tried = new ArrayList<>();
        // 流式：总预算只约束「首字节之前」，所以这里传的是截止时刻，
        // 由每次尝试把它折算成 TTFB/idle 上限；第一块到达后不再受总预算限制。
        long deadlineAt = deadlineAt(request.timeoutMs());
        return streamAttempt(snapshot, modelChain, 0, 0, maxRetries, tried,
                request, appIdKey, sessionKey, channelRef, attemptsRef, ttfbRef, logicalModelRef,
                estimatedTokens, deadlineAt);
    }

    private Flux<ChatChunk> streamAttempt(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                          int attempt, int maxRetries, List<Long> tried,
                                          ChatRequest request, String appIdKey, String sessionKey,
                                          java.util.concurrent.atomic.AtomicReference<Channel> channelRef,
                                          java.util.concurrent.atomic.AtomicInteger attemptsRef,
                                          java.util.concurrent.atomic.AtomicBoolean ttfbRef,
                                          java.util.concurrent.atomic.AtomicReference<String> logicalModelRef,
                                          int estimatedTokens, long deadlineAt) {
        if (budgetExhausted(deadlineAt)) {
            return Flux.error(budgetExhaustedError());
        }
        String logicalModel = modelChain.get(modelIndex);
        logicalModelRef.set(logicalModel);
        RouteContext ctx = RouteContext.of(logicalModel, request.routing() == null ? null : request.routing().getStrategy(),
                appIdKey, sessionKey);

        return router.pick(snapshot, logicalModel, ctx, tried)
                .flatMapMany(channel -> {
                    tried.add(channel.getId());
                    channelRef.set(channel);
                    attemptsRef.incrementAndGet();

                    ModelProvider provider = providerRegistry.requireFor(channel.getProvider());
                    UpstreamRequest upstream = UpstreamRequest.chat(
                            channel.resolveBaseUrl(), decryptKey(channel), channel.physicalModel(logicalModel),
                            // 流式不做 min(单次上限, 剩余预算) 的收紧：首字节之后的逐块 idle 超时
                            // 仍由渠道自己的 timeout_ms 决定，总预算只用来约束「等到首字节」。
                            resolveTimeout(request, channel), request, null, null);

                    return withChannelSlotFlux(channel, estimatedTokens,
                            FirstByteBudget.enforce(provider.chatStream(upstream), deadlineAt,
                                    () -> new UpstreamException(UpstreamException.Kind.TIMEOUT,
                                            "流式请求在总预算内未收到首字节，已切换到其他渠道", null)))
                            .doOnNext(chunk -> {
                                // 首个分片到达即视为「已开始输出」，此后不再允许重试
                                if (ttfbRef.compareAndSet(false, true)) {
                                    onSuccess(channel);
                                }
                            })
                            .onErrorResume(e -> {
                                if (Boolean.TRUE.equals(ttfbRef.get())) {
                                    log.warn("流式传输中途失败，已输出部分内容，不重试: channel={}, msg={}",
                                            channel.getId(), e.getMessage());
                                    return Flux.error(asUpstreamException(e));
                                }
                                return recoverStream(snapshot, modelChain, modelIndex, attempt, maxRetries,
                                        tried, channel, e, request, appIdKey, sessionKey,
                                        channelRef, attemptsRef, ttfbRef, logicalModelRef, estimatedTokens, deadlineAt);
                            });
                })
                .onErrorResume(e -> degradeStreamIfNoChannel(modelChain, modelIndex, e,
                        () -> streamAttempt(snapshot, modelChain, modelIndex + 1, 0, maxRetries,
                                new ArrayList<>(), request, appIdKey, sessionKey,
                                channelRef, attemptsRef, ttfbRef, logicalModelRef, estimatedTokens, deadlineAt)));
    }

    private Flux<ChatChunk> recoverStream(ConfigSnapshot snapshot, List<String> modelChain, int modelIndex,
                                          int attempt, int maxRetries, List<Long> tried,
                                          Channel channel, Throwable error, ChatRequest request,
                                          String appIdKey, String sessionKey,
                                          java.util.concurrent.atomic.AtomicReference<Channel> channelRef,
                                          java.util.concurrent.atomic.AtomicInteger attemptsRef,
                                          java.util.concurrent.atomic.AtomicBoolean ttfbRef,
                                          java.util.concurrent.atomic.AtomicReference<String> logicalModelRef,
                                          int estimatedTokens, long deadlineAt) {
        UpstreamException ue = asUpstreamException(error);
        onFailure(channel, ue);

        boolean canFallbackModel = modelIndex < modelChain.size() - 1 && !budgetExhausted(deadlineAt);
        if (ue.retryable() && attempt < maxRetries && canRetryWithinBudget(attempt, deadlineAt)) {
            long backoff = (long) properties.getDefaults().getRetryBackoffMs() * (attempt + 1);
            return Mono.delay(Duration.ofMillis(backoff)).thenMany(
                            streamAttempt(snapshot, modelChain, modelIndex, attempt + 1, maxRetries, tried,
                                    request, appIdKey, sessionKey, channelRef, attemptsRef, ttfbRef,
                                    logicalModelRef, estimatedTokens, deadlineAt))
                    // 与非流式同理：无候选渠道时优先透传最初那次上游失败，避免 429 被误报成 503
                    .onErrorResume(next -> {
                        if (!noChannelAvailable(next)) {
                            return Flux.error(next);
                        }
                        return canFallbackModel
                                ? degradeStreamToNextModel(snapshot, modelChain, modelIndex, maxRetries, tried,
                                        request, appIdKey, sessionKey, channelRef, attemptsRef, ttfbRef,
                                        logicalModelRef, estimatedTokens, deadlineAt)
                                : Flux.error(ue);
                    });
        }
        if (canFallbackModel) {
            return degradeStreamToNextModel(snapshot, modelChain, modelIndex, maxRetries, tried,
                    request, appIdKey, sessionKey, channelRef, attemptsRef, ttfbRef, logicalModelRef,
                    estimatedTokens, deadlineAt);
        }
        return Flux.error(ue);
    }

    /** 流式版降级：切到降级链的下一个模型，并重置已试渠道。 */
    private Flux<ChatChunk> degradeStreamToNextModel(ConfigSnapshot snapshot, List<String> modelChain,
                                                    int modelIndex, int maxRetries, List<Long> tried,
                                                    ChatRequest request, String appIdKey, String sessionKey,
                                                    java.util.concurrent.atomic.AtomicReference<Channel> channelRef,
                                                    java.util.concurrent.atomic.AtomicInteger attemptsRef,
                                                    java.util.concurrent.atomic.AtomicBoolean ttfbRef,
                                                    java.util.concurrent.atomic.AtomicReference<String> logicalModelRef,
                                                    int estimatedTokens, long deadlineAt) {
        log.warn("渠道重试耗尽，流式请求降级到备用模型: {} -> {}", 
                modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
        metrics.recordFallback(modelChain.get(modelIndex), modelChain.get(modelIndex + 1));
        tried.clear();
        return streamAttempt(snapshot, modelChain, modelIndex + 1, 0, maxRetries, tried,
                request, appIdKey, sessionKey, channelRef, attemptsRef, ttfbRef, logicalModelRef,
                estimatedTokens, deadlineAt);
    }

    // ==================================================================
    // 向量化
    // ==================================================================

    public Mono<EmbeddingOutcome> embeddingWithFailover(ConfigSnapshot snapshot, String logicalModel,
                                                        EmbeddingRequest request, String appIdKey,
                                                        int estimatedTokens) {
        int maxRetries = properties.getDefaults().getMaxRetries();
        List<Long> tried = new ArrayList<>();
        long deadlineAt = deadlineAt(request.timeoutMs());
        return embeddingAttempt(snapshot, logicalModel, 0, maxRetries, tried, request, appIdKey, estimatedTokens,
                deadlineAt);
    }

    private Mono<EmbeddingOutcome> embeddingAttempt(ConfigSnapshot snapshot, String logicalModel, int attempt,
                                                    int maxRetries, List<Long> tried,
                                                    EmbeddingRequest request, String appIdKey,
                                                    int estimatedTokens, long deadlineAt) {
        if (budgetExhausted(deadlineAt)) {
            return Mono.error(budgetExhaustedError());
        }
        RouteContext ctx = RouteContext.of(logicalModel, null, appIdKey, null);
        return router.pick(snapshot, logicalModel, ctx, tried)
                .flatMap(channel -> {
                    tried.add(channel.getId());
                    ModelProvider provider = providerRegistry.requireFor(channel.getProvider());
                    UpstreamRequest upstream = UpstreamRequest.embedding(
                            channel.resolveBaseUrl(), decryptKey(channel), channel.physicalModel(logicalModel),
                            attemptTimeoutMs(resolveTimeout(request, channel), deadlineAt), request, null, null);
                    return withChannelSlot(channel, estimatedTokens, provider.embedding(upstream))
                            .doOnNext(r -> onSuccess(channel))
                            .map(r -> new EmbeddingOutcome(r, channel, channel.physicalModel(logicalModel),
                                    logicalModel, attempt + 1))
                            .onErrorResume(e -> {
                                UpstreamException ue = asUpstreamException(e);
                                onFailure(channel, ue);
                                if (ue.retryable() && attempt < maxRetries
                                        && canRetryWithinBudget(attempt, deadlineAt)) {
                                    return Mono.delay(Duration.ofMillis(properties.getDefaults().getRetryBackoffMs()))
                                            .then(embeddingAttempt(snapshot, logicalModel, attempt + 1, maxRetries,
                                                    tried, request, appIdKey, estimatedTokens, deadlineAt));
                                }
                                return Mono.error(ue);
                            });
                });
    }

    // ==================================================================
    // 公共钩子
    // ==================================================================

    private void onSuccess(Channel channel) {
        circuitRegistry.onSuccess(channel.getId());
    }

    private void onFailure(Channel channel, UpstreamException ue) {
        // 归因：只有上游自身的问题才计入该渠道的失败率。
        // 渠道饱和是本网关闸门拦下的（请求没发出去），若计入就会出现
        //「网关自己限流 → 把自己健康的渠道熔断」的荒谬结果。
        if (ue.countsTowardCircuit()) {
            circuitRegistry.onError(channel.getId(), ue);
        }
        if (ue.shouldTripCircuit()) {
            circuitRegistry.breaker(channel.getId()).transitionToOpenState();
            cooldownTracker.cool(channel.getId(), ue.getKind().name()).subscribe();
            log.error("渠道配额耗尽，已熔断并进入冷却: channel={}", channel.getId());
        } else if (ue.isChannelConfigurationError()) {
            // 密钥失效 / 模型映射错误：熔断要攒够样本才开，但渠道此刻已经在持续失败，
            // 直接按完整冷却窗口把流量让给其他渠道（修好前不再接客）。
            cooldownTracker.cool(channel.getId(), "CONFIG_" + ue.getUpstreamStatus()).subscribe();
            log.error("渠道配置类错误，已冷却: channel={}, upstreamStatus={}, msg={}",
                    channel.getId(), ue.getUpstreamStatus(), ue.getMessage());
        } else if (ue.getKind() == UpstreamException.Kind.RETRYABLE
                || ue.getKind() == UpstreamException.Kind.TIMEOUT) {
            // 偶发失败不足以熔断，但先冷却一小段，避免继续往坏节点打流量
            cooldownTracker.cool(channel.getId(), ue.getKind().name(), 10).subscribe();
        }
    }

    private UpstreamException asUpstreamException(Throwable error) {
        if (error instanceof UpstreamException ue) {
            return ue;
        }
        if (error instanceof GatewayException ge) {
            // 渠道并发闸门返回的 429 是「网关侧背压」，语义上可换渠道重试，
            // 但不能算作该渠道的健康问题（见 countsTowardCircuit）。
            if (ge.getCode() == ErrorCode.RATE_LIMITED) {
                return new UpstreamException(UpstreamException.Kind.CHANNEL_SATURATED,
                        ge.getMessage(), error, null, null, ge.getRetryAfter());
            }
            return new UpstreamException(UpstreamException.Kind.NON_RETRYABLE, ge.getMessage(), error);
        }
        // 传输层异常（超时/连接失败/读中断）由 UpstreamException 统一归一，
        // 避免「Reactor 超时消息里没有 timeout 字样」这类误判散落在调用方。
        return UpstreamException.fromTransportError(error);
    }

    /** 解密渠道密钥。解密失败说明 master-key 变更，属于配置事故，直接明确报错。 */
    private String decryptKey(Channel channel) {
        if (channel.getApiKeyEnc() == null || channel.getApiKeyEnc().isBlank()) {
            throw new GatewayException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "渠道 " + channel.getName() + " 尚未配置上游密钥，已跳过路由");
        }
        return secretCipher.decrypt(channel.getApiKeyEnc());
    }

    private Integer resolveTimeout(ChatRequest request, Channel channel) {
        if (request.timeoutMs() != null) {
            return request.timeoutMs();
        }
        return channel.timeoutMsOr(properties.getDefaults().getRequestTimeoutMs());
    }

    private int resolveTimeout(EmbeddingRequest request, Channel channel) {
        if (request.timeoutMs() != null && request.timeoutMs() > 0) {
            return request.timeoutMs();
        }
        return channel.timeoutMsOr(properties.getDefaults().getRequestTimeoutMs());
    }

    // ==================================================================
    // 总 deadline
    // ==================================================================

    /**
     * 整条请求链的总预算截止时刻。
     *
     * 业务在请求里声明了 timeout_ms 就以它为准（那是业务愿意等待的上限），
     * 否则用默认 request-timeout-ms 兜底。**重试与模型降级共享同一份预算** ——
     * 否则 max-retries=2 时最坏耗时约等于 3 倍单次超时，业务侧看到的是「越重试越慢」，
     * 与类注释承诺的总 deadline 不符（对照 litellm 的 request_timeout 语义）。
     */
    private long deadlineAt(Integer requestTimeoutMs) {
        long total = requestTimeoutMs != null && requestTimeoutMs > 0
                ? requestTimeoutMs : properties.getDefaults().getRequestTimeoutMs();
        return System.currentTimeMillis() + total;
    }

    /**
     * 剩余预算是否还够一次「退避 + 重试」。
     *
     * 只判断「还有没有时间」是不够的：退避本身就要花掉 {@code retryBackoffMs * (attempt+1)}，
     * 若剩余预算连退避都不够，重试必然刚发出就撞上总 deadline，白白多打一次上游
     * （流式场景还会让客户端多等一个退避周期）。这里直接把这种情况挡在重试之前。
     */
    private boolean canRetryWithinBudget(int attempt, long deadlineAt) {
        long remaining = deadlineAt - System.currentTimeMillis();
        return remaining > (long) properties.getDefaults().getRetryBackoffMs() * (attempt + 1);
    }

    /** 预算耗尽（剩余 <= 0ms）时不再发起新的尝试，避免 0 超时的无意义调用。 */
    private boolean budgetExhausted(long deadlineAt) {
        return deadlineAt - System.currentTimeMillis() <= 0;
    }

    /** 本次尝试可用的超时：min(单次上限, 剩余预算)，二者都不能被突破。 */
    private int attemptTimeoutMs(int singleLimitMs, long deadlineAt) {
        long remaining = deadlineAt - System.currentTimeMillis();
        return (int) Math.max(1L, Math.min(singleLimitMs, remaining));
    }

    private UpstreamException budgetExhaustedError() {
        return new UpstreamException(UpstreamException.Kind.TIMEOUT,
                "请求总超时预算已耗尽，已停止重试与降级", null);
    }

}

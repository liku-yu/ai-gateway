package com.gateway.filter;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 责任链装配。
 * Spring 自动收集所有 {@link GatewayFilter} 实现并按 order 排序；
 * 新增环节只需实现接口 + 声明 order，装配代码零改动。
 */
@Slf4j
@Component
public class FilterChainFactory {

    private final List<GatewayFilter> filters;
    private final RequestFinalizer finalizer;

    public FilterChainFactory(List<GatewayFilter> discovered, RequestFinalizer finalizer) {
        this.finalizer = finalizer;
        this.filters = discovered.stream()
                .sorted(Comparator.comparingInt(GatewayFilter::order))
                .collect(Collectors.toList());
        log.info("责任链已装配: {}", this.filters.stream()
                .map(f -> f.order() + ":" + f.getClass().getSimpleName())
                .collect(Collectors.joining(" -> ")));
    }

    /**
     * 执行整条链，并保证**恰好终结一次**。
     *
     * 为什么兜底放在这里而不是各过滤器内部：终结动作（退还预扣、释放并发、落日志）
     * 只有链的调度者才知道「整条链是否已经结束」。把它放在链尾有两个好处：
     *   1. 任何环节抛错（含准入之后、上游调用之前）都会被兜住，不再泄漏资源；
     *   2. 顺序天然正确：先跑完链，再终结，不会出现「终结后还在调用上游」的错序。
     *
     * 幂等由 RequestContext.finalized 保证，因此这里的终结对正常路径是空操作，
     * 不会与 UpstreamCallFilter 内部的正常终结重复执行。
     */
    public Mono<RequestContext> execute(RequestContext ctx) {
        return proceed(0, ctx)
                .onErrorResume(e -> finalizeQuietly(ctx, toErrorCode(e)).then(Mono.error(e)))
                // 客户端在响应产出前断连时，Reactor 只会发出取消信号（没有 error），
                // 若不在取消路径兜底，预扣费与并发额度同样会被静默泄漏。
                .doOnCancel(() -> finalizeQuietly(ctx, ErrorCode.INTERNAL_ERROR).subscribe());
    }

    /** 兜底终结：以「未成功交付响应」结算，任何异常都不得掩盖原始错误。 */
    private Mono<Void> finalizeQuietly(RequestContext ctx, ErrorCode errorCode) {
        return finalizer.finalizeOnce(ctx, errorCode)
                .onErrorResume(e -> {
                    log.error("兜底终结失败（预扣费/并发额度可能需人工核对）: traceId={}, err={}",
                            ctx.getTraceId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /** 把链上抛出的异常翻译成统一错误码，供审计与指标使用。 */
    private ErrorCode toErrorCode(Throwable error) {
        if (error instanceof GatewayException ge) {
            return ge.getCode();
        }
        if (error instanceof com.gateway.adapter.UpstreamException ue) {
            return ue.toErrorCode();
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private Mono<RequestContext> proceed(int index, RequestContext ctx) {
        if (index >= filters.size()) {
            return Mono.just(ctx);
        }
        GatewayFilter current = filters.get(index);
        if (!current.enabled(ctx)) {
            return proceed(index + 1, ctx);
        }
        return current.filter(ctx, next -> proceed(index + 1, next));
    }

    public List<String> describe() {
        return filters.stream().map(f -> f.order() + ":" + f.getClass().getSimpleName()).toList();
    }
}

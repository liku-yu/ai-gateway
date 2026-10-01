package com.gateway.adapter;

import com.gateway.protocol.ChatChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 只约束**首字节**的总预算守卫。
 *
 * 为什么不能用 {@code .timeout(remaining)} 一把梭：那是「元素间隔」超时，
 * 流式响应的每个分片间隔都会被它管住，正常的长时间生成会被总预算掐断。
 * 这里把 nextTimeout 设为 {@link Mono#never()}，即「首字节之后不再超时」，
 * 后续空闲检测交还给 provider 自己的逐块 idle 超时。
 */
final class FirstByteBudget {

    private FirstByteBudget() {
    }

    /**
     * @param source     上游流
     * @param deadlineAt 总预算截止时刻（毫秒时间戳）
     * @param onTimeout  超时错误工厂：每次触发都构造一个**新**异常，避免复用同一个实例
     */
    static Flux<ChatChunk> enforce(Flux<ChatChunk> source, long deadlineAt,
                                   Supplier<UpstreamException> onTimeout) {
        return Flux.defer(() -> {
            long remaining = deadlineAt - System.currentTimeMillis();
            if (remaining <= 0) {
                return Flux.error(onTimeout.get());
            }
            return source.timeout(
                    Mono.delay(Duration.ofMillis(remaining)),
                    chunk -> Mono.never(),
                    Flux.error(onTimeout.get()));
        });
    }
}

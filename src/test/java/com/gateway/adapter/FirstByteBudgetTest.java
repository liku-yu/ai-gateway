package com.gateway.adapter;

import com.gateway.protocol.ChatChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式总预算的语义锁定：**只约束首字节**。
 * 这是「流式长时间生成不能被总预算掐断」这一承诺的唯一保障，必须单独测住。
 */
class FirstByteBudgetTest {

    private static ChatChunk chunk(String id) {
        ChatChunk c = new ChatChunk();
        c.setId(id);
        return c;
    }

    private static UpstreamException timeout() {
        return new UpstreamException(UpstreamException.Kind.TIMEOUT, "首字节超时", null);
    }

    @Test
    @DisplayName("首字节迟迟不来：到总预算立即超时，不会一直挂着")
    void firstChunkBeyondBudgetTimesOut() {
        long deadlineAt = System.currentTimeMillis() + 80;

        StepVerifier.withVirtualTime(() -> FirstByteBudget.enforce(Flux.never(), deadlineAt, FirstByteBudgetTest::timeout))
                .thenAwait(Duration.ofMillis(200))
                .expectErrorSatisfies(e -> assertThat(((UpstreamException) e).getKind())
                        .isEqualTo(UpstreamException.Kind.TIMEOUT))
                .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("首字节已到达：后续分片再慢也不再受总预算约束，交还给 provider 的 idle 超时")
    void noBudgetTimeoutAfterFirstChunk() {
        long deadlineAt = System.currentTimeMillis() + 50;
        Flux<ChatChunk> source = Flux.concat(Mono.just(chunk("first")), Flux.never());

        StepVerifier.withVirtualTime(() -> FirstByteBudget.enforce(source, deadlineAt, FirstByteBudgetTest::timeout))
                .thenAwait(Duration.ofMillis(10))
                .expectNextMatches(c -> "first".equals(c.getId()))
                // 远超总预算的时间窗口内不应再有任何超时事件
                .thenAwait(Duration.ofSeconds(5))
                .expectNoEvent(Duration.ofSeconds(1))
                .thenCancel()
                .verify(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("预算在订阅前就已耗尽：立即失败，不订阅上游")
    void expiredBudgetFailsWithoutSubscribing() {
        java.util.concurrent.atomic.AtomicBoolean subscribed = new java.util.concurrent.atomic.AtomicBoolean(false);
        Flux<ChatChunk> source = Flux.defer(() -> {
            subscribed.set(true);
            return Flux.never();
        });

        StepVerifier.create(FirstByteBudget.enforce(source, System.currentTimeMillis() - 1, FirstByteBudgetTest::timeout))
                .expectError(UpstreamException.class)
                .verify(Duration.ofSeconds(2));

        assertThat(subscribed).isFalse();
    }
}

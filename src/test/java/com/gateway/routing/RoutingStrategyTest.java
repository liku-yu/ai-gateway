package com.gateway.routing;

import com.gateway.domain.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 路由策略测试：验证每种策略的选择语义，不依赖 Spring 与外部组件。 */
class RoutingStrategyTest {

    private Channel channel(long id, int weight, int priority) {
        Channel c = new Channel();
        c.setId(id);
        c.setName("ch-" + id);
        c.setWeight(weight);
        c.setPriority(priority);
        c.setStatus("ACTIVE");
        return c;
    }

    @Test
    @DisplayName("优先级策略应选中 priority 最小的渠道")
    void priorityPicksLowest() {
        Channel a = channel(1, 100, 5);
        Channel b = channel(2, 100, 1);
        Channel c = channel(3, 100, 9);

        var selected = new PriorityStrategy().select(List.of(a, b, c), RouteContext.of("m", null, "1", null));

        assertThat(selected).contains(b);
    }

    @Test
    @DisplayName("最少连接策略应选中在途请求数最少的渠道")
    void leastConnectionsPicksIdlest() {
        Channel a = channel(1, 100, 0);
        Channel b = channel(2, 100, 0);
        RouteContext ctx = new RouteContext("m", null, "1", null, true, null,
                Map.of(1L, 7, 2L, 1), Map.of());

        var selected = new LeastConnectionsStrategy().select(List.of(a, b), ctx);

        assertThat(selected).contains(b);
    }

    @Test
    @DisplayName("成本最优策略应选单价最低的渠道")
    void cheapestPicksLowestCost() {
        Channel a = channel(1, 100, 0);
        Channel b = channel(2, 100, 0);
        RouteContext ctx = new RouteContext("m", null, "1", null, true, null, Map.of(),
                Map.of(1L, new BigDecimal("0.010"), 2L, new BigDecimal("0.001")));

        var selected = new CheapestStrategy().select(List.of(a, b), ctx);

        assertThat(selected).contains(b);
    }

    @Test
    @DisplayName("一致性 Hash 对同一 sessionKey 必须稳定命中同一渠道")
    void hashIsStableForSameKey() {
        List<Channel> candidates = List.of(channel(1, 100, 0), channel(2, 100, 0), channel(3, 100, 0));
        ConsistentHashStrategy strategy = new ConsistentHashStrategy();

        Long first = strategy.select(candidates, RouteContext.of("m", null, "app", "session-abc"))
                .orElseThrow().getId();
        for (int i = 0; i < 20; i++) {
            assertThat(strategy.select(candidates, RouteContext.of("m", null, "app", "session-abc"))
                    .orElseThrow().getId()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("加权随机应覆盖所有权重非零的渠道")
    void weightedCoversAllChannels() {
        Channel a = channel(1, 50, 0);
        Channel b = channel(2, 50, 0);
        WeightedRandomStrategy strategy = new WeightedRandomStrategy();
        boolean sawA = false;
        boolean sawB = false;

        for (int i = 0; i < 200 && !(sawA && sawB); i++) {
            Long id = strategy.select(List.of(a, b), RouteContext.of("m", null, "1", null)).orElseThrow().getId();
            sawA |= id == 1L;
            sawB |= id == 2L;
        }

        assertThat(sawA).isTrue();
        assertThat(sawB).isTrue();
    }

    @Test
    @DisplayName("空候选集应返回空，交由上层报错")
    void emptyCandidatesYieldsEmpty() {
        assertThat(new WeightedRandomStrategy().select(List.of(), RouteContext.of("m", null, "1", null)))
                .isEmpty();
        assertThat(new PriorityStrategy().select(List.of(), RouteContext.of("m", null, "1", null)))
                .isEmpty();
    }
}

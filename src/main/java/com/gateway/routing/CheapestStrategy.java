package com.gateway.routing;

import com.gateway.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 成本最优：在满足质量前提下优先选单位 token 更便宜的渠道。 */
@Component
public class CheapestStrategy implements RoutingStrategy {

    @Override
    public String code() {
        return "cheapest";
    }

    @Override
    public Optional<Channel> select(List<Channel> candidates, RouteContext ctx) {
        return candidates.stream()
                .min(Comparator.comparing(ctx::costOf)
                        .thenComparingInt(c -> c.priorityOr(Integer.MAX_VALUE)));
    }
}

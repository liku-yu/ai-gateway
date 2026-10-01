package com.gateway.routing;

import com.gateway.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 优先级 + 故障转移：主渠道优先，配合重试自然形成主备顺序。 */
@Component
public class PriorityStrategy implements RoutingStrategy {

    @Override
    public String code() {
        return "priority";
    }

    @Override
    public Optional<Channel> select(List<Channel> candidates, RouteContext ctx) {
        return candidates.stream()
                .min(Comparator.comparingInt(c -> c.priorityOr(Integer.MAX_VALUE)));
    }
}

package com.gateway.routing;

import com.gateway.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 最少连接：长请求/流式场景下避免把流量压在同一个渠道上。 */
@Component
public class LeastConnectionsStrategy implements RoutingStrategy {

    @Override
    public String code() {
        return "leastconn";
    }

    @Override
    public Optional<Channel> select(List<Channel> candidates, RouteContext ctx) {
        return candidates.stream()
                .min(Comparator.comparingInt(ctx::inFlightOf)
                        .thenComparingInt(c -> c.priorityOr(Integer.MAX_VALUE)));
    }
}

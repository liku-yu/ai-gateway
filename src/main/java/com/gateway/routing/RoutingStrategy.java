package com.gateway.routing;

import com.gateway.domain.Channel;

import java.util.List;
import java.util.Optional;

/** 路由策略（策略模式）：从候选渠道中选一个。 */
public interface RoutingStrategy {

    /** 策略标识，与请求 extra_body.routing.strategy 对应。 */
    String code();

    Optional<Channel> select(List<Channel> candidates, RouteContext ctx);
}

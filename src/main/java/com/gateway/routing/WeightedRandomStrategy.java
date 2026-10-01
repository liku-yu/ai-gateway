package com.gateway.routing;

import com.gateway.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** 加权随机：多 Key 分流的默认策略，权重可按剩余额度动态调整。 */
@Component
public class WeightedRandomStrategy implements RoutingStrategy {

    @Override
    public String code() {
        return "weighted";
    }

    @Override
    public Optional<Channel> select(List<Channel> candidates, RouteContext ctx) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int total = candidates.stream().mapToInt(c -> c.weightOr(100)).sum();
        if (total <= 0) {
            return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (Channel c : candidates) {
            cumulative += c.weightOr(100);
            if (roll < cumulative) {
                return Optional.of(c);
            }
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }
}

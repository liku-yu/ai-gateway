package com.gateway.routing;

import com.gateway.domain.Channel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * 一致性 Hash：按 sessionKey 固定渠道。
 * 目的不是负载均衡，而是**复用上游 prompt cache**（同一会话落到同一渠道命中率更高）。
 */
@Component
public class ConsistentHashStrategy implements RoutingStrategy {

    @Override
    public String code() {
        return "hash";
    }

    @Override
    public Optional<Channel> select(List<Channel> candidates, RouteContext ctx) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String key = ctx.sessionKey() != null ? ctx.sessionKey() : String.valueOf(ctx.appIdKey());
        int index = Math.floorMod(hash(key), candidates.size());
        return Optional.of(candidates.get(index));
    }

    private int hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
        } catch (Exception e) {
            return key.hashCode();
        }
    }
}

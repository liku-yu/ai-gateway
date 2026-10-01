package com.gateway.infra;

import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.domain.Channel;
import com.gateway.domain.LogicalModel;
import com.gateway.domain.Price;
import com.gateway.domain.Provider;

import java.util.List;
import java.util.Map;

/**
 * 配置快照：一次刷新产出的不可变视图，热路径只读它。
 * 使用 volatile 整体替换，保证请求线程读到的永远是一份自洽的数据。
 */
public record ConfigSnapshot(
        long version,
        long loadedAt,
        Map<String, LogicalModel> models,
        Map<Long, Provider> providers,
        Map<Long, Channel> channels,
        /** 逻辑模型 -> 支持它的渠道列表（已按 priority 排序）。 */
        Map<String, List<Channel>> candidates,
        /** 虚拟 Key 的 sha256 -> ApiKey。 */
        Map<String, ApiKey> keysByHash,
        Map<Long, App> apps,
        /** provider|model -> 当前生效定价。 */
        Map<String, Price> prices
) {
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, 0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public LogicalModel model(String logicalName) {
        return models.get(logicalName);
    }

    public List<Channel> candidatesOf(String logicalName) {
        return candidates.getOrDefault(logicalName, List.of());
    }

    public Price price(String provider, String model) {
        return prices.get(priceKey(provider, model));
    }

    public static String priceKey(String provider, String model) {
        return provider + "|" + model;
    }

    public int channelCount() {
        return channels.size();
    }

    public int keyCount() {
        return keysByHash.size();
    }
}

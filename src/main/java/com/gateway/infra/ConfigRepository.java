package com.gateway.infra;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.domain.Channel;
import com.gateway.domain.LogicalModel;
import com.gateway.domain.Price;
import com.gateway.domain.Provider;
import com.gateway.domain.mapper.ApiKeyMapper;
import com.gateway.domain.mapper.AppMapper;
import com.gateway.domain.mapper.ChannelMapper;
import com.gateway.domain.mapper.LogicalModelMapper;
import com.gateway.domain.mapper.PriceMapper;
import com.gateway.domain.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从 DB 装配配置快照。
 * 只在配置刷新（冷路径）和兜底定时刷新时被调用，请求线程绝不触碰。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConfigRepository {

    private final ProviderMapper providerMapper;
    private final ChannelMapper channelMapper;
    private final LogicalModelMapper logicalModelMapper;
    private final AppMapper appMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final PriceMapper priceMapper;

    public ConfigSnapshot load(long version) {
        Map<Long, Provider> providers = providerMapper.selectList(null).stream()
                .collect(Collectors.toMap(Provider::getId, Function.identity()));

        List<Channel> rawChannels = channelMapper.selectList(null);
        Map<Long, Channel> channels = new HashMap<>(rawChannels.size());
        for (Channel ch : rawChannels) {
            ch.setProvider(providers.get(ch.getProviderId()));
            channels.put(ch.getId(), ch);
        }

        Map<String, LogicalModel> models = logicalModelMapper.selectList(null).stream()
                .collect(Collectors.toMap(LogicalModel::getLogicalName, Function.identity(), (a, b) -> a));

        Map<String, List<Channel>> candidates = buildCandidates(models, channels);

        Map<String, ApiKey> keys = apiKeyMapper.selectList(null).stream()
                .collect(Collectors.toMap(ApiKey::getKeyHash, Function.identity(), (a, b) -> a));

        Map<Long, App> apps = appMapper.selectList(null).stream()
                .collect(Collectors.toMap(App::getId, Function.identity(), (a, b) -> a));

        Map<String, Price> prices = loadEffectivePrices();

        ConfigSnapshot snapshot = new ConfigSnapshot(
                version, System.currentTimeMillis(), models, providers, channels, candidates, keys, apps, prices);
        log.info("配置快照已刷新: version={}, providers={}, channels={}, models={}, keys={}, prices={}",
                version, providers.size(), channels.size(), models.size(), keys.size(), prices.size());
        return snapshot;
    }

    private Map<String, List<Channel>> buildCandidates(Map<String, LogicalModel> models, Map<Long, Channel> channels) {
        Map<String, List<Channel>> result = new HashMap<>();
        for (String logicalName : models.keySet()) {
            List<Channel> matched = new ArrayList<>();
            for (Channel ch : channels.values()) {
                if (ch.getProvider() == null || !ch.getProvider().isAvailable() || !ch.supports(logicalName)) {
                    continue;
                }
                matched.add(ch);
            }
            matched.sort(Comparator.comparingInt(c -> c.priorityOr(Integer.MAX_VALUE)));
            result.put(logicalName, List.copyOf(matched));
        }
        return result;
    }

    /** 定价带时效，取当前时刻生效、effective_from 最新的一条。 */
    private Map<String, Price> loadEffectivePrices() {
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<Price> qw = new QueryWrapper<Price>()
                .le("effective_from", now)
                .and(w -> w.isNull("effective_to").or().gt("effective_to", now))
                .orderByAsc("effective_from");
        Map<String, Price> latest = new HashMap<>();
        for (Price p : priceMapper.selectList(qw)) {
            latest.put(ConfigSnapshot.priceKey(p.getProvider(), p.getModel()), p);
        }
        return latest;
    }
}

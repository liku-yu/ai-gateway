package com.gateway.adapter;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器策略注册表。
 *
 * 解析顺序（兼容两种配置风格）：
 * 1. gw_provider.adapter_class（显式指定实现，适合同一协议多家厂商复用的场景）；
 * 2. gw_provider.code（约定式：一个 code 对应一个适配器）。
 * 两者都注册到同一张表，避免「配了 adapter_class 却不生效」的坑。
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final Map<String, ModelProvider> adapters = new HashMap<>();
    private final java.util.Set<String> codes = new java.util.LinkedHashSet<>();

    public ProviderRegistry(List<ModelProvider> discovered) {
        for (ModelProvider provider : discovered) {
            adapters.put(provider.code(), provider);
            codes.add(provider.code());
            String simple = provider.getClass().getSimpleName();
            adapters.put(simple, provider);
            adapters.put(Character.toLowerCase(simple.charAt(0)) + simple.substring(1), provider);
            // 小写化同义词：DB 里手写 code 大小写不一致时也能解析到
            for (String alias : provider.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    adapters.put(alias.trim(), provider);
                    adapters.put(alias.trim().toLowerCase(), provider);
                }
            }
        }
        log.info("已注册上游适配器: {}", adapters.keySet());
    }

    /** 已注册的适配器 canonical code（去重、稳定顺序），供 CLI/健康检查展示。 */
    public java.util.Set<String> adapterCodes() {
        return java.util.Collections.unmodifiableSet(codes);
    }

    /** 按 provider code 解析（大小写不敏感）。 */
    public ModelProvider require(String code) {
        ModelProvider provider = code == null ? null : adapters.get(code);
        if (provider == null && code != null) {
            provider = adapters.get(code.trim());
            if (provider == null) {
                provider = adapters.get(code.trim().toLowerCase());
            }
        }
        if (provider == null) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "未注册的上游适配器: " + code);
        }
        return provider;
    }

    /**
     * 按渠道解析适配器：优先 adapter_class，回退 provider code。
     * 这样同一家上游的多个渠道可以共用实现，也可以让不同协议走不同实现。
     */
    public ModelProvider requireFor(com.gateway.domain.Provider provider) {
        if (provider == null) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, "渠道缺少供应商配置");
        }
        String adapterClass = provider.getAdapterClass();
        if (adapterClass != null && !adapterClass.isBlank()) {
            ModelProvider byClass = adapters.get(adapterClass.trim());
            if (byClass != null) {
                return byClass;
            }
            log.warn("adapter_class={} 未找到对应实现，回退按 provider code={} 解析",
                    adapterClass, provider.getCode());
        }
        return require(provider.getCode());
    }

    public boolean supports(String code) {
        return adapters.containsKey(code);
    }
}

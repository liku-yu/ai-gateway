package com.gateway.adapter;

import com.gateway.protocol.Capability;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Set;

/**
 * Azure OpenAI 适配器。
 *
 * 与标准 OpenAI 的两点差异：
 * <ol>
 *   <li>鉴权头是 {@code api-key} 而不是 {@code Authorization: Bearer}；</li>
 *   <li>路径带 deployment 与 api-version：{@code {base}/openai/deployments/{deployment}/chat/completions?api-version=...}，
 *       其中 deployment 用渠道模型映射里的「物理模型名」表达。</li>
 * </ol>
 *
 * baseUrl 约定：
 * <ul>
 *   <li>推荐填资源根地址，如 {@code https://my-res.openai.azure.com}；</li>
 *   <li>若 baseUrl 已包含 {@code /deployments/}，则直接在其后拼路径；</li>
 *   <li>若已带 {@code api-version=}，则不再追加。</li>
 * </ul>
 * api-version 默认 {@value #DEFAULT_API_VERSION}，也可写在 baseUrl 的 query 里覆盖。
 */
@Component
public class AzureOpenAiProvider extends AbstractOpenAiCompatibleProvider {

    private static final String DEFAULT_API_VERSION = "2024-10-21";

    public AzureOpenAiProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        super(webClient);
    }

    @Override
    public String code() {
        return "azure";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("azure-openai", "azureopenai");
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.TOOL_CALL,
                Capability.STREAM, Capability.VISION);
    }

    @Override
    protected Map<String, String> authHeaders(String apiKey) {
        return Map.of("api-key", apiKey == null ? "" : apiKey);
    }

    @Override
    protected String url(UpstreamRequest request, String path) {
        String base = trimBase(request.baseUrl());
        boolean hasVersion = base.contains("api-version=");
        String version = hasVersion ? "" : (base.contains("?") ? "&api-version=" : "?api-version=") + DEFAULT_API_VERSION;

        if (base.contains("/deployments/")) {
            return base + path + version;
        }
        return base + "/openai/deployments/" + request.physicalModel() + path + version;
    }

    private static String trimBase(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=azure");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}

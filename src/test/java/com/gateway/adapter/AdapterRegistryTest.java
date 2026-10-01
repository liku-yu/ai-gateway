package com.gateway.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 适配器注册与别名解析：新增供应商时最容易出错的一环。 */
class AdapterRegistryTest {

    private ProviderRegistry registry() {
        // WebClient 传 null：本测试只验证 code/aliases 解析，不发起网络调用
        return new ProviderRegistry(List.of(
                new OpenAiProvider(null),
                new QwenProvider(null),
                new DeepSeekProvider(null),
                new OllamaProvider(null),
                new CustomOpenAiProvider(null),
                new AzureOpenAiProvider(null),
                new AnthropicProvider(null),
                new GeminiProvider(null),
                new OpenAiResponsesProvider(null)));
    }

    @Test
    @DisplayName("内置适配器均可按 code 解析")
    void resolvesByCode() {
        ProviderRegistry r = registry();
        assertThat(r.require("openai").code()).isEqualTo("openai");
        assertThat(r.require("qwen").code()).isEqualTo("qwen");
        assertThat(r.require("deepseek").code()).isEqualTo("deepseek");
        assertThat(r.require("ollama").code()).isEqualTo("ollama");
        assertThat(r.require("anthropic").code()).isEqualTo("anthropic");
        assertThat(r.require("gemini").code()).isEqualTo("gemini");
        assertThat(r.require("azure").code()).isEqualTo("azure");
        assertThat(r.require("openai-responses").code()).isEqualTo("openai-responses");
        assertThat(r.require("openai-compatible").code()).isEqualTo("openai-compatible");
    }

    @Test
    @DisplayName("别名与大小写都能解析到同一实现")
    void resolvesAliases() {
        ProviderRegistry r = registry();
        assertThat(r.require("claude").code()).isEqualTo("anthropic");
        assertThat(r.require("google").code()).isEqualTo("gemini");
        assertThat(r.require("custom").code()).isEqualTo("openai-compatible");
        assertThat(r.require("AZURE").code()).isEqualTo("azure");
        assertThat(r.require("Anthropic").code()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("canonical code 列表稳定且不包含别名")
    void adapterCodes() {
        assertThat(registry().adapterCodes())
                .contains("openai", "anthropic", "gemini", "azure", "openai-responses", "openai-compatible")
                .doesNotContain("claude", "google", "custom");
    }
}

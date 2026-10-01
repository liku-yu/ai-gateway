package com.gateway.billing;

import com.gateway.domain.Price;
import com.gateway.protocol.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt Cache 计费的语义测试。
 *
 * 覆盖两种主流口径：
 * - OpenAI：{@code prompt_tokens} 含 {@code cached_tokens}（子集），无缓存写；
 * - Anthropic：{@code input_tokens} / cache read / cache write 三项之和才是输入总量。
 */
class CacheBillingTest {

    /** 输入 1 元/1K，输出 2 元/1K，缓存读 0.1 元/1K，缓存写 1.25 元/1K。 */
    private Price price() {
        Price p = new Price();
        p.setProvider("p");
        p.setModel("m");
        p.setInputPrice(new BigDecimal("1.000000"));
        p.setOutputPrice(new BigDecimal("2.000000"));
        p.setCacheReadPrice(new BigDecimal("0.100000"));
        p.setCacheWritePrice(new BigDecimal("1.250000"));
        p.setCurrency("CNY");
        return p;
    }

    @Test
    @DisplayName("OpenAI 口径：cached_tokens 是 prompt 的子集，按缓存读价结算")
    void openAiSemantics() {
        Usage usage = new Usage(1000, 100, null, false);
        usage.setCachedTokens(800);
        usage.normalize();

        // 标准输入 200 -> 0.2；缓存读 800 -> 0.08；输出 100 -> 0.2
        assertThat(usage.standardInputOrZero()).isEqualTo(200);
        assertThat(price().cost(usage)).isEqualByComparingTo(new BigDecimal("0.480000"));
    }

    @Test
    @DisplayName("Anthropic 口径：prompt 为三项之和，标准输入只减缓存读与写")
    void anthropicSemantics() {
        // input=100, cache_read=700, cache_write=200，总量 1000
        Usage usage = new Usage(1000, 100, null, false);
        usage.setCachedTokens(700);
        usage.setCacheCreationTokens(200);
        usage.normalize();

        // 标准输入 100 -> 0.1；读 700 -> 0.07；写 200 -> 0.25；输出 100 -> 0.2
        assertThat(usage.standardInputOrZero()).isEqualTo(100);
        assertThat(price().cost(usage)).isEqualByComparingTo(new BigDecimal("0.620000"));
    }

    @Test
    @DisplayName("归一化能读取 OpenAI 的 prompt_tokens_details.cached_tokens")
    void parsesOpenAiDetails() {
        Usage usage = new Usage();
        usage.setPromptTokens(500);
        usage.setCompletionTokens(10);
        usage.setRaw(new java.util.LinkedHashMap<>(java.util.Map.of(
                "prompt_tokens_details", java.util.Map.of("cached_tokens", 300))));

        usage.normalize();

        assertThat(usage.cachedOrZero()).isEqualTo(300);
        assertThat(usage.standardInputOrZero()).isEqualTo(200);
        assertThat(usage.getTotalTokens()).isEqualTo(510);
    }

    @Test
    @DisplayName("归一化能读取 Anthropic 的 cache_read_input_tokens / cache_creation_input_tokens")
    void parsesAnthropicFields() {
        Usage usage = new Usage();
        usage.setPromptTokens(1000);
        usage.setCompletionTokens(10);
        usage.setRaw(new java.util.LinkedHashMap<>(java.util.Map.of(
                "cache_read_input_tokens", 600,
                "cache_creation_input_tokens", 100)));

        usage.normalize();

        assertThat(usage.cachedOrZero()).isEqualTo(600);
        assertThat(usage.cacheCreationOrZero()).isEqualTo(100);
        assertThat(usage.standardInputOrZero()).isEqualTo(300);
    }

    @Test
    @DisplayName("脏数据（cached > prompt）不得算出负的标准输入或负成本")
    void toleratesDirtyUsage() {
        Usage usage = new Usage(100, 0, null, false);
        usage.setCachedTokens(500);
        usage.normalize();

        assertThat(usage.standardInputOrZero()).isZero();
        assertThat(price().cost(usage)).isEqualByComparingTo(new BigDecimal("0.050000"));
    }

    @Test
    @DisplayName("序列化不泄漏 raw 容器，同时保留上游原始字段")
    void serializationKeepsPassthroughWithoutRawLeak() {
        Usage usage = com.gateway.infra.JsonSupport.read("""
                {"prompt_tokens":100,"completion_tokens":5,
                 "prompt_tokens_details":{"cached_tokens":80}}
                """, Usage.class);
        usage.normalize();

        String out = com.gateway.infra.JsonSupport.write(usage);

        assertThat(out).doesNotContain("\"raw\"");
        assertThat(out).contains("prompt_tokens_details").contains("cached_tokens");
    }

    @Test
    @DisplayName("未配置缓存价时，缓存读写按 0 计，不因缺列拒绝请求")
    void missingCachePriceIsFree() {
        Price p = price();
        p.setCacheReadPrice(null);
        p.setCacheWritePrice(null);
        Usage usage = new Usage(1000, 100, null, false);
        usage.setCachedTokens(800);
        usage.setCacheCreationTokens(200);
        usage.normalize();

        // 标准输入 0 -> 0；输出 100 -> 0.2
        assertThat(p.cost(usage)).isEqualByComparingTo(new BigDecimal("0.200000"));
    }
}

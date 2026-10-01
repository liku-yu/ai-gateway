package com.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三家非 OpenAI 协议的 usage / 终止原因翻译。
 * 这是缓存计费正确性的关键：prompt 必须为「输入总量」，cached/creation 单列。
 */
class UsageTranslationTest {

    private static JsonNode json(String s) {
        return JsonSupport.readTree(s);
    }

    @Test
    @DisplayName("Anthropic：prompt = input + cache_read + cache_creation，标准输入= input")
    void anthropicUsage() {
        Usage u = AnthropicProvider.usageOf(json("""
                {"input_tokens":100,"output_tokens":20,
                 "cache_read_input_tokens":700,"cache_creation_input_tokens":200}
                """));

        assertThat(u.promptOrZero()).isEqualTo(1000);
        assertThat(u.completionOrZero()).isEqualTo(20);
        assertThat(u.cachedOrZero()).isEqualTo(700);
        assertThat(u.cacheCreationOrZero()).isEqualTo(200);
        assertThat(u.standardInputOrZero()).isEqualTo(100);
    }

    @Test
    @DisplayName("OpenAI Responses：cached_tokens 是 prompt 的子集")
    void responsesUsage() {
        Usage u = OpenAiResponsesProvider.usageOf(json("""
                {"input_tokens":1000,"output_tokens":50,
                 "input_tokens_details":{"cached_tokens":800}}
                """));

        assertThat(u.promptOrZero()).isEqualTo(1000);
        assertThat(u.cachedOrZero()).isEqualTo(800);
        assertThat(u.standardInputOrZero()).isEqualTo(200);
    }

    @Test
    @DisplayName("Gemini：promptTokenCount 已含 cachedContentTokenCount")
    void geminiUsage() {
        Usage u = GeminiProvider.usageOf(json("""
                {"promptTokenCount":1000,"candidatesTokenCount":50,"cachedContentTokenCount":600}
                """));

        assertThat(u.promptOrZero()).isEqualTo(1000);
        assertThat(u.cachedOrZero()).isEqualTo(600);
        assertThat(u.standardInputOrZero()).isEqualTo(400);
    }

    @Test
    @DisplayName("终止原因映射")
    void finishReasons() {
        assertThat(AnthropicProvider.mapStopReason("end_turn")).isEqualTo("stop");
        assertThat(AnthropicProvider.mapStopReason("max_tokens")).isEqualTo("length");
        assertThat(AnthropicProvider.mapStopReason("tool_use")).isEqualTo("tool_calls");

        assertThat(GeminiProvider.mapFinish("STOP")).isEqualTo("stop");
        assertThat(GeminiProvider.mapFinish("MAX_TOKENS")).isEqualTo("length");
        assertThat(GeminiProvider.mapFinish("SAFETY")).isEqualTo("content_filter");

        assertThat(OpenAiResponsesProvider.mapFinish("incomplete")).isEqualTo("length");
        assertThat(OpenAiResponsesProvider.mapFinish("completed")).isEqualTo("stop");
    }
}

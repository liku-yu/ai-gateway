package com.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.Message;
import com.gateway.protocol.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Anthropic Messages 入站/出站互转。 */
class AnthropicMessagesTest {

    private static JsonNode json(String s) {
        return JsonSupport.readTree(s);
    }

    @Test
    @DisplayName("入站：system/max_tokens/消息/工具定义正确映射")
    void inboundMapping() {
        ChatRequest req = AnthropicMessages.toChatRequest(json("""
                {
                  "model": "chat-default",
                  "max_tokens": 128,
                  "system": "你是助手",
                  "messages": [
                    {"role":"user","content":"你好"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"SH"}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"晴"}]}
                  ],
                  "tools":[{"name":"get_weather","description":"查天气","input_schema":{"type":"object"}}]
                }
                """));

        assertThat(req.getModel()).isEqualTo("chat-default");
        assertThat(req.getMaxTokens()).isEqualTo(128);
        assertThat(req.getMessages()).hasSize(4);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(req.getMessages().get(0).contentAsText()).isEqualTo("你是助手");
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("assistant");
        assertThat(req.getMessages().get(2).getToolCalls().path(0).path("function").path("name").asText())
                .isEqualTo("get_weather");
        assertThat(req.getMessages().get(3).getRole()).isEqualTo("tool");
        assertThat(req.getMessages().get(3).getToolCallId()).isEqualTo("toolu_1");
        assertThat(req.passthrough()).containsKey("tools");
    }

    @Test
    @DisplayName("出站：文本 + stop_reason + usage 缓存字段正确还原")
    void outboundMapping() {
        ChatResponse response = new ChatResponse();
        response.setId("chatcmpl-1");
        response.setModel("gpt-4o-mini");
        Message message = new Message();
        message.setRole("assistant");
        message.setContent(JsonSupport.mapper().getNodeFactory().textNode("你好呀"));
        ChatResponse.ChatChoice choice = new ChatResponse.ChatChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason("stop");
        response.setChoices(List.of(choice));
        Usage usage = new Usage(1000, 50, 1050, false);
        usage.setCachedTokens(800);
        response.setUsage(usage);

        JsonNode out = AnthropicMessages.toAnthropicResponse(response, "chat-default");

        assertThat(out.path("type").asText()).isEqualTo("message");
        assertThat(out.path("model").asText()).isEqualTo("chat-default");
        assertThat(out.path("content").path(0).path("type").asText()).isEqualTo("text");
        assertThat(out.path("content").path(0).path("text").asText()).isEqualTo("你好呀");
        assertThat(out.path("stop_reason").asText()).isEqualTo("end_turn");
        // 标准输入 = 1000 - 800 = 200
        assertThat(out.path("usage").path("input_tokens").asInt()).isEqualTo(200);
        assertThat(out.path("usage").path("output_tokens").asInt()).isEqualTo(50);
        assertThat(out.path("usage").path("cache_read_input_tokens").asInt()).isEqualTo(800);
    }

    @Test
    @DisplayName("流式：事件序列为 message_start -> block -> delta* -> block_stop -> message_delta -> message_stop")
    void streamEventOrder() {
        ChatChunk c1 = chunk("a");
        ChatChunk c2 = chunk("b");
        ChatChunk last = new ChatChunk();
        last.setChoices(List.of());
        Usage u = new Usage(500, 10, 510, false);
        last.setUsage(u);

        List<ServerSentEvent<String>> events = AnthropicMessages
                .streamEvents(Flux.just(c1, c2, last), "chat-default")
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message_start", "content_block_start",
                        "content_block_delta", "content_block_delta",
                        "content_block_stop", "message_delta", "message_stop");
        assertThat(events.get(2).data()).contains("text_delta").contains("a");
    }

    private static ChatChunk chunk(String text) {
        ChatChunk c = new ChatChunk();
        c.setChoices(List.of());
        ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
        choice.setIndex(0);
        ChatChunk.Delta d = new ChatChunk.Delta();
        d.setContent(text);
        choice.setDelta(d);
        c.setChoices(List.of(choice));
        return c;
    }
}

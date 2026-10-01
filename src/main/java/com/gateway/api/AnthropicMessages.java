package com.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.Message;
import com.gateway.protocol.Usage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Messages 与网关内部 OpenAI 兼容 IR 的互转。
 *
 * 让 Claude Code / Anthropic SDK 可以直接把 {@code ANTHROPIC_BASE_URL} 指向网关，
 * 网关再按模型映射路由到任意上游（包括非 Anthropic 的模型）。
 *
 * 覆盖：system、多轮消息、图片（base64/data URL）、tool_use / tool_result、流式文本与 usage。
 * 边界：Anthropic 的 thinking 分片、server-side 工具、prompt caching 标记（cache_control）
 * 暂不透传，作为后续增强项。
 */
public final class AnthropicMessages {

    private AnthropicMessages() {
    }

    // ==================================================================
    // 入站：Anthropic -> ChatRequest
    // ==================================================================

    public static ChatRequest toChatRequest(JsonNode body) {
        ChatRequest req = new ChatRequest();
        req.setModel(body.path("model").asText(null));
        if (body.hasNonNull("max_tokens")) {
            req.setMaxTokens(body.get("max_tokens").asInt());
        }
        if (body.hasNonNull("temperature")) {
            req.setTemperature(body.get("temperature").asDouble());
        }
        if (body.hasNonNull("top_p")) {
            req.setTopP(body.get("top_p").asDouble());
        }
        if (body.hasNonNull("stream")) {
            req.setStream(body.get("stream").asBoolean());
        }
        if (body.hasNonNull("stop_sequences")) {
            req.setStop(JsonSupport.mapper().convertValue(body.get("stop_sequences"), Object.class));
        }

        List<Message> messages = new ArrayList<>();
        if (body.has("system")) {
            String system = systemText(body.get("system"));
            if (!system.isEmpty()) {
                messages.add(Message.text("system", system));
            }
        }
        for (JsonNode m : body.path("messages")) {
            appendMessage(messages, m);
        }
        req.setMessages(messages);

        // 工具定义：Anthropic tools -> OpenAI tools（放进 passthrough 原样转发）
        if (body.has("tools")) {
            ArrayNode tools = JsonSupport.mapper().createArrayNode();
            for (JsonNode t : body.get("tools")) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.path("name").asText(""));
                if (t.hasNonNull("description")) {
                    fn.put("description", t.get("description").asText());
                }
                fn.set("parameters", t.hasNonNull("input_schema")
                        ? t.get("input_schema") : JsonSupport.mapper().createObjectNode());
            }
            req.putPassthrough("tools", JsonSupport.mapper().convertValue(tools, Object.class));
        }
        return req;
    }

    private static void appendMessage(List<Message> out, JsonNode m) {
        String role = m.path("role").asText("user");
        JsonNode content = m.path("content");
        if (content.isTextual()) {
            out.add(Message.text(role, content.asText()));
            return;
        }
        if (!content.isArray()) {
            return;
        }
        ArrayNode parts = JsonSupport.mapper().createArrayNode();
        ArrayNode toolCalls = JsonSupport.mapper().createArrayNode();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> parts.addObject().put("type", "text").put("text", block.path("text").asText(""));
                case "image" -> {
                    String url = imageToDataUrl(block);
                    if (url != null && !url.isBlank()) {
                        parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
                    }
                }
                case "tool_use" -> {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", block.path("id").asText(""));
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", block.path("name").asText(""));
                    fn.put("arguments", block.path("input").toString());
                }
                case "tool_result" -> {
                    Message tool = new Message();
                    tool.setRole("tool");
                    tool.setToolCallId(block.path("tool_use_id").asText(""));
                    tool.setContent(JsonSupport.mapper().getNodeFactory()
                            .textNode(toolResultText(block.path("content"))));
                    out.add(tool);
                }
                default -> {
                    // 未知分片（thinking 等）忽略
                }
            }
        }
        if (!parts.isEmpty() || !toolCalls.isEmpty()) {
            Message msg = new Message();
            msg.setRole(role);
            msg.setContent(parts.isEmpty()
                    ? JsonSupport.mapper().getNodeFactory().textNode("") : parts);
            if (!toolCalls.isEmpty()) {
                msg.setToolCalls(toolCalls);
            }
            out.add(msg);
        }
    }

    private static String systemText(JsonNode system) {
        if (system == null || system.isNull()) {
            return "";
        }
        if (system.isTextual()) {
            return system.asText();
        }
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : system) {
                if (block.hasNonNull("text")) {
                    sb.append(block.get("text").asText());
                }
            }
            return sb.toString();
        }
        return system.asText("");
    }

    private static String toolResultText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.hasNonNull("text")) {
                    sb.append(block.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String imageToDataUrl(JsonNode block) {
        JsonNode source = block.path("source");
        String type = source.path("type").asText("");
        if ("base64".equals(type)) {
            return "data:" + source.path("media_type").asText("image/png")
                    + ";base64," + source.path("data").asText("");
        }
        if ("url".equals(type)) {
            return source.path("url").asText("");
        }
        return null;
    }

    // ==================================================================
    // 出站：ChatResponse -> Anthropic
    // ==================================================================

    public static ObjectNode toAnthropicResponse(ChatResponse response, String requestedModel) {
        ObjectNode out = JsonSupport.mapper().createObjectNode();
        out.put("id", response.getId() == null ? "msg_" + shortId() : response.getId());
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", requestedModel == null ? response.getModel() : requestedModel);

        ArrayNode content = out.putArray("content");
        String finishReason = "stop";
        Message message = null;
        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            ChatResponse.ChatChoice choice = response.getChoices().get(0);
            message = choice.getMessage();
            if (choice.getFinishReason() != null) {
                finishReason = choice.getFinishReason();
            }
        }
        if (message != null) {
            String text = message.contentAsText();
            if (text != null && !text.isEmpty()) {
                content.addObject().put("type", "text").put("text", text);
            }
            if (message.getToolCalls() != null && message.getToolCalls().isArray()) {
                for (JsonNode tc : message.getToolCalls()) {
                    ObjectNode use = content.addObject();
                    use.put("type", "tool_use");
                    use.put("id", tc.path("id").asText("toolu_" + shortId()));
                    use.put("name", tc.path("function").path("name").asText(""));
                    String args = tc.path("function").path("arguments").asText("{}");
                    try {
                        use.set("input", JsonSupport.mapper().readTree(args));
                    } catch (Exception e) {
                        use.set("input", JsonSupport.mapper().createObjectNode());
                    }
                }
            }
        }
        out.put("stop_reason", mapFinishToAnthropic(finishReason));
        out.putNull("stop_sequence");
        out.set("usage", anthropicUsage(response.getUsage()));
        return out;
    }

    private static ObjectNode anthropicUsage(Usage usage) {
        ObjectNode u = JsonSupport.mapper().createObjectNode();
        int cached = usage == null ? 0 : usage.cachedOrZero();
        int creation = usage == null ? 0 : usage.cacheCreationOrZero();
        int prompt = usage == null ? 0 : usage.promptOrZero();
        int input = Math.max(0, prompt - cached - creation);
        u.put("input_tokens", input);
        u.put("output_tokens", usage == null ? 0 : usage.completionOrZero());
        if (cached > 0) {
            u.put("cache_read_input_tokens", cached);
        }
        if (creation > 0) {
            u.put("cache_creation_input_tokens", creation);
        }
        return u;
    }

    private static String mapFinishToAnthropic(String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "end_turn";
        };
    }

    // ==================================================================
    // 流式：Flux<ChatChunk> -> Anthropic SSE
    // ==================================================================

    public static Flux<ServerSentEvent<String>> streamEvents(Flux<ChatChunk> chunks, String requestedModel) {
        return Flux.defer(() -> {
            AtomicReference<Usage> usageRef = new AtomicReference<>();
            AtomicReference<String> finishRef = new AtomicReference<>("end_turn");

            Flux<ServerSentEvent<String>> head = Flux.just(
                    sse("message_start", messageStart(requestedModel)),
                    sse("content_block_start", contentBlockStart()));

            Flux<ServerSentEvent<String>> body = chunks.concatMap(chunk -> {
                List<ServerSentEvent<String>> events = new ArrayList<>();
                if (chunk.getChoices() != null) {
                    for (ChatChunk.ChunkChoice choice : chunk.getChoices()) {
                        if (choice.getDelta() != null && choice.getDelta().getContent() != null
                                && !choice.getDelta().getContent().isEmpty()) {
                            events.add(sse("content_block_delta", textDelta(choice.getDelta().getContent())));
                        }
                        if (choice.getFinishReason() != null) {
                            finishRef.set(mapFinishToAnthropic(choice.getFinishReason()));
                        }
                    }
                }
                if (chunk.getUsage() != null) {
                    usageRef.set(chunk.getUsage());
                }
                return Flux.fromIterable(events);
            });

            Flux<ServerSentEvent<String>> tail = Flux.defer(() -> Flux.just(
                    sse("content_block_stop", simple("content_block_stop", "index", 0)),
                    sse("message_delta", messageDelta(finishRef.get(), usageRef.get())),
                    sse("message_stop", simple("message_stop", null, 0))));

            return Flux.concat(head, body, tail);
        });
    }

    private static ObjectNode messageStart(String requestedModel) {
        ObjectNode root = JsonSupport.mapper().createObjectNode();
        root.put("type", "message_start");
        ObjectNode message = root.putObject("message");
        message.put("id", "msg_" + shortId());
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", requestedModel == null ? "" : requestedModel);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        ObjectNode usage = message.putObject("usage");
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        return root;
    }

    private static ObjectNode contentBlockStart() {
        ObjectNode root = JsonSupport.mapper().createObjectNode();
        root.put("type", "content_block_start");
        root.put("index", 0);
        ObjectNode block = root.putObject("content_block");
        block.put("type", "text");
        block.put("text", "");
        return root;
    }

    private static ObjectNode textDelta(String text) {
        ObjectNode root = JsonSupport.mapper().createObjectNode();
        root.put("type", "content_block_delta");
        root.put("index", 0);
        ObjectNode delta = root.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", text);
        return root;
    }

    private static ObjectNode messageDelta(String stopReason, Usage usage) {
        ObjectNode root = JsonSupport.mapper().createObjectNode();
        root.put("type", "message_delta");
        ObjectNode delta = root.putObject("delta");
        delta.put("stop_reason", stopReason);
        delta.putNull("stop_sequence");
        root.set("usage", anthropicUsage(usage));
        return root;
    }

    private static ObjectNode simple(String type, String key, int value) {
        ObjectNode root = JsonSupport.mapper().createObjectNode();
        root.put("type", type);
        if (key != null) {
            root.put(key, value);
        }
        return root;
    }

    private static ServerSentEvent<String> sse(String event, ObjectNode data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(JsonSupport.write(data))
                .build();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}

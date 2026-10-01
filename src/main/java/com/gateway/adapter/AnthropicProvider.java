package com.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.Capability;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingResponse;
import com.gateway.protocol.Message;
import com.gateway.protocol.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Anthropic Messages 适配器。
 *
 * 上游说 Anthropic 协议、而网关内部用 OpenAI 兼容 IR，因此这里做双向翻译：
 * <ul>
 *   <li>请求：system 合并为顶层 system，tool 消息合并为 user 轮的 tool_result，assistant 的
 *       tool_calls 转 tool_use，图片按 data URL 转 base64 / 否则转 url；</li>
 *   <li>响应：content 的 text/tool_use 还原为 OpenAI 的 message + tool_calls；</li>
 *   <li>流式：把 message_start / content_block_delta / message_delta / message_stop
 *       映射为 OpenAI 的 delta 分片，并在结尾补 usage；</li>
 *   <li>usage：Anthropic 的 input/read/creation 三项合并为规范口径，缓存字段被正确计入缓存计费。</li>
 * </ul>
 *
 * 已知边界：Anthropic 的 thinking 分片与 server-side 工具暂不透传，作为后续增强项。
 */
@Component
public class AnthropicProvider implements ModelProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final WebClient webClient;

    public AnthropicProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String code() {
        return "anthropic";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("claude", "anthropic-messages");
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.TOOL_CALL, Capability.STREAM, Capability.VISION);
    }

    // ==================================================================
    // 非流式
    // ==================================================================

    @Override
    public Mono<ChatResponse> chat(UpstreamRequest request) {
        ObjectNode body = buildMessageBody(request, false);
        return webClient.post()
                .uri(url(request))
                .headers(h -> authHeaders(request.apiKey()).forEach(h::add))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(b -> Mono.error(UpstreamException.from(resp.statusCode(), b)));
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(json -> toChatResponse(json, request));
                })
                .timeout(Duration.ofMillis(timeoutMs(request)));
    }

    // ==================================================================
    // 流式
    // ==================================================================

    @Override
    public Flux<ChatChunk> chatStream(UpstreamRequest request) {
        ObjectNode body = buildMessageBody(request, true);
        return Flux.defer(() -> {
            StreamState st = new StreamState();
            return webClient.post()
                    .uri(url(request))
                    .headers(h -> authHeaders(request.apiKey()).forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .exchangeToFlux(resp -> {
                        if (!resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMapMany(b -> Flux.error(UpstreamException.from(resp.statusCode(), b)));
                        }
                        return resp.bodyToFlux(String.class);
                    })
                    .flatMapIterable(this::splitDataLines)
                    .concatMap(line -> parseEvent(line, st, request))
                    .timeout(Duration.ofMillis(timeoutMs(request)))
                    .onErrorMap(this::mapError);
        });
    }

    @Override
    public Mono<EmbeddingResponse> embedding(UpstreamRequest request) {
        return Mono.error(new UpstreamException(UpstreamException.Kind.NON_RETRYABLE,
                "Anthropic 不提供向量化接口", null, 400, null));
    }

    // ==================================================================
    // 请求翻译
    // ==================================================================

    private ObjectNode buildMessageBody(UpstreamRequest request, boolean stream) {
        ChatRequest req = request.chatRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());
        body.put("max_tokens", req.getMaxTokens() != null ? req.getMaxTokens() : DEFAULT_MAX_TOKENS);
        body.put("stream", stream);

        List<String> systems = new ArrayList<>();
        ArrayNode messages = body.putArray("messages");
        ArrayNode pendingToolResults = null;

        for (Message m : req.getMessages()) {
            String role = m.getRole();
            if ("system".equals(role)) {
                systems.add(m.contentAsText());
                continue;
            }
            if ("tool".equals(role)) {
                // Anthropic 要求 tool_result 位于 user 轮；连续的 tool 消息合并成一个 user 轮
                if (pendingToolResults == null) {
                    ObjectNode user = messages.addObject();
                    user.put("role", "user");
                    pendingToolResults = user.putArray("content");
                }
                ObjectNode tr = pendingToolResults.addObject();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", m.getToolCallId() == null ? "" : m.getToolCallId());
                tr.put("content", m.contentAsText());
                continue;
            }
            pendingToolResults = null;
            ObjectNode node = messages.addObject();
            node.put("role", "assistant".equals(role) ? "assistant" : "user");
            node.set("content", convertContent(m));
        }

        if (!systems.isEmpty()) {
            body.put("system", String.join("\n\n", systems));
        }
        if (req.getTemperature() != null) {
            body.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            body.put("top_p", req.getTopP());
        }
        if (req.getStop() != null) {
            body.set("stop_sequences", normalizeStop(req.getStop()));
        }
        return body;
    }

    private ArrayNode convertContent(Message m) {
        ArrayNode arr = JsonSupport.mapper().createArrayNode();
        JsonNode content = m.getContent();
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if (part.hasNonNull("text")) {
                    arr.addObject().put("type", "text").put("text", part.get("text").asText());
                } else if ("image_url".equals(part.path("type").asText())) {
                    addImage(arr, imageUrlOf(part));
                }
            }
        } else {
            arr.addObject().put("type", "text").put("text", m.contentAsText());
        }
        if (m.getToolCalls() != null && m.getToolCalls().isArray()) {
            for (JsonNode tc : m.getToolCalls()) {
                ObjectNode use = arr.addObject();
                use.put("type", "tool_use");
                use.put("id", tc.path("id").asText(""));
                use.put("name", tc.path("function").path("name").asText(""));
                String args = tc.path("function").path("arguments").asText("{}");
                try {
                    use.set("input", JsonSupport.mapper().readTree(args));
                } catch (Exception e) {
                    use.set("input", JsonSupport.mapper().createObjectNode());
                }
            }
        }
        return arr;
    }

    private static String imageUrlOf(JsonNode part) {
        JsonNode imageUrl = part.path("image_url");
        if (imageUrl.isTextual()) {
            return imageUrl.asText();
        }
        String url = imageUrl.path("url").asText(null);
        return url == null ? imageUrl.asText("") : url;
    }

    private static void addImage(ArrayNode arr, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (url.startsWith("data:image/")) {
            int comma = url.indexOf(',');
            if (comma < 0) {
                return;
            }
            String meta = url.substring(5, comma);
            String mediaType = meta.split(";")[0];
            String data = url.substring(comma + 1);
            ObjectNode img = arr.addObject();
            img.put("type", "image");
            ObjectNode src = img.putObject("source");
            src.put("type", "base64");
            src.put("media_type", mediaType);
            src.put("data", data);
        } else {
            ObjectNode img = arr.addObject();
            img.put("type", "image");
            ObjectNode src = img.putObject("source");
            src.put("type", "url");
            src.put("url", url);
        }
    }

    private static ArrayNode normalizeStop(Object stop) {
        ArrayNode arr = JsonSupport.mapper().createArrayNode();
        if (stop instanceof List<?> list) {
            list.forEach(s -> arr.add(String.valueOf(s)));
        } else {
            arr.add(String.valueOf(stop));
        }
        return arr;
    }

    // ==================================================================
    // 响应翻译
    // ==================================================================

    private ChatResponse toChatResponse(String json, UpstreamRequest request) {
        JsonNode root = JsonSupport.readTree(json);
        ChatResponse response = new ChatResponse();
        response.setId(root.path("id").asText("msg-" + shortId()));
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(root.path("model").asText(request.physicalModel()));

        Message message = new Message();
        message.setRole("assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = JsonSupport.mapper().createArrayNode();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", block.path("id").asText(""));
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", block.path("name").asText(""));
                fn.put("arguments", block.path("input").toString());
            }
        }
        message.setContent(JsonSupport.mapper().getNodeFactory().textNode(text.toString()));
        if (!toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }

        ChatResponse.ChatChoice choice = new ChatResponse.ChatChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(mapStopReason(root.path("stop_reason").asText("")));
        response.setChoices(List.of(choice));
        response.setUsage(usageOf(root.path("usage")));
        return response;
    }

    /** Anthropic usage -> 规范口径：prompt = input + cache_read + cache_creation。 */
    static Usage usageOf(JsonNode usage) {
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int read = usage.path("cache_read_input_tokens").asInt(0);
        int creation = usage.path("cache_creation_input_tokens").asInt(0);
        int prompt = input + read + creation;
        Usage u = new Usage(prompt, output, prompt + output, false);
        u.setCachedTokens(read);
        u.setCacheCreationTokens(creation);
        return u;
    }

    static String mapStopReason(String reason) {
        return switch (reason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    // ==================================================================
    // 流式翻译
    // ==================================================================

    /** 每次订阅独立的状态，避免并发请求串味。 */
    private static final class StreamState {
        int inputTokens;
        int cacheRead;
        int cacheCreation;
        int outputTokens;
        String stopReason = "end_turn";
        boolean toolOpen;
        String toolId = "";
        String toolName = "";
        int toolIndex;
        final StringBuilder toolJson = new StringBuilder();
    }

    private Flux<ChatChunk> parseEvent(String line, StreamState st, UpstreamRequest request) {
        JsonNode node;
        try {
            node = JsonSupport.mapper().readTree(line);
        } catch (Exception e) {
            return Flux.empty();
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "message_start" -> {
                JsonNode usage = node.path("message").path("usage");
                st.inputTokens = usage.path("input_tokens").asInt(0);
                st.cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                st.cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                ChatChunk c = newChunk(request);
                c.getChoices().add(delta("assistant", "", null, null, null));
                return Flux.just(c);
            }
            case "content_block_start" -> {
                JsonNode cb = node.path("content_block");
                if ("tool_use".equals(cb.path("type").asText())) {
                    st.toolOpen = true;
                    st.toolId = cb.path("id").asText("");
                    st.toolName = cb.path("name").asText("");
                    st.toolIndex = node.path("index").asInt(0);
                    st.toolJson.setLength(0);
                }
                return Flux.empty();
            }
            case "content_block_delta" -> {
                JsonNode delta = node.path("delta");
                String dt = delta.path("type").asText();
                if ("text_delta".equals(dt)) {
                    ChatChunk c = newChunk(request);
                    c.getChoices().add(delta(null, delta.path("text").asText(""), null, null, null));
                    return Flux.just(c);
                }
                if ("input_json_delta".equals(dt)) {
                    st.toolJson.append(delta.path("partial_json").asText(""));
                }
                return Flux.empty();
            }
            case "content_block_stop" -> {
                if (st.toolOpen) {
                    st.toolOpen = false;
                    ChatChunk c = newChunk(request);
                    c.getChoices().add(delta(null, null, st.toolId, st.toolName, st.toolJson.toString()));
                    return Flux.just(c);
                }
                return Flux.empty();
            }
            case "message_delta" -> {
                JsonNode delta = node.path("delta");
                if (delta.hasNonNull("stop_reason")) {
                    st.stopReason = delta.path("stop_reason").asText(st.stopReason);
                }
                if (node.path("usage").hasNonNull("output_tokens")) {
                    st.outputTokens = node.path("usage").path("output_tokens").asInt(st.outputTokens);
                }
                return Flux.empty();
            }
            case "message_stop" -> {
                ChatChunk c = newChunk(request);
                ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
                choice.setIndex(0);
                choice.setDelta(new ChatChunk.Delta());
                choice.setFinishReason(mapStopReason(st.stopReason));
                c.getChoices().add(choice);
                int prompt = st.inputTokens + st.cacheRead + st.cacheCreation;
                Usage u = new Usage(prompt, st.outputTokens, prompt + st.outputTokens, false);
                u.setCachedTokens(st.cacheRead);
                u.setCacheCreationTokens(st.cacheCreation);
                c.setUsage(u);
                return Flux.just(c);
            }
            default -> {
                return Flux.empty();
            }
        }
    }

    private ChatChunk newChunk(UpstreamRequest request) {
        ChatChunk c = new ChatChunk();
        c.setId("chatcmpl-" + shortId());
        c.setCreated(Instant.now().getEpochSecond());
        c.setModel(request.physicalModel());
        c.setChoices(new ArrayList<>());
        return c;
    }

    private ChatChunk.ChunkChoice delta(String role, String content, String toolId, String toolName, String toolArgs) {
        ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
        choice.setIndex(0);
        ChatChunk.Delta d = new ChatChunk.Delta();
        if (role != null) {
            d.setRole(role);
        }
        if (content != null && !content.isEmpty()) {
            d.setContent(content);
        }
        if (toolId != null) {
            ArrayNode calls = JsonSupport.mapper().createArrayNode();
            ObjectNode call = calls.addObject();
            call.put("index", 0);
            call.put("id", toolId);
            call.put("type", "function");
            ObjectNode fn = call.putObject("function");
            fn.put("name", toolName);
            fn.put("arguments", toolArgs == null ? "" : toolArgs);
            d.setToolCalls(calls);
        }
        choice.setDelta(d);
        return choice;
    }

    // ==================================================================
    // 公共
    // ==================================================================

    private String url(UpstreamRequest request) {
        String base = request.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=anthropic");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // Anthropic 的 base 是根地址（对应 ANTHROPIC_BASE_URL），路径固定 /v1/messages
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/messages";
        }
        return trimmed + "/v1/messages";
    }

    private java.util.Map<String, String> authHeaders(String apiKey) {
        return java.util.Map.of(
                "x-api-key", apiKey == null ? "" : apiKey,
                "anthropic-version", ANTHROPIC_VERSION);
    }

    private int timeoutMs(UpstreamRequest request) {
        return request.timeoutMs() == null ? 30000 : request.timeoutMs();
    }

    /** 只保留 data 行里的 JSON，忽略 event/id/retry/注释。 */
    private List<String> splitDataLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith(":") || s.startsWith("event:")
                    || s.startsWith("id:") || s.startsWith("retry:")) {
                continue;
            }
            if (s.startsWith("data:")) {
                s = s.substring(5).trim();
            }
            if (s.startsWith("{")) {
                out.add(s);
            }
        }
        return out;
    }

    private Throwable mapError(Throwable t) {
        if (t instanceof UpstreamException) {
            return t;
        }
        return UpstreamException.fromTransportError(t);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}

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
 * OpenAI Responses API（{@code /v1/responses}）适配器。
 *
 * 用于接入只提供 Responses 协议的上游（新版 OpenAI、Codex 后端、部分中转站）。
 * 把内部 Chat 请求翻译为 input/instructions，响应再还原为 chat.completion。
 * usage 的 input_tokens_details.cached_tokens 计入缓存读。
 *
 * 流式处理 {@code response.output_text.delta} 与 {@code response.completed}。
 *
 * 已知边界：Responses 的 tools/function_call、reasoning 输出暂不透传。
 */
@Component
public class OpenAiResponsesProvider implements ModelProvider {

    private final WebClient webClient;

    public OpenAiResponsesProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String code() {
        return "openai-responses";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("responses", "openai-response");
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.STREAM, Capability.VISION);
    }

    @Override
    public Mono<ChatResponse> chat(UpstreamRequest request) {
        ObjectNode body = buildBody(request, false);
        return webClient.post()
                .uri(url(request))
                .headers(h -> h.add("Authorization", "Bearer " + (request.apiKey() == null ? "" : request.apiKey())))
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

    @Override
    public Flux<ChatChunk> chatStream(UpstreamRequest request) {
        ObjectNode body = buildBody(request, true);
        return Flux.defer(() -> webClient.post()
                .uri(url(request))
                .headers(h -> h.add("Authorization", "Bearer " + (request.apiKey() == null ? "" : request.apiKey())))
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
                .concatMap(line -> parseEvent(line, request))
                .timeout(Duration.ofMillis(timeoutMs(request)))
                .onErrorMap(this::mapError));
    }

    @Override
    public Mono<EmbeddingResponse> embedding(UpstreamRequest request) {
        return Mono.error(new UpstreamException(UpstreamException.Kind.NON_RETRYABLE,
                "Responses 适配器不提供向量化接口", null, 400, null));
    }

    // ==================================================================

    private ObjectNode buildBody(UpstreamRequest request, boolean stream) {
        ChatRequest req = request.chatRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());
        body.put("stream", stream);

        List<String> systems = new ArrayList<>();
        ArrayNode input = body.putArray("input");
        for (Message m : req.getMessages()) {
            String role = m.getRole();
            if ("system".equals(role)) {
                systems.add(m.contentAsText());
                continue;
            }
            ObjectNode item = input.addObject();
            String responseRole = "assistant".equals(role) ? "assistant" : "user";
            item.put("role", responseRole);
            ArrayNode content = item.putArray("content");
            String partType = "assistant".equals(role) ? "output_text" : "input_text";
            JsonNode raw = m.getContent();
            if (raw != null && raw.isArray()) {
                for (JsonNode part : raw) {
                    if (part.hasNonNull("text")) {
                        content.addObject().put("type", partType).put("text", part.get("text").asText());
                    } else if ("image_url".equals(part.path("type").asText())) {
                        String url = imageUrlOf(part);
                        if (url != null && !url.isBlank()) {
                            content.addObject().put("type", "input_image").put("image_url", url);
                        }
                    }
                }
            } else {
                content.addObject().put("type", partType).put("text", m.contentAsText());
            }
        }
        if (!systems.isEmpty()) {
            body.put("instructions", String.join("\n\n", systems));
        }
        if (req.getMaxTokens() != null) {
            body.put("max_output_tokens", req.getMaxTokens());
        }
        if (req.getTemperature() != null) {
            body.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            body.put("top_p", req.getTopP());
        }
        return body;
    }

    private static String imageUrlOf(JsonNode part) {
        JsonNode imageUrl = part.path("image_url");
        if (imageUrl.isTextual()) {
            return imageUrl.asText();
        }
        String url = imageUrl.path("url").asText(null);
        return url == null ? imageUrl.asText("") : url;
    }

    private ChatResponse toChatResponse(String json, UpstreamRequest request) {
        JsonNode root = JsonSupport.readTree(json);
        ChatResponse response = new ChatResponse();
        response.setId(root.path("id").asText("resp-" + shortId()));
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(root.path("model").asText(request.physicalModel()));

        Message message = new Message();
        message.setRole("assistant");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        text.append(part.path("text").asText(""));
                    }
                }
            }
        }
        message.setContent(JsonSupport.mapper().getNodeFactory().textNode(text.toString()));
        ChatResponse.ChatChoice choice = new ChatResponse.ChatChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(mapFinish(root.path("status").asText("completed")));
        response.setChoices(List.of(choice));
        response.setUsage(usageOf(root.path("usage")));
        return response;
    }

    /** Responses usage -> 规范口径；cached_tokens 是 input 的子集。 */
    static Usage usageOf(JsonNode usage) {
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int cached = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
        Usage u = new Usage(input, output, input + output, false);
        u.setCachedTokens(cached);
        return u;
    }

    static String mapFinish(String status) {
        return switch (status) {
            case "incomplete" -> "length";
            case "failed", "cancelled" -> "stop";
            default -> "stop";
        };
    }

    private Flux<ChatChunk> parseEvent(String line, UpstreamRequest request) {
        JsonNode node;
        try {
            node = JsonSupport.mapper().readTree(line);
        } catch (Exception e) {
            return Flux.empty();
        }
        String type = node.path("type").asText("");
        if ("response.output_text.delta".equals(type)) {
            ChatChunk chunk = newChunk(request);
            ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
            choice.setIndex(0);
            ChatChunk.Delta delta = new ChatChunk.Delta();
            delta.setContent(node.path("delta").asText(""));
            choice.setDelta(delta);
            chunk.getChoices().add(choice);
            return Flux.just(chunk);
        }
        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            JsonNode response = node.path("response");
            ChatChunk chunk = newChunk(request);
            ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
            choice.setIndex(0);
            choice.setDelta(new ChatChunk.Delta());
            choice.setFinishReason(mapFinish(response.path("status").asText("completed")));
            chunk.getChoices().add(choice);
            chunk.setUsage(usageOf(response.path("usage")));
            return Flux.just(chunk);
        }
        if ("response.failed".equals(type) || "error".equals(type)) {
            String msg = node.path("response").path("error").path("message")
                    .asText(node.path("message").asText("Responses 流式失败"));
            return Flux.error(new UpstreamException(UpstreamException.Kind.RETRYABLE, msg, null));
        }
        return Flux.empty();
    }

    private ChatChunk newChunk(UpstreamRequest request) {
        ChatChunk chunk = new ChatChunk();
        chunk.setId("chatcmpl-" + shortId());
        chunk.setCreated(Instant.now().getEpochSecond());
        chunk.setModel(request.physicalModel());
        chunk.setChoices(new ArrayList<>());
        return chunk;
    }

    // ==================================================================

    private String url(UpstreamRequest request) {
        String base = request.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=openai-responses");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/responses";
        }
        return trimmed + "/v1/responses";
    }

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

    private int timeoutMs(UpstreamRequest request) {
        return request.timeoutMs() == null ? 30000 : request.timeoutMs();
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

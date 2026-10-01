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
 * Google Gemini（Generative Language API）适配器。
 *
 * 把 OpenAI 兼容 IR 翻译为 Gemini 的 contents/parts 结构：
 * system -> systemInstruction，assistant -> role=model，图片 -> inlineData，
 * 工具调用 -> functionCall / functionResponse（best-effort）。
 * usage 的 cachedContentTokenCount 计入缓存读。
 *
 * 流式走 {@code :streamGenerateContent?alt=sse}，把每个分片的 text 映射为 OpenAI delta，
 * 末尾补 usage。
 *
 * 已知边界：Gemini 的 safety settings、grounding、多候选暂不透传。
 */
@Component
public class GeminiProvider implements ModelProvider {

    private final WebClient webClient;

    public GeminiProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String code() {
        return "gemini";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("google", "google-gemini", "vertex");
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.TOOL_CALL, Capability.STREAM, Capability.VISION);
    }

    @Override
    public Mono<ChatResponse> chat(UpstreamRequest request) {
        ObjectNode body = buildBody(request);
        return webClient.post()
                .uri(url(request, false))
                .headers(h -> h.add("x-goog-api-key", request.apiKey() == null ? "" : request.apiKey()))
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
        ObjectNode body = buildBody(request);
        return Flux.defer(() -> webClient.post()
                .uri(url(request, true))
                .headers(h -> h.add("x-goog-api-key", request.apiKey() == null ? "" : request.apiKey()))
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
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        ObjectNode content = body.putObject("content");
        ArrayNode parts = content.putArray("parts");
        for (String text : request.embeddingRequest().inputTexts()) {
            parts.addObject().put("text", text);
        }
        return webClient.post()
                .uri(embedUrl(request))
                .headers(h -> h.add("x-goog-api-key", request.apiKey() == null ? "" : request.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(b -> Mono.error(UpstreamException.from(resp.statusCode(), b)));
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(json -> toEmbeddingResponse(json, request));
                })
                .timeout(Duration.ofMillis(timeoutMs(request)));
    }

    // ==================================================================

    private ObjectNode buildBody(UpstreamRequest request) {
        ChatRequest req = request.chatRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        ArrayNode contents = body.putArray("contents");
        List<String> systems = new ArrayList<>();

        for (Message m : req.getMessages()) {
            String role = m.getRole();
            if ("system".equals(role)) {
                systems.add(m.contentAsText());
                continue;
            }
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(role) ? "model" : "user");
            ArrayNode parts = content.putArray("parts");
            appendParts(parts, m);
        }
        if (!systems.isEmpty()) {
            body.putObject("systemInstruction").putArray("parts")
                    .addObject().put("text", String.join("\n\n", systems));
        }

        ObjectNode gen = JsonSupport.mapper().createObjectNode();
        if (req.getTemperature() != null) {
            gen.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            gen.put("topP", req.getTopP());
        }
        if (req.getMaxTokens() != null) {
            gen.put("maxOutputTokens", req.getMaxTokens());
        }
        if (req.getStop() != null) {
            ArrayNode stops = gen.putArray("stopSequences");
            if (req.getStop() instanceof List<?> list) {
                list.forEach(s -> stops.add(String.valueOf(s)));
            } else {
                stops.add(String.valueOf(req.getStop()));
            }
        }
        if (!gen.isEmpty()) {
            body.set("generationConfig", gen);
        }
        return body;
    }

    private void appendParts(ArrayNode parts, Message m) {
        JsonNode content = m.getContent();
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if (part.hasNonNull("text")) {
                    parts.addObject().put("text", part.get("text").asText());
                } else if ("image_url".equals(part.path("type").asText())) {
                    addInlineImage(parts, imageUrlOf(part));
                }
            }
        } else {
            parts.addObject().put("text", m.contentAsText());
        }
        if ("tool".equals(m.getRole()) && m.getName() != null) {
            ObjectNode fn = parts.addObject().putObject("functionResponse");
            fn.put("name", m.getName());
            fn.putObject("response").put("result", m.contentAsText());
        }
        if (m.getToolCalls() != null && m.getToolCalls().isArray()) {
            for (JsonNode tc : m.getToolCalls()) {
                ObjectNode call = parts.addObject().putObject("functionCall");
                call.put("name", tc.path("function").path("name").asText(""));
                String args = tc.path("function").path("arguments").asText("{}");
                try {
                    call.set("args", JsonSupport.mapper().readTree(args));
                } catch (Exception e) {
                    call.set("args", JsonSupport.mapper().createObjectNode());
                }
            }
        }
    }

    private static String imageUrlOf(JsonNode part) {
        JsonNode imageUrl = part.path("image_url");
        if (imageUrl.isTextual()) {
            return imageUrl.asText();
        }
        String url = imageUrl.path("url").asText(null);
        return url == null ? imageUrl.asText("") : url;
    }

    private static void addInlineImage(ArrayNode parts, String url) {
        if (url == null || !url.startsWith("data:image/")) {
            return;
        }
        int comma = url.indexOf(',');
        if (comma < 0) {
            return;
        }
        String meta = url.substring(5, comma);
        String mediaType = meta.split(";")[0];
        String data = url.substring(comma + 1);
        ObjectNode part = parts.addObject();
        ObjectNode inline = part.putObject("inlineData");
        inline.put("mimeType", mediaType);
        inline.put("data", data);
    }

    private ChatResponse toChatResponse(String json, UpstreamRequest request) {
        JsonNode root = JsonSupport.readTree(json);
        ChatResponse response = new ChatResponse();
        response.setId("chatcmpl-" + shortId());
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.physicalModel());

        JsonNode candidate = root.path("candidates").path(0);
        Message message = new Message();
        message.setRole("assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = JsonSupport.mapper().createArrayNode();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            } else if (part.has("functionCall")) {
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", "call_" + shortId());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", part.path("functionCall").path("name").asText(""));
                fn.put("arguments", part.path("functionCall").path("args").toString());
            }
        }
        message.setContent(JsonSupport.mapper().getNodeFactory().textNode(text.toString()));
        if (!toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }
        ChatResponse.ChatChoice choice = new ChatResponse.ChatChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(mapFinish(candidate.path("finishReason").asText("STOP")));
        response.setChoices(List.of(choice));
        response.setUsage(usageOf(root.path("usageMetadata")));
        return response;
    }

    private EmbeddingResponse toEmbeddingResponse(String json, UpstreamRequest request) {
        JsonNode root = JsonSupport.readTree(json);
        List<EmbeddingResponse.EmbeddingData> data = new ArrayList<>();
        JsonNode values = root.path("embedding").path("values");
        EmbeddingResponse.EmbeddingData d = new EmbeddingResponse.EmbeddingData();
        d.setIndex(0);
        List<Float> floats = new ArrayList<>();
        values.forEach(v -> floats.add((float) v.asDouble()));
        d.setEmbedding(floats);
        data.add(d);
        EmbeddingResponse response = new EmbeddingResponse();
        response.setData(data);
        response.setModel(request.physicalModel());
        return response;
    }

    private Flux<ChatChunk> parseEvent(String line, UpstreamRequest request) {
        JsonNode root;
        try {
            root = JsonSupport.mapper().readTree(line);
        } catch (Exception e) {
            return Flux.empty();
        }
        JsonNode candidate = root.path("candidates").path(0);
        ChatChunk chunk = new ChatChunk();
        chunk.setId("chatcmpl-" + shortId());
        chunk.setCreated(Instant.now().getEpochSecond());
        chunk.setModel(request.physicalModel());
        chunk.setChoices(new ArrayList<>());

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }
        if (text.length() > 0) {
            ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
            choice.setIndex(0);
            ChatChunk.Delta delta = new ChatChunk.Delta();
            delta.setContent(text.toString());
            choice.setDelta(delta);
            chunk.getChoices().add(choice);
        }
        if (root.has("usageMetadata")) {
            chunk.setUsage(usageOf(root.path("usageMetadata")));
        }
        if (chunk.getChoices().isEmpty() && chunk.getUsage() == null) {
            return Flux.empty();
        }
        return Flux.just(chunk);
    }

    static Usage usageOf(JsonNode usageMetadata) {
        int prompt = usageMetadata.path("promptTokenCount").asInt(0);
        int completion = usageMetadata.path("candidatesTokenCount").asInt(0);
        int cached = usageMetadata.path("cachedContentTokenCount").asInt(0);
        Usage u = new Usage(prompt, completion, prompt + completion, false);
        // Gemini 的 promptTokenCount 已包含 cachedContentTokenCount，因此只标记缓存读
        u.setCachedTokens(cached);
        return u;
    }

    static String mapFinish(String reason) {
        return switch (reason) {
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
            default -> "stop";
        };
    }

    // ==================================================================

    private String url(UpstreamRequest request, boolean stream) {
        String base = trim(request.baseUrl());
        String root = base.endsWith("/v1beta") ? base : base + "/v1beta";
        String method = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        return root + "/models/" + request.physicalModel() + method;
    }

    private String embedUrl(UpstreamRequest request) {
        String base = trim(request.baseUrl());
        String root = base.endsWith("/v1beta") ? base : base + "/v1beta";
        return root + "/models/" + request.physicalModel() + ":embedContent";
    }

    private static String trim(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=gemini");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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

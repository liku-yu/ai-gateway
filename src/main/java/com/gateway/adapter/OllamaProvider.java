package com.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.Capability;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingResponse;
import com.gateway.protocol.Message;
import com.gateway.protocol.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
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
 * Ollama 自建模型：原生 /api/chat 协议，不兼容 OpenAI，故独立实现。
 *
 * 差异点（与 OpenAI 系相比）：
 * 1. 采样参数嵌套在 options（num_predict / temperature / top_p）；
 * 2. 流式是 NDJSON（每行一个 JSON），不是 SSE，且靠 done:true 结束而非 [DONE]；
 * 3. token 统计来自 prompt_eval_count / eval_count；
 * 4. 无需鉴权（本地部署），Authorization 头整体省略。
 */
@Slf4j
@Component
public class OllamaProvider implements ModelProvider {

    private final WebClient webClient;

    public OllamaProvider(@Qualifier("upstreamWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String code() {
        return "ollama";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.STREAM);
    }

    @Override
    public Mono<ChatResponse> chat(UpstreamRequest request) {
        ObjectNode body = buildChatBody(request, false);
        return webClient.post()
                .uri(url(request, "/api/chat"))
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
                .timeout(Duration.ofMillis(timeout(request)));
    }

    @Override
    public Flux<ChatChunk> chatStream(UpstreamRequest request) {
        ObjectNode body = buildChatBody(request, true);
        return webClient.post()
                .uri(url(request, "/api/chat"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMapMany(b -> Flux.error(UpstreamException.from(resp.statusCode(), b)));
                    }
                    return resp.bodyToFlux(String.class);
                })
                // 一次响应可能被拆成多行，展开后逐行解析 NDJSON
                .flatMapIterable(this::splitLines)
                .filter(line -> !line.isBlank())
                .concatMap(line -> parseNdjson(line, request))
                .timeout(Duration.ofMillis(timeout(request)))
                .onErrorMap(this::mapError);
    }

    @Override
    public Mono<EmbeddingResponse> embedding(UpstreamRequest request) {
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());
        body.set("input", JsonSupport.mapper().valueToTree(request.embeddingRequest().inputTexts()));

        return webClient.post()
                .uri(url(request, "/api/embed"))
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
                .timeout(Duration.ofMillis(timeout(request)));
    }

    // ------------------------------------------------------------------
    // 协议转换
    // ------------------------------------------------------------------

    private ObjectNode buildChatBody(UpstreamRequest request, boolean stream) {
        var req = request.chatRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());
        body.put("stream", stream);

        ArrayNode messages = body.putArray("messages");
        for (Message m : req.getMessages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.getRole());
            node.put("content", m.contentAsText());
        }

        // OpenAI 的平铺参数在 Ollama 里要收进 options
        ObjectNode options = body.putObject("options");
        if (req.getTemperature() != null) {
            options.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            options.put("top_p", req.getTopP());
        }
        if (req.getMaxTokens() != null) {
            options.put("num_predict", req.getMaxTokens());
        }
        if (req.passthrough().get("top_k") != null) {
            options.set("top_k", JsonSupport.mapper().valueToTree(req.passthrough().get("top_k")));
        }
        return body;
    }

    private ChatResponse toChatResponse(String json, UpstreamRequest request) {
        JsonNode node = JsonSupport.readTree(json).path("message");
        ChatResponse.ChatChoice choice = new ChatResponse.ChatChoice();
        choice.setIndex(0);
        Message message = new Message();
        message.setRole(node.path("role").asText("assistant"));
        message.setContentText(node.path("content").asText(""));
        choice.setMessage(message);
        choice.setFinishReason("stop");

        ChatResponse response = new ChatResponse();
        response.setId("chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.physicalModel());
        response.setChoices(List.of(choice));
        response.setUsage(extractUsage(json));
        return response;
    }

    private EmbeddingResponse toEmbeddingResponse(String json, UpstreamRequest request) {
        List<EmbeddingResponse.EmbeddingData> data = new ArrayList<>();
        JsonNode node = JsonSupport.readTree(json);
        int i = 0;
        for (JsonNode vec : node.path("embeddings")) {
            EmbeddingResponse.EmbeddingData d = new EmbeddingResponse.EmbeddingData();
            d.setIndex(i++);
            List<Float> values = new ArrayList<>();
            vec.forEach(v -> values.add((float) v.asDouble()));
            d.setEmbedding(values);
            data.add(d);
        }
        EmbeddingResponse response = new EmbeddingResponse();
        response.setData(data);
        response.setModel(request.physicalModel());
        response.setUsage(Usage.of(node.path("prompt_eval_count").asInt(0), 0));
        return response;
    }

    /** NDJSON 单行 -> ChatChunk；done:true 时转为携带 usage 的收尾分片。 */
    private Flux<ChatChunk> parseNdjson(String line, UpstreamRequest request) {
        try {
            JsonNode node = JsonSupport.readTree(line);
            boolean done = node.path("done").asBoolean(false);

            ChatChunk chunk = new ChatChunk();
            chunk.setId("chatcmpl-" + Integer.toHexString(line.hashCode()));
            chunk.setCreated(Instant.now().getEpochSecond());
            chunk.setModel(request.physicalModel());

            ChatChunk.ChunkChoice choice = new ChatChunk.ChunkChoice();
            choice.setIndex(0);
            ChatChunk.Delta delta = new ChatChunk.Delta();
            String content = node.path("message").path("content").asText("");
            delta.setContent(content);
            choice.setDelta(delta);
            choice.setFinishReason(done ? "stop" : null);
            chunk.setChoices(List.of(choice));

            if (done) {
                chunk.setUsage(extractUsage(line));
            }
            return Flux.just(chunk);
        } catch (Exception e) {
            log.debug("跳过无法解析的 Ollama 分片: {}", e.getMessage());
            return Flux.empty();
        }
    }

    private Usage extractUsage(String json) {
        try {
            JsonNode node = JsonSupport.readTree(json);
            int prompt = node.path("prompt_eval_count").asInt(0);
            int completion = node.path("eval_count").asInt(0);
            if (prompt == 0 && completion == 0) {
                return null;
            }
            return Usage.of(prompt, completion);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> splitLines(String block) {
        List<String> lines = new ArrayList<>();
        for (String l : block.split("\n")) {
            if (!l.isBlank()) {
                lines.add(l.trim());
            }
        }
        return lines;
    }

    private Throwable mapError(Throwable t) {
        if (t instanceof UpstreamException) {
            return t;
        }
        String msg = t.getMessage() == null ? "unknown" : t.getMessage();
        if (msg.toLowerCase().contains("timeout")) {
            return new UpstreamException(UpstreamException.Kind.TIMEOUT, "Ollama 读取超时", t);
        }
        return new UpstreamException(UpstreamException.Kind.RETRYABLE, "Ollama 传输中断: " + msg, t);
    }

    private String url(UpstreamRequest request, String path) {
        String base = request.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=ollama");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path;
    }

    private int timeout(UpstreamRequest request) {
        return request.timeoutMs() == null ? 60000 : request.timeoutMs();
    }
}

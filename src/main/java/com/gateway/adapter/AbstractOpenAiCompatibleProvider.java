package com.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.infra.JsonSupport;
import com.gateway.protocol.ChatChunk;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.EmbeddingResponse;
import com.gateway.protocol.Message;
import com.gateway.protocol.StreamOptions;
import com.gateway.protocol.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容系适配器基类。
 *
 * OpenAI / 通义(百炼兼容模式) / DeepSeek 共用本基类，只在下列钩子上做差异：
 * {@link #chatPath()} {@link #authorizationHeader(String)} {@link #mapParams} {@link #extractUsage}。
 * 非兼容厂商（如 Ollama 原生协议）单独实现 {@link ModelProvider}。
 */
@Slf4j
public abstract class AbstractOpenAiCompatibleProvider implements ModelProvider {

    protected final WebClient webClient;

    protected AbstractOpenAiCompatibleProvider(WebClient upstreamWebClient) {
        this.webClient = upstreamWebClient;
    }

    protected String chatPath() {
        return "/chat/completions";
    }

    protected String embeddingPath() {
        return "/embeddings";
    }

    /** 鉴权头。默认 Bearer，个别厂商可覆写。 */
    protected Map<String, String> authHeaders(String apiKey) {
        return Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    protected String url(UpstreamRequest request, String path) {
        String base = request.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("渠道未配置 baseUrl: provider=" + code());
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path;
    }

    // ------------------------------------------------------------------
    // 非流式对话
    // ------------------------------------------------------------------
    @Override
    public Mono<ChatResponse> chat(UpstreamRequest request) {
        ObjectNode body = mapChatParams(request);
        return webClient.post()
                .uri(url(request, chatPath()))
                .headers(h -> authHeaders(request.apiKey()).forEach(h::add))
                .header("X-Request-Id", orEmpty(request.requestId()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> toMonoResponse(resp, ChatResponse.class, request))
                .timeout(Duration.ofMillis(timeoutMs(request)))
                .doOnNext(r -> normalizeUsage(r.getUsage(), request));
    }

    // ------------------------------------------------------------------
    // 流式对话：逐块透传，不做聚合缓冲
    // ------------------------------------------------------------------
    @Override
    public Flux<ChatChunk> chatStream(UpstreamRequest request) {
        ObjectNode body = mapChatParams(request);
        body.put("stream", true);
        // 强制索取流式 usage，最终结算以上游返回为准
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);

        return webClient.post()
                .uri(url(request, chatPath()))
                .headers(h -> authHeaders(request.apiKey()).forEach(h::add))
                .header("X-Request-Id", orEmpty(request.requestId()))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux(resp -> toFluxResponse(resp, request))
                // 流式 usage 出现在最后一片，必须在离流前归一化缓存字段，
                // 否则结算会漏掉 Prompt Cache 命中部分（按全价计费）。
                .doOnNext(chunk -> {
                    if (chunk.getUsage() != null) {
                        chunk.getUsage().normalize();
                    }
                })
                .timeout(Duration.ofMillis(timeoutMs(request)));
    }

    // ------------------------------------------------------------------
    // 向量化
    // ------------------------------------------------------------------
    @Override
    public Mono<EmbeddingResponse> embedding(UpstreamRequest request) {
        ObjectNode body = mapEmbeddingParams(request);
        return webClient.post()
                .uri(url(request, embeddingPath()))
                .headers(h -> authHeaders(request.apiKey()).forEach(h::add))
                .header("X-Request-Id", orEmpty(request.requestId()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> toMonoResponse(resp, EmbeddingResponse.class, request))
                .timeout(Duration.ofMillis(timeoutMs(request)));
    }

    // ==================================================================
    // 差异钩子
    // ==================================================================

    /** 参数映射：把统一内部请求翻译成该厂商的物理请求体。 */
    protected ObjectNode mapChatParams(UpstreamRequest request) {
        var req = request.chatRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());

        var messages = JsonSupport.mapper().createArrayNode();
        for (Message m : req.getMessages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.getRole());
            if (m.getName() != null) {
                node.put("name", m.getName());
            }
            if (m.getToolCallId() != null) {
                node.put("tool_call_id", m.getToolCallId());
            }
            node.set("content", m.getContent());
            if (m.getToolCalls() != null) {
                node.set("tool_calls", m.getToolCalls());
            }
        }
        body.set("messages", messages);
        body.put("stream", false);

        putIfNotNull(body, "temperature", req.getTemperature());
        putIfNotNull(body, "top_p", req.getTopP());
        putIfNotNull(body, "max_tokens", req.getMaxTokens());
        putIfNotNull(body, "stop", req.getStop());
        putIfNotNull(body, "user", req.passthrough().get("user"));

        // 业务透传的其它 OpenAI 参数（tools / response_format / seed / top_k ...）原样带上
        for (Map.Entry<String, Object> e : req.passthrough().entrySet()) {
            String k = e.getKey();
            if (k.equals("user") || k.equals("stream") || k.equals("stream_options")) {
                continue;
            }
            body.set(k, JsonSupport.mapper().valueToTree(e.getValue()));
        }
        return body;
    }

    protected ObjectNode mapEmbeddingParams(UpstreamRequest request) {
        var req = request.embeddingRequest();
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.physicalModel());
        body.set("input", JsonSupport.mapper().valueToTree(req.getInput()));
        putIfNotNull(body, "encoding_format", req.getEncodingFormat());
        putIfNotNull(body, "dimensions", req.getDimensions());
        return body;
    }

    /**
     * usage 归一化。
     * 部分上游在流式场景不返回 usage，这里标记 estimated 并交由计费层用本地估算兜底。
     * 同时把 OpenAi 的 {@code prompt_tokens_details.cached_tokens} 归一为规范字段，
     * 供缓存计费使用。
     */
    protected void normalizeUsage(Usage usage, UpstreamRequest request) {
        if (usage == null) {
            return;
        }
        usage.normalize();
        if (usage.getTotalTokens() == null) {
            usage.setTotalTokens(usage.promptOrZero() + usage.completionOrZero());
        }
        if (usage.getEstimated() == null) {
            usage.setEstimated(false);
        }
    }

    // ==================================================================
    // 响应转换与错误分类
    // ==================================================================

    private <T> Mono<T> toMonoResponse(ClientResponse resp, Class<T> type, UpstreamRequest request) {
        if (resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(json -> JsonSupport.read(json, type))
                    .onErrorMap(e -> new UpstreamException(UpstreamException.Kind.NON_RETRYABLE,
                            "上游响应解析失败: " + e.getMessage(), e));
        }
        return resp.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(body -> Mono.error(UpstreamException.from(resp.statusCode(), body)));
    }

    /** 解析 SSE：把 `data: {...}` 行转成统一的 ChatChunk，遇到 [DONE] 结束。 */
    private Flux<ChatChunk> toFluxResponse(ClientResponse resp, UpstreamRequest request) {
        if (!resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(UpstreamException.from(resp.statusCode(), body)));
        }
        return resp.bodyToFlux(String.class)
                .takeUntil(line -> line.contains("[DONE]"))
                .filter(line -> !line.contains("[DONE]"))
                .concatMap(this::parseSseLine)
                .filter(chunk -> !chunk.getChoices().isEmpty() || chunk.getUsage() != null)
                .onErrorMap(this::mapStreamError);
    }

    private Flux<ChatChunk> parseSseLine(String rawLine) {
        List<ChatChunk> out = new ArrayList<>(1);
        for (String line : rawLine.split("\n")) {
            String payload = stripDataPrefix(line);
            if (payload.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = JsonSupport.mapper().readTree(payload);
                ChatChunk chunk = JsonSupport.mapper().treeToValue(node, ChatChunk.class);
                if (chunk.getChoices() == null) {
                    chunk.setChoices(List.of());
                }
                out.add(chunk);
            } catch (Exception e) {
                // 单个分片解析失败不应中断整条流，跳过并留痕
                log.debug("跳过无法解析的流式分片: {}", e.getMessage());
            }
        }
        return out.isEmpty() ? Flux.empty() : Flux.fromIterable(out);
    }

    private String stripDataPrefix(String line) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith(":")) {
            return "";
        }
        if (s.startsWith("data:")) {
            s = s.substring(5).trim();
        }
        return s;
    }

    private Throwable mapStreamError(Throwable t) {
        if (t instanceof UpstreamException) {
            return t;
        }
        String msg = t.getMessage() == null ? "unknown" : t.getMessage();
        if (msg.contains("timeout") || msg.contains("Timeout")) {
            return new UpstreamException(UpstreamException.Kind.TIMEOUT, "流式读取超时", t);
        }
        return new UpstreamException(UpstreamException.Kind.RETRYABLE, "流式传输中断: " + msg, t);
    }

    protected int timeoutMs(UpstreamRequest request) {
        return request.timeoutMs() == null ? 30000 : request.timeoutMs();
    }

    protected static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    protected static void putIfNotNull(ObjectNode body, String field, Object value) {
        if (value != null) {
            body.set(field, JsonSupport.mapper().valueToTree(value));
        }
    }

    protected static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    protected static StreamOptions includeUsage() {
        return new StreamOptions(true);
    }

    protected static boolean isSuccess(HttpStatus status) {
        return status != null && status.is2xxSuccessful();
    }
}

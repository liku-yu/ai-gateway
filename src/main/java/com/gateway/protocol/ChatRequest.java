package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的对话请求。
 * 未识别的字段（top_p / tools / response_format / seed ...）由 {@link JsonAnySetter} 原样保留，
 * 转发上游时再拼回，避免网关成为「参数白名单」而被迫频繁发版。
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {

    private String model;

    private List<Message> messages = new ArrayList<>();

    private Boolean stream = Boolean.FALSE;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Object stop;

    @JsonProperty("stream_options")
    private StreamOptions streamOptions;

    @JsonProperty("extra_body")
    private GatewayExtras extraBody;

    /** 未识别字段的原样透传容器。 */
    @JsonIgnore
    private Map<String, Object> passthrough = new LinkedHashMap<>();

    @JsonAnySetter
    public void putPassthrough(String key, Object value) {
        this.passthrough.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> passthrough() {
        return passthrough;
    }

    public void setPassthrough(Map<String, Object> map) {
        this.passthrough = map == null ? new LinkedHashMap<>() : map;
    }

    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    @JsonIgnore
    public int effectiveMaxTokens() {
        return maxTokens == null ? 1024 : maxTokens;
    }

    @JsonIgnore
    public Integer timeoutMs() {
        return extraBody == null ? null : extraBody.getTimeoutMs();
    }

    @JsonIgnore
    public BigDecimal maxCost() {
        return extraBody == null ? null : extraBody.getMaxCost();
    }

    @JsonIgnore
    public RoutingHint routing() {
        return extraBody == null ? null : extraBody.getRouting();
    }

    @JsonIgnore
    public MaskingHint masking() {
        return extraBody == null ? null : extraBody.getMasking();
    }
}

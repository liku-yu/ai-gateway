package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容的向量化请求；input 支持单条字符串或字符串数组。 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingRequest {

    private String model;

    private Object input;

    private String encodingFormat;

    private Integer dimensions;

    @JsonProperty("extra_body")
    private GatewayExtras extraBody;

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

    @JsonIgnore
    public Integer timeoutMs() {
        return extraBody == null ? null : extraBody.getTimeoutMs();
    }

    @JsonIgnore
    public java.math.BigDecimal maxCost() {
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

    @JsonIgnore
    public List<String> inputTexts() {
        List<String> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        if (input instanceof String s) {
            out.add(s);
        } else if (input instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        } else {
            out.add(String.valueOf(input));
        }
        return out;
    }

    @JsonIgnore
    public void setInputTexts(List<String> texts) {
        this.input = texts.size() == 1 ? texts.get(0) : texts;
    }
}

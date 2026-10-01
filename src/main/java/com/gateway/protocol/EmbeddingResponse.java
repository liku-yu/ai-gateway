package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** OpenAI 兼容的向量化响应。 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    private String object = "list";

    private List<EmbeddingData> data;

    private String model;

    private Usage usage;

    @JsonProperty("gateway")
    private GatewayMeta gateway;

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingData {
        private String object = "embedding";
        private Integer index;
        private List<Float> embedding;
    }
}

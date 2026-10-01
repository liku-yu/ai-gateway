package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** OpenAI 兼容的流式分片（SSE data 载荷）。 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatChunk {

    private String id;

    private String object = "chat.completion.chunk";

    private Long created;

    private String model;

    private List<ChunkChoice> choices;

    private Usage usage;

    @JsonProperty("gateway")
    private GatewayMeta gateway;

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChunkChoice {
        private Integer index;

        private Delta delta;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {
        private String role;
        private String content;

        @JsonProperty("tool_calls")
        private Object toolCalls;
    }
}

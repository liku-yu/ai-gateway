package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** OpenAI 兼容的非流式对话响应。网关会在其基础上补齐 usage（上游缺失时用估算值）。 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private String id;

    private String object = "chat.completion";

    private Long created;

    private String model;

    private List<ChatChoice> choices;

    private Usage usage;

    /** 网关附加信息，放在响应顶层，不影响标准字段解析。 */
    @JsonProperty("gateway")
    private GatewayMeta gateway;

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatChoice {
        private Integer index;
        private Message message;

        @JsonProperty("finish_reason")
        private String finishReason;
    }
}

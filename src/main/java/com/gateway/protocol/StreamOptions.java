package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** OpenAI 流式 usage 开关；网关会强制置为 true 以便结算，对不支持的上游则回退本地估算。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamOptions {
    @JsonProperty("include_usage")
    private Boolean includeUsage;
}

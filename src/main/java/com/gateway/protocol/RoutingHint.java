package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 业务侧可选的路由提示，放在 extra_body.routing，不影响 OpenAI 兼容性。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingHint {
    /** weighted / priority / leastconn / hash / cheapest，缺省用配置默认值。 */
    private String strategy;

    @JsonProperty("allow_fallback")
    private Boolean allowFallback;

    @JsonProperty("fallback_models")
    private List<String> fallbackModels;

    /** 一致性 Hash 用的稳定键，缺省取 appId。 */
    @JsonProperty("session_key")
    private String sessionKey;
}

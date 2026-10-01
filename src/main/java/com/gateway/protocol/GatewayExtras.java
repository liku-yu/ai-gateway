package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 网关私有扩展字段，承载于请求体的 extra_body 中。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayExtras {

    private RoutingHint routing;

    private MaskingHint masking;

    @JsonProperty("timeout_ms")
    private Integer timeoutMs;

    @JsonProperty("max_cost")
    private java.math.BigDecimal maxCost;
}

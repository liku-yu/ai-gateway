package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 网关回执元信息：告诉业务实际走了哪个渠道、是否重试/降级/熔断、计费多少。 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayMeta {

    private String traceId;

    private String requestId;

    private String provider;

    private Long channelId;

    private String channelName;

    /** 上游物理模型名，便于业务排查映射问题。 */
    private String physicalModel;

    private Integer retryCount;

    private Boolean degraded;

    private Boolean masked;

    private Integer maskedCount;

    private Integer latencyMs;

    private Integer ttfbMs;

    private java.math.BigDecimal cost;

    private String currency;
}

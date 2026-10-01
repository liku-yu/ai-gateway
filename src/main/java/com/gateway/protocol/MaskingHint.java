package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 业务侧可覆盖的脱敏策略，放在 extra_body.masking。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaskingHint {
    private Boolean enabled;
    private List<String> types;
    /** 是否把响应中的占位符还原为原文，缺省读全局配置。 */
    private Boolean restore;
}

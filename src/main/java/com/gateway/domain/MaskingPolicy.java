package com.gateway.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 脱敏策略（存储于 gw_app.masking_policy，可被请求级 extra_body.masking 覆盖）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaskingPolicy {

    private Boolean enabled;

    /** 生效的脱敏类型，如 PHONE / ID_CARD / EMAIL / BANK_CARD / API_KEY。 */
    private List<String> types;

    /** 是否把模型回复里的占位符还原成原文。 */
    private Boolean restore;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public boolean has(String type) {
        return types == null || types.isEmpty() || types.contains(type);
    }
}

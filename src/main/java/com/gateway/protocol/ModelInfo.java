package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** /v1/models 返回项：只暴露逻辑模型，不泄露上游渠道信息。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelInfo {
    private String id;
    private String object = "model";
    private Long created;
    @JsonProperty("owned_by")
    private String ownedBy = "ai-gateway";
    private List<String> capabilities;
    private String type;
}

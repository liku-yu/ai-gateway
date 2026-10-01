package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Map;

/** 渠道：路由 / 熔断 / 限流 / 计费的最小执行单元（对应 gw_channel）。 */
@Data
@TableName(value = "gw_channel", autoResultMap = true)
public class Channel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long providerId;

    private String name;

    /** AES-GCM 密文，解密只发生在调用上游前的一刻，永不回显、不落日志。 */
    private String apiKeyEnc;

    /** 覆盖 provider 的 baseUrl，为空则用 provider 的。 */
    private String baseUrl;

    private Integer weight;

    private Integer priority;

    /** 逻辑模型名 -> 上游物理模型名。 */
    @TableField(value = "model_mapping", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> modelMapping;

    private Integer rpmLimit;

    private Integer tpmLimit;

    private Integer concurrencyLimit;

    private Integer timeoutMs;

    private String status;

    /** 关联字段，非表列。 */
    @TableField(exist = false)
    private Provider provider;

    public boolean isActive() {
        return ChannelStatus.ACTIVE.name().equals(status);
    }

    public boolean supports(String logicalModel) {
        return modelMapping != null && modelMapping.containsKey(logicalModel);
    }

    public String physicalModel(String logicalModel) {
        if (modelMapping == null) {
            return null;
        }
        return modelMapping.get(logicalModel);
    }

    public int weightOr(int fallback) {
        return weight == null || weight <= 0 ? fallback : weight;
    }

    public int priorityOr(int fallback) {
        return priority == null ? fallback : priority;
    }

    public int timeoutMsOr(int fallback) {
        return timeoutMs == null || timeoutMs <= 0 ? fallback : timeoutMs;
    }

    public String resolveBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return provider == null ? null : provider.getBaseUrl();
    }
}

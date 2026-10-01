package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 租户应用（对应 gw_app）：预算、可用模型、脱敏策略的归属。 */
@Data
@TableName(value = "gw_app", autoResultMap = true)
public class App {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String name;

    private BigDecimal dailyBudget;

    private BigDecimal monthlyBudget;

    @TableField(value = "allowed_models", typeHandler = JacksonTypeHandler.class)
    private List<String> allowedModels;

    @TableField(value = "masking_policy", typeHandler = JacksonTypeHandler.class)
    private MaskingPolicy maskingPolicy;

    private Integer status;

    /** 是否可用（避开 isEnabled 命名，防止 MyBatis 误映射 status 列）。 */
    public boolean isAvailable() {
        return status == null || status == 1;
    }

    public boolean allows(String logicalModel) {
        return allowedModels == null || allowedModels.isEmpty() || allowedModels.contains(logicalModel);
    }
}

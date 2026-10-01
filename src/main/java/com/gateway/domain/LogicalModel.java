package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;

/** 逻辑模型（业务可见，对应 gw_model）。 */
@Data
@TableName(value = "gw_model", autoResultMap = true)
public class LogicalModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String logicalName;

    private String type;

    private String description;

    @TableField(value = "fallback_chain", typeHandler = JacksonTypeHandler.class)
    private List<String> fallbackChain;

    private Integer enabled;

    /** 是否启用（方法名刻意避开 isEnabled，防止 MyBatis 误映射 enabled 列）。 */
    public boolean isAvailable() {
        return enabled == null || enabled == 1;
    }
}

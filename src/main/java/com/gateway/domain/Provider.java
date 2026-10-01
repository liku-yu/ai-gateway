package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 供应商（对应 gw_provider）。 */
@Data
@TableName("gw_provider")
public class Provider {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String baseUrl;
    private String adapterClass;
    private Integer enabled;

    /** 是否启用（避开 isEnabled 命名，防止 MyBatis 误映射 enabled 列）。 */
    public boolean isAvailable() {
        return enabled == null || enabled == 1;
    }
}

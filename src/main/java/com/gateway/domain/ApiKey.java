package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 虚拟 Key（对应 gw_api_key）：只存 hash，明文仅在签发时返回一次。 */
@Data
@TableName("gw_api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    private String keyHash;

    private String keyPrefix;

    private Integer rpmLimit;

    private Integer tpmLimit;

    private Integer concurrencyLimit;

    private Integer status;

    private LocalDateTime expireAt;

    /** 是否可用（避开 isEnabled 命名，防止 MyBatis 误映射 status 列）。 */
    public boolean isAvailable() {
        return status == null || status == 1;
    }

    public boolean isExpired() {
        return expireAt != null && expireAt.isBefore(LocalDateTime.now());
    }
}

package com.gateway.admin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 运行时设置覆盖（对应 gw_setting）。 */
@Data
@TableName("gw_setting")
public class Setting {

    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;

    private String settingValue;

    private LocalDateTime updateTime;
}

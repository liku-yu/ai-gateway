package com.gateway.domain;

/** 渠道状态。COOLING 为运行时写入（DB 与本地缓存同步），DISABLED 为人工下线。 */
public enum ChannelStatus {
    ACTIVE,
    DISABLED,
    COOLING
}

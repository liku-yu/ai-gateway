package com.gateway.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 访问日志（gw_request_log）：只记录元信息与计费结果，不含请求正文。 */
@Data
@TableName("gw_request_log")
public class RequestLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String requestId;
    private Long tenantId;
    private Long appId;
    private Long apiKeyId;
    private String logicalModel;
    private String provider;
    private Long channelId;
    private Integer stream;
    private Integer success;
    private Integer statusCode;
    private String errorCode;
    private Integer ttfbMs;
    private Integer costMs;
    private Integer promptTokens;
    private Integer completionTokens;
    /** 缓存读 token（Prompt Cache 命中部分）。 */
    private Integer cachedTokens;
    /** 缓存写 token（Prompt Cache 写入部分）。 */
    private Integer cacheCreationTokens;
    private Integer usageEstimated;
    private BigDecimal cost;
    private String currency;
    private Integer retryCnt;
    private Integer degraded;
    private Integer maskedCnt;
    private String clientIp;
    private LocalDateTime createTime;
}

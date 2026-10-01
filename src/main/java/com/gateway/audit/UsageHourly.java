package com.gateway.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 小时级用量聚合（gw_usage_hourly）。 */
@Data
@TableName("gw_usage_hourly")
public class UsageHourly {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime statHour;
    private Long tenantId;
    private Long appId;
    private String provider;
    private String model;
    private Integer reqCnt;
    private Integer errCnt;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedTokens;
    private Long cacheCreationTokens;
    private BigDecimal cost;

    public static UsageHourly of(LocalDateTime statHour, Long tenantId, Long appId,
                                 String provider, String model, int reqCnt, int errCnt,
                                 long promptTokens, long completionTokens, BigDecimal cost) {
        return of(statHour, tenantId, appId, provider, model, reqCnt, errCnt,
                promptTokens, completionTokens, 0L, 0L, cost);
    }

    public static UsageHourly of(LocalDateTime statHour, Long tenantId, Long appId,
                                 String provider, String model, int reqCnt, int errCnt,
                                 long promptTokens, long completionTokens,
                                 long cachedTokens, long cacheCreationTokens, BigDecimal cost) {
        UsageHourly u = new UsageHourly();
        u.setStatHour(statHour);
        u.setTenantId(tenantId);
        u.setAppId(appId);
        u.setProvider(provider);
        u.setModel(model);
        u.setReqCnt(reqCnt);
        u.setErrCnt(errCnt);
        u.setPromptTokens(promptTokens);
        u.setCompletionTokens(completionTokens);
        u.setCachedTokens(cachedTokens);
        u.setCacheCreationTokens(cacheCreationTokens);
        u.setCost(cost);
        return u;
    }
}

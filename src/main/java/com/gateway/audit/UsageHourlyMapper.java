package com.gateway.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface UsageHourlyMapper extends BaseMapper<UsageHourly> {

    /** 幂等累加：同一 (小时, app, provider, model) 维度重复写入时做增量合并。 */
    @Insert("""
            INSERT INTO gw_usage_hourly
              (stat_hour, tenant_id, app_id, provider, model, req_cnt, err_cnt, prompt_tokens, completion_tokens,
               cached_tokens, cache_creation_tokens, cost)
            VALUES (#{statHour}, #{tenantId}, #{appId}, #{provider}, #{model}, #{reqCnt}, #{errCnt},
                    #{promptTokens}, #{completionTokens}, #{cachedTokens}, #{cacheCreationTokens}, #{cost})
            ON DUPLICATE KEY UPDATE
              req_cnt = req_cnt + VALUES(req_cnt),
              err_cnt = err_cnt + VALUES(err_cnt),
              prompt_tokens = prompt_tokens + VALUES(prompt_tokens),
              completion_tokens = completion_tokens + VALUES(completion_tokens),
              cached_tokens = cached_tokens + VALUES(cached_tokens),
              cache_creation_tokens = cache_creation_tokens + VALUES(cache_creation_tokens),
              cost = cost + VALUES(cost)
            """)
    int upsert(LocalDateTime statHour, Long tenantId, Long appId, String provider, String model,
               int reqCnt, int errCnt, long promptTokens, long completionTokens,
               long cachedTokens, long cacheCreationTokens, BigDecimal cost);
}

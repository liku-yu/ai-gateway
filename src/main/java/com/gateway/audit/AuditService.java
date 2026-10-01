package com.gateway.audit;

import com.gateway.filter.RequestContext;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.JsonSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计与用量聚合入队。
 * 注意：所有写入内容都必须已经脱敏；这里不接受也不存储任何请求正文或凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AsyncLogSink sink;

    /** 记录一次请求（成功或失败都会调用，保证失败也有痕）。 */
    public void recordRequest(RequestContext ctx, boolean success, ErrorCode errorCode) {
        try {
            RequestLog rl = new RequestLog();
            rl.setTraceId(ctx.getTraceId());
            rl.setRequestId(ctx.getRequestId());
            rl.setTenantId(ctx.getApp() == null ? null : ctx.getApp().getTenantId());
            rl.setAppId(ctx.getApp() == null ? null : ctx.getApp().getId());
            rl.setApiKeyId(ctx.getApiKey() == null ? null : ctx.getApiKey().getId());
            rl.setLogicalModel(ctx.getLogicalModel());
            rl.setProvider(ctx.getChannel() == null || ctx.getChannel().getProvider() == null
                    ? null : ctx.getChannel().getProvider().getCode());
            rl.setChannelId(ctx.getChannel() == null ? null : ctx.getChannel().getId());
            rl.setStream(ctx.isStream() ? 1 : 0);
            rl.setSuccess(success ? 1 : 0);
            rl.setStatusCode(ctx.getStatusCode());
            rl.setErrorCode(errorCode == null ? ctx.getErrorCode() : errorCode.name());
            rl.setTtfbMs(ctx.getTtfbMs());
            rl.setCostMs((int) ctx.elapsedMs());
            rl.setPromptTokens(ctx.getUsage() == null ? 0 : ctx.getUsage().promptOrZero());
            rl.setCompletionTokens(ctx.getUsage() == null ? 0 : ctx.getUsage().completionOrZero());
            rl.setCachedTokens(ctx.getUsage() == null ? 0 : ctx.getUsage().cachedOrZero());
            rl.setCacheCreationTokens(ctx.getUsage() == null ? 0 : ctx.getUsage().cacheCreationOrZero());
            rl.setUsageEstimated(ctx.isUsageEstimated() ? 1 : 0);
            rl.setCost(ctx.getActualCost() == null ? BigDecimal.ZERO : ctx.getActualCost());
            rl.setCurrency(ctx.getPrice() == null ? "CNY" : ctx.getPrice().getCurrency());
            rl.setRetryCnt(ctx.getRetryCount());
            rl.setDegraded(ctx.isDegraded() ? 1 : 0);
            rl.setMaskedCnt(ctx.getMaskedCount() == null ? 0 : ctx.getMaskedCount());
            rl.setClientIp(ctx.getClientIp());
            rl.setCreateTime(LocalDateTime.now());
            sink.submit(rl);
        } catch (Exception e) {
            log.debug("组装访问日志失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 用量聚合入队。
     *
     * 注意：这里**必须**只入队、不做 DB 写。早期实现直接同步 upsert，
     * 结果在压测中把 Netty 事件循环堵住（P50 从 <5ms 恶化到 150ms），
     * 这是「热路径零 DB 查询」原则最容易被悄悄破坏的地方。
     */
    public void recordUsageHourly(RequestContext ctx) {
        if (ctx.getUsage() == null || ctx.getApp() == null) {
            return;
        }
        try {
            LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
            String provider = ctx.getChannel() == null || ctx.getChannel().getProvider() == null
                    ? null : ctx.getChannel().getProvider().getCode();
            sink.submit(UsageHourly.of(hour, ctx.getApp().getTenantId(), ctx.getApp().getId(),
                    provider, ctx.getPhysicalModel(),
                    1, ctx.isSuccess() ? 0 : 1,
                    ctx.getUsage().promptOrZero(), ctx.getUsage().completionOrZero(),
                    ctx.getUsage().cachedOrZero(), ctx.getUsage().cacheCreationOrZero(),
                    ctx.getActualCost() == null ? BigDecimal.ZERO : ctx.getActualCost()));
        } catch (Exception e) {
            log.debug("用量聚合入队失败（忽略）: {}", e.getMessage());
        }
    }

    /** 审计事件：配置变更、熔断、脱敏命中统计等。detail 中不得含明文凭据。 */
    public void audit(String eventType, RequestContext ctx, String targetType, String targetId, Map<String, Object> detail) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (detail != null) {
                detail.forEach((k, v) -> {
                    if (isSensitiveKey(k)) {
                        safe.put(k, "<已隐藏>");
                    } else {
                        safe.put(k, v);
                    }
                });
            }
            sink.submit(AuditEvent.of(eventType,
                    ctx == null || ctx.getApp() == null ? "system" : "app:" + ctx.getApp().getId(),
                    targetType, targetId, JsonSupport.write(safe),
                    ctx == null ? null : ctx.getTraceId()));
        } catch (Exception e) {
            log.debug("审计事件写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 字段名命中敏感词一律不落库，作为「双保险」防止调用方误传明文。 */
    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("key") || k.contains("secret") || k.contains("token")
                || k.contains("password") || k.contains("credential");
    }
}

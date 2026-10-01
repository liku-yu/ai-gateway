package com.gateway.audit;

import com.gateway.infra.GatewayProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志/审计的异步落库通道。
 *
 * 设计取舍：**宁可丢日志，不可拖慢请求**。
 * - 有界队列 + 非阻塞 offer：队列满时直接丢弃并计数（打点告警），绝不让业务线程等待 DB；
 * - 单线程消费 + 批量提交，降低 DB 压力；
 * - 落库异常不向上抛，避免影响主链路（主链路早已返回响应）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncLogSink {

    private final GatewayProperties properties;
    private final RequestLogMapper requestLogMapper;
    private final AuditEventMapper auditEventMapper;
    private final UsageHourlyMapper usageHourlyMapper;

    private BlockingQueue<Object> queue;
    private volatile boolean running = true;
    private Thread worker;
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();

    @PostConstruct
    void start() {
        this.queue = new ArrayBlockingQueue<>(properties.getLogging().getAsyncQueueCapacity());
        this.worker = Thread.ofPlatform()
                .name("gw-log-sink")
                .daemon(true)
                .start(this::consumeLoop);
        log.info("异步日志通道已启动，队列容量={}", properties.getLogging().getAsyncQueueCapacity());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void submit(RequestLog logEntry) {
        offer(logEntry);
    }

    public void submit(AuditEvent event) {
        offer(event);
    }

    public void submit(UsageHourly usage) {
        offer(usage);
    }

    private void offer(Object item) {
        if (!running) {
            return;
        }
        if (!queue.offer(item)) {
            long n = dropped.incrementAndGet();
            if (n % 1000 == 1) {
                log.warn("日志队列已满，已丢弃 {} 条（不影响业务响应）", n);
            }
            return;
        }
        enqueued.incrementAndGet();
    }

    private void consumeLoop() {
        List<Object> batch = new ArrayList<>(320);
        List<RequestLog> logs = new ArrayList<>(256);
        List<AuditEvent> events = new ArrayList<>(64);
        List<UsageHourly> usages = new ArrayList<>(64);
        while (running || !queue.isEmpty()) {
            try {
                batch.clear();
                Object first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                }
                // 一次性把队列里剩余的都收走，减少 DB 往返
                queue.drainTo(batch, 320 - batch.size());
                classifyAll(batch, logs, events, usages);
                flush(logs, events, usages);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("异步日志写入异常（已忽略，不影响业务）", e);
                batch.clear();
                logs.clear();
                events.clear();
                usages.clear();
            }
        }
    }

    private void classifyAll(List<Object> batch, List<RequestLog> logs,
                             List<AuditEvent> events, List<UsageHourly> usages) {
        for (Object item : batch) {
            if (item instanceof RequestLog rl) {
                logs.add(rl);
            } else if (item instanceof AuditEvent ae) {
                events.add(ae);
            } else if (item instanceof UsageHourly uh) {
                usages.add(uh);
            }
        }
    }

    private void flush(List<RequestLog> logs, List<AuditEvent> events, List<UsageHourly> usages) {
        int written = 0;
        for (RequestLog rl : logs) {
            try {
                requestLogMapper.insert(rl);
                written++;
            } catch (Exception e) {
                log.debug("访问日志写入失败: {}", e.getMessage());
            }
        }
        for (AuditEvent ae : events) {
            try {
                auditEventMapper.insert(ae);
                written++;
            } catch (Exception e) {
                log.debug("审计事件写入失败: {}", e.getMessage());
            }
        }
        // 用量聚合走 upsert：同一 (小时, app, provider, model) 维度增量合并；
        // 批量场景下先按维度合并再写，减少 DB 往返。
        for (UsageHourly uh : mergeUsages(usages)) {
            try {
                usageHourlyMapper.upsert(uh.getStatHour(), uh.getTenantId(), uh.getAppId(),
                        uh.getProvider(), uh.getModel(), uh.getReqCnt(), uh.getErrCnt(),
                        uh.getPromptTokens() == null ? 0L : uh.getPromptTokens(),
                        uh.getCompletionTokens() == null ? 0L : uh.getCompletionTokens(),
                        uh.getCachedTokens() == null ? 0L : uh.getCachedTokens(),
                        uh.getCacheCreationTokens() == null ? 0L : uh.getCacheCreationTokens(),
                        uh.getCost() == null ? java.math.BigDecimal.ZERO : uh.getCost());
                written++;
            } catch (Exception e) {
                log.debug("用量聚合写入失败: {}", e.getMessage());
            }
        }
        persisted.addAndGet(written);
        logs.clear();
        events.clear();
        usages.clear();
    }

    /** 按聚合维度合并同批次数据，避免同一个 key 在一批里被 upsert 多次。 */
    private List<UsageHourly> mergeUsages(List<UsageHourly> usages) {
        if (usages.size() <= 1) {
            return usages;
        }
        java.util.Map<String, UsageHourly> merged = new java.util.LinkedHashMap<>();
        for (UsageHourly u : usages) {
            String key = u.getStatHour() + "|" + u.getAppId() + "|" + u.getProvider() + "|" + u.getModel();
            if (merged.containsKey(key)) {
                UsageHourly acc = merged.get(key);
                acc.setReqCnt(acc.getReqCnt() + u.getReqCnt());
                acc.setErrCnt(acc.getErrCnt() + u.getErrCnt());
                acc.setPromptTokens((acc.getPromptTokens() == null ? 0 : acc.getPromptTokens())
                        + (u.getPromptTokens() == null ? 0 : u.getPromptTokens()));
                acc.setCompletionTokens((acc.getCompletionTokens() == null ? 0 : acc.getCompletionTokens())
                        + (u.getCompletionTokens() == null ? 0 : u.getCompletionTokens()));
                acc.setCachedTokens((acc.getCachedTokens() == null ? 0 : acc.getCachedTokens())
                        + (u.getCachedTokens() == null ? 0 : u.getCachedTokens()));
                acc.setCacheCreationTokens((acc.getCacheCreationTokens() == null ? 0 : acc.getCacheCreationTokens())
                        + (u.getCacheCreationTokens() == null ? 0 : u.getCacheCreationTokens()));
                acc.setCost((acc.getCost() == null ? java.math.BigDecimal.ZERO : acc.getCost())
                        .add(u.getCost() == null ? java.math.BigDecimal.ZERO : u.getCost()));
            } else {
                merged.put(key, u);
            }
        }
        return new ArrayList<>(merged.values());
    }

    public long enqueuedCount() {
        return enqueued.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long persistedCount() {
        return persisted.get();
    }

    public int queueSize() {
        return queue == null ? 0 : queue.size();
    }
}

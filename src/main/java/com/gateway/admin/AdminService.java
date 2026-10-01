package com.gateway.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gateway.adapter.ModelProvider;
import com.gateway.adapter.ProviderRegistry;
import com.gateway.adapter.UpstreamRequest;
import com.gateway.audit.AuditEvent;
import com.gateway.audit.AuditEventMapper;
import com.gateway.circuit.CircuitRegistry;
import com.gateway.circuit.CooldownTracker;
import com.gateway.domain.ApiKey;
import com.gateway.domain.App;
import com.gateway.domain.Channel;
import com.gateway.domain.LogicalModel;
import com.gateway.domain.Price;
import com.gateway.domain.Provider;
import com.gateway.domain.mapper.ApiKeyMapper;
import com.gateway.domain.mapper.AppMapper;
import com.gateway.domain.mapper.ChannelMapper;
import com.gateway.domain.mapper.LogicalModelMapper;
import com.gateway.domain.mapper.PriceMapper;
import com.gateway.domain.mapper.ProviderMapper;
import com.gateway.infra.ApiKeyHasher;
import com.gateway.infra.ConfigCache;
import com.gateway.infra.ConfigSnapshot;
import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.GatewayProperties;
import com.gateway.infra.JsonSupport;
import com.gateway.infra.SecretCipher;
import com.gateway.protocol.ChatRequest;
import com.gateway.protocol.ChatResponse;
import com.gateway.protocol.Message;
import com.gateway.ratelimit.BalanceKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理业务层：所有配置变更与查询的唯一实现。
 *
 * Web 控制台（{@code /admin/api/**}）与命令行（{@code gateway ...}）都调用这里，
 * 避免「CLI 会写审计 / API 忘了写」「CLI 会广播 / API 不会」这类两端行为漂移。
 *
 * 线程模型：本类方法多为阻塞调用（MyBatis、ConfigCache.reload、Redis block），
 * 调用方必须保证不在 Netty 事件循环上执行（API 控制器统一切到 boundedElastic）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final String DEFAULT_MASTER_KEY = "ZGV2LW9ubHktbWFzdGVyLWtleS0zMmJ5dGVzISExMjM=";
    private static final String DEFAULT_SALT = "dev-only-salt-change-me";

    private final ConfigCache configCache;
    private final GatewayProperties properties;
    private final ReactiveStringRedisTemplate redis;
    private final AuditEventMapper auditEventMapper;
    private final CircuitRegistry circuitRegistry;
    private final CooldownTracker cooldownTracker;
    private final ProviderRegistry providerRegistry;
    private final SecretCipher secretCipher;
    private final ApiKeyHasher apiKeyHasher;
    private final JdbcTemplate jdbcTemplate;

    private final ProviderMapper providerMapper;
    private final ChannelMapper channelMapper;
    private final LogicalModelMapper logicalModelMapper;
    private final AppMapper appMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final PriceMapper priceMapper;

    // ==================================================================
    // 结果类型
    // ==================================================================

    public record Check(String name, String level, String detail) {
    }

    public record TestResult(boolean ok, long latencyMs, String detail) {
    }

    public record CreatedKey(Long id, Long appId, String key, LocalDateTime expireAt) {
    }

    // ==================================================================
    // 概览 / 自检
    // ==================================================================

    public Map<String, Object> status() {
        ConfigSnapshot s = configCache.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configVersion", s.version());
        out.put("loadedAt", Instant.ofEpochMilli(s.loadedAt()).toString());
        out.put("providers", s.providers().size());
        out.put("channels", s.channelCount());
        out.put("models", s.models().size());
        out.put("virtualKeys", s.keyCount());
        out.put("prices", s.prices().size());
        out.put("circuitOpen", circuitRegistry.openCount());
        out.put("circuitDetail", circuitRegistry.snapshot());
        out.put("adapters", providerRegistry.adapterCodes());
        return out;
    }

    public List<Check> doctor() {
        List<Check> checks = new ArrayList<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            checks.add(new Check("数据库", "OK", "连接正常"));
        } catch (Exception e) {
            checks.add(new Check("数据库", "FAIL", "连接失败: " + e.getMessage()));
        }
        try {
            Boolean ok = redis.hasKey("gw:doctor:probe").block(Duration.ofSeconds(2));
            checks.add(new Check("Redis", "OK", "连接正常" + (Boolean.TRUE.equals(ok) ? "（探测键存在）" : "")));
        } catch (Exception e) {
            checks.add(new Check("Redis", "FAIL", "连接失败: " + e.getMessage()));
        }
        checks.add(checkMasterKey());
        checks.add(checkDefaults());
        var codes = providerRegistry.adapterCodes();
        checks.add(codes.isEmpty()
                ? new Check("上游适配器", "FAIL", "未注册任何适配器")
                : new Check("上游适配器", "OK", String.join(", ", codes)));
        ConfigSnapshot s = configCache.current();
        checks.add(s.version() <= 0
                ? new Check("配置快照", "WARN", "尚未加载")
                : new Check("配置快照", "OK",
                        "version=" + s.version() + ", 渠道=" + s.channelCount() + ", 模型=" + s.models().size()));
        return checks;
    }

    private Check checkMasterKey() {
        String master = properties.getCrypto().getMasterKey();
        if (master == null || master.isBlank()) {
            return new Check("加密主密钥", "FAIL", "未配置 gateway.crypto.master-key");
        }
        try {
            int len = Base64.getDecoder().decode(master).length;
            if (len != 32) {
                return new Check("加密主密钥", "FAIL", "Base64 解码后为 " + len + " 字节，应为 32");
            }
        } catch (Exception e) {
            return new Check("加密主密钥", "FAIL", "不是合法 Base64");
        }
        return DEFAULT_MASTER_KEY.equals(master)
                ? new Check("加密主密钥", "WARN", "正在使用开发默认值，生产必须替换")
                : new Check("加密主密钥", "OK", "已配置（32 字节）");
    }

    private Check checkDefaults() {
        List<String> defaults = new ArrayList<>();
        if ("admin".equals(properties.getAdmin().getUsername())
                && "admin123".equals(properties.getAdmin().getPassword())) {
            defaults.add("管理员账号密码");
        }
        if ("dev-admin-token".equals(properties.getAdmin().getToken())) {
            defaults.add("机器令牌");
        }
        if (DEFAULT_SALT.equals(properties.getApiKeySalt())) {
            defaults.add("虚拟 Key 盐值");
        }
        return defaults.isEmpty()
                ? new Check("默认凭据", "OK", "未使用开发默认值")
                : new Check("默认凭据", "WARN", String.join("、", defaults) + " 仍为开发默认值");
    }

    // ==================================================================
    // 供应商
    // ==================================================================

    public List<Map<String, Object>> providerViews() {
        Map<Long, Long> channelCount = channelMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(Channel::getProviderId, Collectors.counting()));
        List<Provider> providers = providerMapper.selectList(null);
        providers.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provider p : providers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("baseUrl", p.getBaseUrl());
            m.put("adapterClass", p.getAdapterClass());
            m.put("enabled", p.isAvailable());
            m.put("channelCount", channelCount.getOrDefault(p.getId(), 0L));
            out.add(m);
        }
        return out;
    }

    public Provider addProvider(String code, String name, String baseUrl, String adapter, boolean disabled) {
        if (code == null || code.isBlank()) {
            throw invalid("code 不能为空");
        }
        String adapterCode = (adapter == null || adapter.isBlank()) ? "openai-compatible" : adapter.trim();
        if (!providerRegistry.supports(adapterCode)) {
            throw invalid("未知适配器: " + adapterCode + "，可用: " + providerRegistry.adapterCodes());
        }
        if (providerMapper.selectList(null).stream().anyMatch(p -> p.getCode().equalsIgnoreCase(code))) {
            throw invalid("供应商 code 已存在: " + code);
        }
        Provider p = new Provider();
        p.setCode(code.trim());
        p.setName(name == null || name.isBlank() ? code.trim() : name.trim());
        p.setBaseUrl(baseUrl);
        p.setAdapterClass(adapterCode);
        p.setEnabled(disabled ? 0 : 1);
        providerMapper.insert(p);
        changed("PROVIDER_ADD", "provider", p.getCode(),
                Map.of("adapter", adapterCode, "baseUrl", baseUrl == null ? "" : baseUrl));
        return p;
    }

    public Provider updateProvider(Long id, String name, String baseUrl, String adapter, Boolean enabled) {
        Provider p = providerMapper.selectById(id);
        if (p == null) {
            throw invalid("供应商不存在: " + id);
        }
        if (name != null && !name.isBlank()) {
            p.setName(name.trim());
        }
        if (baseUrl != null) {
            p.setBaseUrl(baseUrl.isBlank() ? null : baseUrl.trim());
        }
        if (adapter != null && !adapter.isBlank()) {
            if (!providerRegistry.supports(adapter.trim())) {
                throw invalid("未知适配器: " + adapter + "，可用: " + providerRegistry.adapterCodes());
            }
            p.setAdapterClass(adapter.trim());
        }
        if (enabled != null) {
            p.setEnabled(enabled ? 1 : 0);
        }
        providerMapper.updateById(p);
        changed("PROVIDER_UPDATE", "provider", p.getCode(), Map.of());
        return p;
    }

    public void deleteProvider(Long id) {
        Provider p = providerMapper.selectById(id);
        if (p == null) {
            throw invalid("供应商不存在: " + id);
        }
        long n = channelMapper.selectList(null).stream().filter(c -> id.equals(c.getProviderId())).count();
        if (n > 0) {
            throw invalid("该供应商下仍有 " + n + " 条渠道，请先删除渠道");
        }
        providerMapper.deleteById(id);
        changed("PROVIDER_DELETE", "provider", p.getCode(), Map.of());
    }

    public TestResult testProvider(Long channelId, String model, int timeoutSeconds) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw invalid("渠道不存在: " + channelId);
        }
        Provider provider = providerMapper.selectById(channel.getProviderId());
        if (provider == null) {
            throw invalid("渠道关联的供应商不存在: " + channel.getProviderId());
        }
        // 必须把 provider 挂回 channel：resolveBaseUrl() 在渠道未单独配 baseUrl 时
        // 要回退到供应商默认地址，而 mapper.selectById 查出的 channel.provider 为 null。
        channel.setProvider(provider);
        ModelProvider adapter = providerRegistry.requireFor(provider);
        if ((channel.getApiKeyEnc() == null || channel.getApiKeyEnc().isBlank())
                && !"ollama".equals(adapter.code())) {
            throw invalid("渠道未配置密钥");
        }
        String physical = (model != null && !model.isBlank()) ? model : firstMappedModel(channel);
        if (physical == null) {
            throw invalid("无法确定物理模型，请指定 model");
        }

        ChatRequest request = new ChatRequest();
        request.setModel(physical);
        request.setMaxTokens(8);
        request.setMessages(List.of(Message.text("user", "ping")));

        String apiKey = channel.getApiKeyEnc() == null ? null
                : secretCipher.decrypt(channel.getApiKeyEnc());
        UpstreamRequest upstream = UpstreamRequest.chat(
                channel.resolveBaseUrl(), apiKey, physical, Math.max(1, timeoutSeconds) * 1000,
                request, null, null);

        long start = System.nanoTime();
        try {
            ChatResponse resp = adapter.chat(upstream).block(Duration.ofSeconds(timeoutSeconds + 5L));
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()
                    ? "(空)" : resp.getChoices().get(0).getMessage().contentAsText();
            String usage = resp == null || resp.getUsage() == null ? ""
                    : " | prompt=" + resp.getUsage().promptOrZero()
                    + " completion=" + resp.getUsage().completionOrZero()
                    + " cached=" + resp.getUsage().cachedOrZero();
            return new TestResult(true, ms, trim(text, 120) + usage);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new TestResult(false, ms, rootMessage(e));
        }
    }

    private static String firstMappedModel(Channel channel) {
        if (channel.getModelMapping() == null || channel.getModelMapping().isEmpty()) {
            return null;
        }
        return channel.getModelMapping().values().iterator().next();
    }

    // ==================================================================
    // 渠道
    // ==================================================================

    public List<Map<String, Object>> channelViews() {
        Map<Long, String> providers = providerMapper.selectList(null).stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getCode, (a, b) -> a));
        List<Channel> channels = channelMapper.selectList(null);
        channels.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Channel c : channels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("providerId", c.getProviderId());
            m.put("provider", providers.get(c.getProviderId()));
            m.put("status", c.getStatus());
            m.put("modelMapping", c.getModelMapping());
            m.put("hasKey", c.getApiKeyEnc() != null && !c.getApiKeyEnc().isBlank());
            m.put("baseUrl", c.getBaseUrl());
            m.put("weight", c.getWeight());
            m.put("priority", c.getPriority());
            m.put("rpmLimit", c.getRpmLimit());
            m.put("tpmLimit", c.getTpmLimit());
            m.put("concurrencyLimit", c.getConcurrencyLimit());
            m.put("timeoutMs", c.getTimeoutMs());
            m.put("circuit", circuitRegistry.breaker(c.getId()).getState().name());
            out.add(m);
        }
        return out;
    }

    public Channel addChannel(String providerCode, String name, Map<String, String> models, String baseUrl,
                              Integer weight, Integer priority, Integer rpm, Integer tpm,
                              Integer concurrency, Integer timeoutMs, String status) {
        Provider provider = providerMapper.selectList(null).stream()
                .filter(x -> x.getCode().equalsIgnoreCase(providerCode))
                .findFirst()
                .orElseThrow(() -> invalid("未知供应商 code: " + providerCode));
        String st = (status == null || status.isBlank()) ? "DISABLED" : status.toUpperCase();
        if (!List.of("ACTIVE", "DISABLED").contains(st)) {
            throw invalid("status 只能是 ACTIVE 或 DISABLED");
        }
        if (models == null || models.isEmpty()) {
            throw invalid("模型映射不能为空");
        }
        Channel channel = new Channel();
        channel.setProviderId(provider.getId());
        channel.setName(name);
        channel.setBaseUrl(baseUrl);
        channel.setWeight(weight == null ? 100 : weight);
        channel.setPriority(priority == null ? 0 : priority);
        channel.setModelMapping(new LinkedHashMap<>(models));
        channel.setRpmLimit(rpm);
        channel.setTpmLimit(tpm);
        channel.setConcurrencyLimit(concurrency);
        channel.setTimeoutMs(timeoutMs == null ? 30000 : timeoutMs);
        channel.setStatus(st);
        channelMapper.insert(channel);
        changed("CHANNEL_ADD", "channel", String.valueOf(channel.getId()),
                Map.of("name", name, "provider", providerCode, "status", st));
        return channel;
    }

    public Channel setChannelKey(Long id, String key, String baseUrl, boolean activate) {
        if (key == null || key.isBlank()) {
            throw invalid("apiKey 不能为空");
        }
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw invalid("渠道不存在: " + id);
        }
        String masked = SecretCipher.maskTail(key.trim());
        channel.setApiKeyEnc(secretCipher.encrypt(key.trim()));
        if (baseUrl != null && !baseUrl.isBlank()) {
            channel.setBaseUrl(baseUrl.trim());
        }
        if (activate) {
            channel.setStatus("ACTIVE");
        }
        channelMapper.updateById(channel);
        changed("CHANNEL_KEY_ROTATE", "channel", String.valueOf(id),
                Map.of("keyTail", masked, "status", channel.getStatus()));
        return channel;
    }

    public void setChannelStatus(Long id, String status) {
        String st = status == null ? "" : status.toUpperCase();
        if (!List.of("ACTIVE", "DISABLED").contains(st)) {
            throw invalid("status 只能是 ACTIVE 或 DISABLED");
        }
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw invalid("渠道不存在: " + id);
        }
        if ("ACTIVE".equals(st) && (channel.getApiKeyEnc() == null || channel.getApiKeyEnc().isBlank())) {
            throw invalid("该渠道尚未配置密钥，不能启用");
        }
        channelMapper.updateStatus(id, st);
        changed("CHANNEL_STATUS_CHANGE", "channel", String.valueOf(id), Map.of("status", st));
    }

    public boolean resetCircuit(Long id) {
        circuitRegistry.reset(id);
        Boolean released = cooldownTracker.release(id).block(Duration.ofSeconds(3));
        boolean ok = Boolean.TRUE.equals(released);
        changed("CIRCUIT_RESET", "channel", String.valueOf(id), Map.of("released", ok));
        return ok;
    }

    public void deleteChannel(Long id) {
        Channel c = channelMapper.selectById(id);
        if (c == null) {
            throw invalid("渠道不存在: " + id);
        }
        channelMapper.deleteById(id);
        changed("CHANNEL_DELETE", "channel", String.valueOf(id), Map.of("name", c.getName()));
    }

    /** 复制渠道（含同一份加密密钥），新渠道默认 DISABLED，避免未经确认就参与路由。 */
    public Channel copyChannel(Long id, String newName) {
        Channel src = channelMapper.selectById(id);
        if (src == null) {
            throw invalid("渠道不存在: " + id);
        }
        Channel copy = new Channel();
        copy.setProviderId(src.getProviderId());
        copy.setName((newName == null || newName.isBlank()) ? src.getName() + "-copy" : newName.trim());
        copy.setApiKeyEnc(src.getApiKeyEnc());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setWeight(src.getWeight());
        copy.setPriority(src.getPriority());
        copy.setModelMapping(src.getModelMapping() == null ? null : new LinkedHashMap<>(src.getModelMapping()));
        copy.setRpmLimit(src.getRpmLimit());
        copy.setTpmLimit(src.getTpmLimit());
        copy.setConcurrencyLimit(src.getConcurrencyLimit());
        copy.setTimeoutMs(src.getTimeoutMs());
        copy.setStatus("DISABLED");
        channelMapper.insert(copy);
        changed("CHANNEL_COPY", "channel", String.valueOf(copy.getId()), Map.of("from", id));
        return copy;
    }

    /** 批量连通性测试（new-api 的 /channel/test 思路）：逐条发最小请求，返回每条结果。 */
    public List<Map<String, Object>> testAllChannels(String model, int timeoutSeconds) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Channel> channels = channelMapper.selectList(null);
        channels.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (Channel c : channels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("status", c.getStatus());
            try {
                TestResult r = testProvider(c.getId(), model, timeoutSeconds);
                m.put("ok", r.ok());
                m.put("latencyMs", r.latencyMs());
                m.put("detail", r.detail());
            } catch (Exception e) {
                m.put("ok", false);
                m.put("latencyMs", 0);
                m.put("detail", rootMessage(e));
            }
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // 逻辑模型
    // ==================================================================

    public List<LogicalModel> models() {
        List<LogicalModel> list = logicalModelMapper.selectList(null);
        list.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return list;
    }

    public LogicalModel addModel(String name, String type, String description, List<String> fallback) {
        if (name == null || name.isBlank()) {
            throw invalid("逻辑模型名不能为空");
        }
        if (logicalModelMapper.selectList(null).stream().anyMatch(m -> m.getLogicalName().equals(name))) {
            throw invalid("逻辑模型已存在: " + name);
        }
        LogicalModel m = new LogicalModel();
        m.setLogicalName(name.trim());
        m.setType((type == null || type.isBlank()) ? "CHAT" : type.toUpperCase());
        m.setDescription(description);
        m.setFallbackChain(normalizeList(fallback));
        m.setEnabled(1);
        logicalModelMapper.insert(m);
        changed("MODEL_ADD", "model", m.getLogicalName(), Map.of("type", m.getType()));
        return m;
    }

    public LogicalModel setFallback(String name, List<String> fallback) {
        LogicalModel m = requireModel(name);
        m.setFallbackChain(normalizeList(fallback));
        logicalModelMapper.updateById(m);
        changed("MODEL_FALLBACK_CHANGE", "model", name,
                Map.of("fallback", m.getFallbackChain() == null ? "" : String.join(",", m.getFallbackChain())));
        return m;
    }

    public LogicalModel setModelEnabled(String name, boolean enabled) {
        LogicalModel m = requireModel(name);
        m.setEnabled(enabled ? 1 : 0);
        logicalModelMapper.updateById(m);
        changed("MODEL_STATUS_CHANGE", "model", name, Map.of("enabled", enabled));
        return m;
    }

    private LogicalModel requireModel(String name) {
        return logicalModelMapper.selectList(null).stream()
                .filter(x -> x.getLogicalName().equals(name))
                .findFirst()
                .orElseThrow(() -> invalid("逻辑模型不存在: " + name));
    }

    // ==================================================================
    // 应用
    // ==================================================================

    public List<App> apps() {
        List<App> list = appMapper.selectList(null);
        list.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return list;
    }

    public App addApp(String name, Long tenantId, BigDecimal daily, BigDecimal monthly, List<String> models) {
        if (name == null || name.isBlank()) {
            throw invalid("应用名不能为空");
        }
        App app = new App();
        app.setTenantId(tenantId == null ? 1L : tenantId);
        app.setName(name.trim());
        app.setDailyBudget(daily);
        app.setMonthlyBudget(monthly);
        app.setAllowedModels(normalizeList(models));
        app.setStatus(1);
        appMapper.insert(app);
        changed("APP_ADD", "app", String.valueOf(app.getId()), Map.of("name", name));
        return app;
    }

    /** 应用余额（分）。余额是 Redis 运行时状态，Web 控制台可直接查看/设置。 */
    public long balance(Long appId) {
        if (appMapper.selectById(appId) == null) {
            throw invalid("应用不存在: " + appId);
        }
        String v = redis.opsForValue().get(BalanceKeys.balance(appId)).block(Duration.ofSeconds(2));
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 设置应用余额（分），用于本地演示/充值；生产应对接真实计费系统。 */
    public long setBalance(Long appId, long fen) {
        if (appMapper.selectById(appId) == null) {
            throw invalid("应用不存在: " + appId);
        }
        if (fen < 0) {
            throw invalid("余额不能为负");
        }
        redis.opsForValue().set(BalanceKeys.balance(appId), String.valueOf(fen)).block(Duration.ofSeconds(3));
        changed("APP_BALANCE_SET", "app", String.valueOf(appId), Map.of("balanceFen", fen));
        return fen;
    }

    public App setAppStatus(Long id, boolean enabled) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw invalid("应用不存在: " + id);
        }
        app.setStatus(enabled ? 1 : 0);
        appMapper.updateById(app);
        changed("APP_STATUS_CHANGE", "app", String.valueOf(id), Map.of("status", enabled));
        return app;
    }

    // ==================================================================
    // 虚拟 Key
    // ==================================================================

    public List<Map<String, Object>> keyViews(Long appId) {
        Map<Long, String> apps = appMapper.selectList(null).stream()
                .collect(Collectors.toMap(App::getId, App::getName, (a, b) -> a));
        List<ApiKey> keys = apiKeyMapper.selectList(null).stream()
                .filter(k -> appId == null || appId.equals(k.getAppId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("appId", k.getAppId());
            m.put("appName", apps.get(k.getAppId()));
            m.put("keyPrefix", k.getKeyPrefix());
            m.put("status", k.isAvailable() ? "启用" : "已吊销");
            m.put("expireAt", k.getExpireAt() == null ? null : k.getExpireAt().toLocalDate().toString());
            m.put("rpmLimit", k.getRpmLimit());
            m.put("tpmLimit", k.getTpmLimit());
            m.put("concurrencyLimit", k.getConcurrencyLimit());
            out.add(m);
        }
        return out;
    }

    public CreatedKey createKey(Long appId, Integer rpm, Integer tpm, Integer concurrency, LocalDate expire) {
        App app = appMapper.selectById(appId);
        if (app == null) {
            throw invalid("应用不存在: " + appId);
        }
        String raw = "sk-gw-" + randomHex(20);
        ApiKey key = new ApiKey();
        key.setAppId(appId);
        key.setKeyHash(apiKeyHasher.hash(raw));
        key.setKeyPrefix(apiKeyHasher.prefixOf(raw));
        key.setRpmLimit(rpm);
        key.setTpmLimit(tpm);
        key.setConcurrencyLimit(concurrency);
        key.setStatus(1);
        key.setExpireAt(expire == null ? null : expire.atTime(23, 59, 59));
        apiKeyMapper.insert(key);
        changed("API_KEY_CREATE", "api_key", String.valueOf(key.getId()), Map.of("appId", appId));
        return new CreatedKey(key.getId(), appId, raw, key.getExpireAt());
    }

    public void revokeKey(Long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw invalid("虚拟 Key 不存在: " + id);
        }
        key.setStatus(0);
        apiKeyMapper.updateById(key);
        changed("API_KEY_REVOKE", "api_key", String.valueOf(id), Map.of("appId", key.getAppId()));
    }

    // ==================================================================
    // 定价
    // ==================================================================

    public List<Price> prices() {
        LocalDateTime now = LocalDateTime.now();
        List<Price> list = priceMapper.selectList(new QueryWrapper<Price>()
                .le("effective_from", now)
                .and(w -> w.isNull("effective_to").or().gt("effective_to", now)));
        list.sort((a, b) -> (a.getProvider() + a.getModel()).compareTo(b.getProvider() + b.getModel()));
        return list;
    }

    public void setPrice(String provider, String model, BigDecimal input, BigDecimal output,
                         BigDecimal cacheRead, BigDecimal cacheWrite, String currency) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw invalid("provider/model 不能为空");
        }
        if (input == null || output == null) {
            throw invalid("input/output 价格不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Price existing = priceMapper.selectList(new QueryWrapper<Price>()
                        .eq("provider", provider)
                        .eq("model", model)
                        .le("effective_from", now)
                        .and(w -> w.isNull("effective_to").or().gt("effective_to", now))
                        .orderByDesc("effective_from"))
                .stream().findFirst().orElse(null);
        BigDecimal cr = cacheRead == null ? BigDecimal.ZERO : cacheRead;
        BigDecimal cw = cacheWrite == null ? BigDecimal.ZERO : cacheWrite;
        String cur = (currency == null || currency.isBlank()) ? "CNY" : currency;
        if (existing != null) {
            existing.setInputPrice(input);
            existing.setOutputPrice(output);
            existing.setCacheReadPrice(cr);
            existing.setCacheWritePrice(cw);
            existing.setCurrency(cur);
            priceMapper.updateById(existing);
        } else {
            Price p = new Price();
            p.setProvider(provider);
            p.setModel(model);
            p.setInputPrice(input);
            p.setOutputPrice(output);
            p.setCacheReadPrice(cr);
            p.setCacheWritePrice(cw);
            p.setCurrency(cur);
            p.setEffectiveFrom(now);
            priceMapper.insert(p);
        }
        changed("PRICE_SET", "price", provider + "/" + model,
                Map.of("input", input, "output", output, "cacheRead", cr, "cacheWrite", cw));
    }

    public void removePrice(String provider, String model) {
        int updated = priceMapper.update(null, new UpdateWrapper<Price>()
                .eq("provider", provider)
                .eq("model", model)
                .isNull("effective_to")
                .set("effective_to", LocalDateTime.now()));
        if (updated == 0) {
            throw invalid("没有生效中的价格: " + provider + "/" + model);
        }
        changed("PRICE_REMOVE", "price", provider + "/" + model, Map.of());
    }

    // ==================================================================
    // 用量
    // ==================================================================

    public Map<String, Object> usage(Long appId, int days) {
        int d = days <= 0 ? 1 : days;
        LocalDateTime from = LocalDate.now().minusDays(d - 1L).atStartOfDay();
        String sql = """
                SELECT COALESCE(provider, '-') AS provider,
                       COALESCE(logical_model, '-') AS model,
                       COUNT(*) AS reqs,
                       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS errs,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(cached_tokens), 0) AS cached_tokens,
                       COALESCE(SUM(cache_creation_tokens), 0) AS cache_creation_tokens,
                       COALESCE(SUM(cost), 0) AS cost
                FROM gw_request_log
                WHERE create_time >= ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(from);
        if (appId != null) {
            sql += " AND app_id = ?";
            args.add(appId);
        }
        sql += " GROUP BY provider, logical_model ORDER BY cost DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());

        long reqs = 0;
        long errs = 0;
        long prompt = 0;
        long completion = 0;
        long cached = 0;
        long cacheWrite = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            reqs += num(r.get("reqs"));
            errs += num(r.get("errs"));
            prompt += num(r.get("prompt_tokens"));
            completion += num(r.get("completion_tokens"));
            cached += num(r.get("cached_tokens"));
            cacheWrite += num(r.get("cache_creation_tokens"));
            cost = cost.add(dec(r.get("cost")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toLocalDate().toString());
        out.put("days", d);
        out.put("rows", rows);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("requests", reqs);
        totals.put("errors", errs);
        totals.put("promptTokens", prompt);
        totals.put("completionTokens", completion);
        totals.put("cachedTokens", cached);
        totals.put("cacheCreationTokens", cacheWrite);
        totals.put("cost", cost);
        out.put("totals", totals);
        return out;
    }

    // ==================================================================
    // 广播 + 审计
    // ==================================================================

    /** 配置变更后的统一收尾：重载本地快照 -> 广播刷新 -> 同步写审计。 */
    public void changed(String eventType, String targetType, String targetId, Map<String, Object> detail) {
        configCache.reload();
        try {
            redis.convertAndSend(properties.getConfig().getRedisChannel(), "refresh")
                    .block(Duration.ofSeconds(3));
        } catch (Exception e) {
            log.warn("配置广播失败（本地已生效，其它实例靠定时兜底）: {}", e.getMessage());
        }
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (detail != null) {
                detail.forEach((k, v) -> safe.put(k, isSensitive(k) ? "<已隐藏>" : v));
            }
            auditEventMapper.insert(AuditEvent.of(eventType, "admin", targetType, targetId,
                    JsonSupport.write(safe), null));
        } catch (Exception e) {
            log.debug("审计写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 手动触发配置刷新（等价于广播一次刷新指令）。 */
    public long refreshConfig() {
        configCache.reload();
        try {
            redis.convertAndSend(properties.getConfig().getRedisChannel(), "refresh")
                    .block(Duration.ofSeconds(3));
        } catch (Exception e) {
            log.warn("配置广播失败: {}", e.getMessage());
        }
        return configCache.current().version();
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("key") || k.contains("secret") || k.contains("token") || k.contains("password");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.INVALID_REQUEST, message);
    }

    private static List<String> normalizeList(List<String> input) {
        if (input == null) {
            return null;
        }
        List<String> out = input.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        return out.isEmpty() ? null : out;
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}

package com.gateway.admin;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import com.gateway.infra.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运行时设置：Web 控制台「设置」页的后端。
 *
 * 设计：
 * <ul>
 *   <li><b>白名单</b>：只暴露 {@link #buildDefs()} 里列出的键，带类型/范围/枚举校验，
 *       避免把 Server 端口、数据源、加密主密钥这类「必须重启且敏感」的项开放出去；</li>
 *   <li><b>持久化</b>：被修改过的键写入 {@code gw_setting}，未出现的键沿用
 *       application.yml / 环境变量的默认值；</li>
 *   <li><b>热生效</b>：直接写回 {@link GatewayProperties} 单例，各调用点在请求期读取，立即生效；</li>
 *   <li><b>一致性</b>：变更由 {@link AdminService} 通过 Redis 广播，各实例收到后重新
 *       {@link #applyAll()}，做到多实例一致。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final GatewayProperties properties;
    private final Environment environment;
    private final SettingMapper settingMapper;

    public enum Type { INT, LONG, BOOL, DOUBLE, ENUM }

    /** 单条设置的定义（描述 + 读写访问器）。 */
    public record SettingDef(String key, String group, String label, String description,
                             Type type, Double min, Double max, List<String> options,
                             Function<GatewayProperties, Object> getter,
                             BiConsumer<GatewayProperties, String> setter) {
    }

    private final List<SettingDef> defs = buildDefs();
    private final Map<String, SettingDef> byKey =
            defs.stream().collect(Collectors.toMap(SettingDef::key, Function.identity()));

    // ==================================================================
    // 查询
    // ==================================================================

    public List<Map<String, Object>> list() {
        Map<String, String> overrides = overrides();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingDef d : defs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("group", d.group());
            m.put("label", d.label());
            m.put("description", d.description());
            m.put("type", d.type().name().toLowerCase());
            m.put("value", d.getter().apply(properties));
            m.put("defaultValue", defaultValue(d));
            m.put("min", d.min());
            m.put("max", d.max());
            m.put("options", d.options());
            m.put("overridden", overrides.containsKey(d.key()));
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // 修改
    // ==================================================================

    /** 批量更新：先整体校验（避免部分写入），再逐个落库并写回内存。返回被修改的键。 */
    public List<String> update(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            SettingDef def = require(e.getKey());
            normalized.put(def.key(), validateAndNormalize(def, e.getValue()));
        }
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            SettingDef def = byKey.get(e.getKey());
            upsert(def.key(), e.getValue());
            def.setter().accept(properties, e.getValue());
            log.info("运行时设置已更新: {}={}", def.key(), e.getValue());
        }
        return List.copyOf(normalized.keySet());
    }

    /** 恢复某键的默认值（删除覆盖行，回写 application.yml / 环境变量的值）。 */
    public void reset(String key) {
        SettingDef def = require(key);
        settingMapper.deleteById(key);
        String fallback = defaultValue(def);
        if (fallback != null && !fallback.isBlank()) {
            try {
                def.setter().accept(properties, validateAndNormalize(def, fallback));
            } catch (Exception e) {
                log.warn("默认值 {}={} 非法，已忽略: {}", def.key(), fallback, e.getMessage());
            }
        }
        log.info("运行时设置已重置为默认: {}", def.key());
    }

    /** 从数据库读回并应用全部覆盖（启动时、以及收到配置广播时调用）。 */
    public void applyAll() {
        for (Setting s : settingMapper.selectList(null)) {
            SettingDef def = byKey.get(s.getSettingKey());
            if (def == null) {
                continue;
            }
            try {
                def.setter().accept(properties, validateAndNormalize(def, s.getSettingValue()));
            } catch (Exception e) {
                log.warn("忽略非法设置 {}={}: {}", def.key(), s.getSettingValue(), e.getMessage());
            }
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private Map<String, String> overrides() {
        return settingMapper.selectList(null).stream()
                .collect(Collectors.toMap(Setting::getSettingKey, Setting::getSettingValue, (a, b) -> a));
    }

    private String defaultValue(SettingDef def) {
        return environment.getProperty("gateway." + def.key());
    }

    private void upsert(String key, String value) {
        Setting existing = settingMapper.selectById(key);
        if (existing == null) {
            Setting s = new Setting();
            s.setSettingKey(key);
            s.setSettingValue(value);
            settingMapper.insert(s);
        } else {
            existing.setSettingValue(value);
            settingMapper.updateById(existing);
        }
    }

    private SettingDef require(String key) {
        SettingDef def = key == null ? null : byKey.get(key.trim());
        if (def == null) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "不支持的设置项: " + key);
        }
        return def;
    }

    private String validateAndNormalize(SettingDef d, String raw) {
        if (raw == null) {
            throw invalid(d, "值不能为空");
        }
        String v = raw.trim();
        switch (d.type()) {
            case INT -> {
                long n = parseLong(d, v);
                checkRange(d, n);
                return String.valueOf((int) n);
            }
            case LONG -> {
                long n = parseLong(d, v);
                checkRange(d, n);
                return String.valueOf(n);
            }
            case DOUBLE -> {
                double n;
                try {
                    n = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw invalid(d, "必须是小数");
                }
                checkRange(d, n);
                return String.valueOf(n);
            }
            case BOOL -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    throw invalid(d, "必须是 true / false");
                }
                return String.valueOf(Boolean.parseBoolean(v));
            }
            case ENUM -> {
                if (!d.options().contains(v)) {
                    throw invalid(d, "只能是: " + String.join(" / ", d.options()));
                }
                return v;
            }
        }
        throw invalid(d, "未知类型");
    }

    private static long parseLong(SettingDef d, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw invalid(d, "必须是整数");
        }
    }

    private static void checkRange(SettingDef d, double n) {
        if (d.min() != null && n < d.min()) {
            throw invalid(d, "不能小于 " + trim(d.min()));
        }
        if (d.max() != null && n > d.max()) {
            throw invalid(d, "不能大于 " + trim(d.max()));
        }
    }

    private static String trim(Double d) {
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static GatewayException invalid(SettingDef d, String why) {
        return new GatewayException(ErrorCode.INVALID_REQUEST, "设置项 " + d.key() + " " + why);
    }

    // ==================================================================
    // 白名单定义
    // ==================================================================

    private static List<SettingDef> buildDefs() {
        List<SettingDef> list = new ArrayList<>();

        // —— 路由与重试 ——
        list.add(new SettingDef("defaults.routing-strategy", "路由与重试", "默认路由策略",
                "未显式指定 routing.strategy 时使用的策略", Type.ENUM, null, null,
                List.of("weighted", "priority", "leastconn", "hash", "cheapest"),
                p -> p.getDefaults().getRoutingStrategy(),
                (p, v) -> p.getDefaults().setRoutingStrategy(v)));
        list.add(intDef("defaults.max-retries", "路由与重试", "最大换渠道重试次数",
                "单请求在上游失败后换渠道重试的次数上限", 0, 10,
                p -> p.getDefaults().getMaxRetries(),
                (p, v) -> p.getDefaults().setMaxRetries(Integer.parseInt(v))));
        list.add(intDef("defaults.request-timeout-ms", "路由与重试", "单请求总预算 (ms)",
                "含重试与模型降级的总超时；流式只约束首字节", 1000, 600000,
                p -> p.getDefaults().getRequestTimeoutMs(),
                (p, v) -> p.getDefaults().setRequestTimeoutMs(Integer.parseInt(v))));
        list.add(intDef("defaults.retry-backoff-ms", "路由与重试", "重试退避基数 (ms)",
                "第 n 次重试等待 n × 该值（另有随机抖动）", 0, 60000,
                p -> p.getDefaults().getRetryBackoffMs(),
                (p, v) -> p.getDefaults().setRetryBackoffMs(Integer.parseInt(v))));

        // —— 渠道与熔断 ——
        list.add(intDef("defaults.channel-cooldown-seconds", "渠道与熔断", "渠道冷却时长 (s)",
                "渠道失败后暂时剔除的时长（Redis 共享，多实例一致）", 1, 3600,
                p -> p.getDefaults().getChannelCooldownSeconds(),
                (p, v) -> p.getDefaults().setChannelCooldownSeconds(Integer.parseInt(v))));
        list.add(boolDef("defaults.channel-concurrency-enabled", "渠道与熔断", "渠道并发闸门",
                "按 gw_channel.concurrency_limit 限制单渠道并发；关闭可换约 20% 吞吐",
                p -> p.getDefaults().isChannelConcurrencyEnabled(),
                (p, v) -> p.getDefaults().setChannelConcurrencyEnabled(Boolean.parseBoolean(v))));
        list.add(boolDef("defaults.channel-quota-enabled", "渠道与熔断", "渠道 RPM/TPM 闸门",
                "让 gw_channel.rpm_limit / tpm_limit 真正生效",
                p -> p.getDefaults().isChannelQuotaEnabled(),
                (p, v) -> p.getDefaults().setChannelQuotaEnabled(Boolean.parseBoolean(v))));

        // —— 限流默认值 ——
        list.add(intDef("defaults.app-rpm-limit", "限流默认值", "应用 RPM 兜底",
                "每个应用每分钟请求上限（单 Key 泄露时不至于拖垮整个应用）", 1, 10_000_000,
                p -> p.getDefaults().getAppRpmLimit(),
                (p, v) -> p.getDefaults().setAppRpmLimit(Integer.parseInt(v))));
        list.add(intDef("defaults.app-model-rpm-limit", "限流默认值", "单应用单模型 RPM",
                "防止某个模型被单个应用刷爆", 1, 10_000_000,
                p -> p.getDefaults().getAppModelRpmLimit(),
                (p, v) -> p.getDefaults().setAppModelRpmLimit(Integer.parseInt(v))));
        list.add(longDef("defaults.app-tpm-limit", "限流默认值", "应用 TPM 令牌桶容量",
                "按 token 计的应用级限流；回填速率 = 容量 / 60 每秒", 1, 2_000_000_000L,
                p -> p.getDefaults().getAppTpmLimit(),
                (p, v) -> p.getDefaults().setAppTpmLimit(Long.parseLong(v))));
        list.add(longDef("defaults.global-rpm-limit", "限流默认值", "全局 RPM 兜底",
                "整个网关实例的每分钟请求上限", 1, 2_000_000_000L,
                p -> p.getDefaults().getGlobalRpmLimit(),
                (p, v) -> p.getDefaults().setGlobalRpmLimit(Long.parseLong(v))));

        // —— 脱敏 ——
        list.add(boolDef("masking.enabled", "脱敏", "启用脱敏",
                "是否对入参做 PII 识别与占位符替换",
                p -> p.getMasking().isEnabled(),
                (p, v) -> p.getMasking().setEnabled(Boolean.parseBoolean(v))));
        list.add(boolDef("masking.restore-placeholders", "脱敏", "响应回填",
                "是否把模型回复中的占位符还原为原文",
                p -> p.getMasking().isRestorePlaceholders(),
                (p, v) -> p.getMasking().setRestorePlaceholders(Boolean.parseBoolean(v))));

        // —— 其他 ——
        list.add(intDef("admin.session-hours", "其他", "登录会话有效期 (小时)",
                "Web 控制台登录令牌的有效期", 1, 720,
                p -> p.getAdmin().getSessionHours(),
                (p, v) -> p.getAdmin().setSessionHours(Integer.parseInt(v))));

        return List.copyOf(list);
    }

    private static SettingDef intDef(String key, String group, String label, String desc,
                                     int min, int max,
                                     Function<GatewayProperties, Object> get,
                                     BiConsumer<GatewayProperties, String> set) {
        return new SettingDef(key, group, label, desc, Type.INT, (double) min, (double) max, List.of(), get, set);
    }

    private static SettingDef longDef(String key, String group, String label, String desc,
                                      long min, long max,
                                      Function<GatewayProperties, Object> get,
                                      BiConsumer<GatewayProperties, String> set) {
        return new SettingDef(key, group, label, desc, Type.LONG, (double) min, (double) max, List.of(), get, set);
    }

    private static SettingDef boolDef(String key, String group, String label, String desc,
                                      Function<GatewayProperties, Object> get,
                                      BiConsumer<GatewayProperties, String> set) {
        return new SettingDef(key, group, label, desc, Type.BOOL, null, null, List.of(), get, set);
    }
}

package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 用量（OpenAI 兼容）。
 *
 * 在原有 prompt / completion 之上，补齐 Prompt Cache 的两个规范字段：
 * <ul>
 *   <li>{@link #cachedTokens} 从缓存读取的输入 token；</li>
 *   <li>{@link #cacheCreationTokens} 写入缓存的输入 token。</li>
 * </ul>
 *
 * 各家字段名不同（OpenAI 的 {@code prompt_tokens_details.cached_tokens}、
 * Anthropic 的 {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}），
 * 未识别字段由 {@link #putRaw} 原样保存，{@link #normalize()} 再归一为上面两个字段。
 * {@code promptTokens} 始终表示**输入总量**，因此标准输入 = prompt - cached - cacheCreation。
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Usage {

    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /** 是否由网关本地估算得出（上游未返回 usage 时为 true）。 */
    @JsonProperty("estimated")
    private Boolean estimated;

    /** 缓存读 token（规范字段）。 */
    @JsonProperty("cached_tokens")
    private Integer cachedTokens;

    /** 缓存写 token（规范字段）。 */
    @JsonProperty("cache_creation_tokens")
    private Integer cacheCreationTokens;

    /** 未识别字段原样容器：透传上游字段，并用于归一化各家的缓存字段。 */
    @JsonIgnore
    private Map<String, Object> raw = new LinkedHashMap<>();

    public Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, Boolean estimated) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimated = estimated;
    }

    public static Usage of(int prompt, int completion) {
        return new Usage(prompt, completion, prompt + completion, false);
    }

    public static Usage estimated(int prompt, int completion) {
        return new Usage(prompt, completion, prompt + completion, true);
    }

    public int promptOrZero() {
        return promptTokens == null ? 0 : promptTokens;
    }

    public int completionOrZero() {
        return completionTokens == null ? 0 : completionTokens;
    }

    public int cachedOrZero() {
        return cachedTokens == null ? 0 : cachedTokens;
    }

    public int cacheCreationOrZero() {
        return cacheCreationTokens == null ? 0 : cacheCreationTokens;
    }

    /**
     * 标准（未命中缓存）输入 token。
     * 下限保护到 0：个别上游会出现 cached &gt; prompt 的脏数据，不能因此算出负成本。
     */
    public int standardInputOrZero() {
        return Math.max(0, promptOrZero() - cachedOrZero() - cacheCreationOrZero());
    }

    @JsonAnySetter
    public void putRaw(String key, Object value) {
        this.raw.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> raw() {
        return raw;
    }

    public void setRaw(Map<String, Object> map) {
        this.raw = map == null ? new LinkedHashMap<>() : map;
    }

    /**
     * 把各家字段归一为 {@link #cachedTokens} / {@link #cacheCreationTokens}。
     *
     * 只在字段为空时填充，显式设置过的值优先（适配器可以先行覆盖）。
     * 不修改 {@code promptTokens}：调用方需保证它是输入总量。
     */
    @JsonIgnore
    public Usage normalize() {
        if (cachedTokens == null) {
            Integer v = firstInt(raw.get("cached_tokens"),
                    nestedInt(raw.get("prompt_tokens_details"), "cached_tokens"),
                    raw.get("cache_read_input_tokens"),
                    raw.get("cachedContentTokenCount"));
            if (v != null) {
                cachedTokens = v;
            }
        }
        if (cacheCreationTokens == null) {
            Integer v = firstInt(raw.get("cache_creation_input_tokens"),
                    raw.get("cache_write_tokens"),
                    nestedInt(raw.get("prompt_tokens_details"), "cache_creation_tokens"));
            if (v != null) {
                cacheCreationTokens = v;
            }
        }
        if (cachedTokens != null && cachedTokens < 0) {
            cachedTokens = 0;
        }
        if (cacheCreationTokens != null && cacheCreationTokens < 0) {
            cacheCreationTokens = 0;
        }
        if (totalTokens == null) {
            totalTokens = promptOrZero() + completionOrZero();
        }
        return this;
    }

    private static Integer firstInt(Object... values) {
        for (Object v : values) {
            Integer n = asInt(v);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Integer nestedInt(Object container, String key) {
        if (container instanceof Map<?, ?> m) {
            return asInt(((Map<String, Object>) m).get(key));
        }
        return null;
    }
}

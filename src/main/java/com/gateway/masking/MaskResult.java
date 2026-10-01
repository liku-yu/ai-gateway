package com.gateway.masking;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脱敏结果与占位符映射。
 *
 * 安全约束：
 * - 映射表（占位符 -> 原文）**只存在于内存**，随请求结束即被回收；
 * - 不写日志、不入库、不进审计、不进响应；toString 也刻意不打印明文。
 */
public class MaskResult {

    private final Map<String, String> placeholderToOriginal = new HashMap<>();
    private final Map<String, AtomicInteger> counters = new HashMap<>();

    public String nextPlaceholder(String type) {
        int seq = counters.computeIfAbsent(type, t -> new AtomicInteger()).incrementAndGet();
        return "[" + type + "_" + seq + "]";
    }

    public void record(String placeholder, String original) {
        placeholderToOriginal.put(placeholder, original);
    }

    public int hitCount() {
        return placeholderToOriginal.size();
    }

    public boolean isEmpty() {
        return placeholderToOriginal.isEmpty();
    }

    /** 是否有该占位符（还原前先判断，避免误替换业务自己写的方括号文本）。 */
    public boolean contains(String placeholder) {
        return placeholderToOriginal.containsKey(placeholder);
    }

    public String originalOf(String placeholder) {
        return placeholderToOriginal.get(placeholder);
    }

    public Map<String, String> view() {
        return Map.copyOf(placeholderToOriginal);
    }

    @Override
    public String toString() {
        return "MaskResult{hits=" + placeholderToOriginal.size() + "}";
    }
}

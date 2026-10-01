package com.gateway.masking;

/** 命中区间（左闭右开）。 */
public record Span(int start, int end, String text) {
    public int length() {
        return end - start;
    }
}

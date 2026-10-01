package com.gateway.masking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于正则的识别基类：负责遍历匹配、去重与排序，子类只需给正则和可选校验。 */
public abstract class AbstractRegexStrategy implements MaskingStrategy {

    protected abstract Pattern pattern();

    /** 额外校验（如 Luhn、校验位），默认全部通过。 */
    protected boolean validate(String candidate) {
        return true;
    }

    @Override
    public List<Span> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        Matcher matcher = pattern().matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.isBlank() || !validate(candidate)) {
                continue;
            }
            spans.add(new Span(matcher.start(), matcher.end(), candidate));
        }
        spans.sort(Comparator.comparingInt(Span::start));
        return dedupe(spans);
    }

    /** 丢弃被前一个区间完全包含的重叠匹配。 */
    private List<Span> dedupe(List<Span> spans) {
        List<Span> out = new ArrayList<>(spans.size());
        int lastEnd = -1;
        for (Span s : spans) {
            if (s.start() >= lastEnd) {
                out.add(s);
                lastEnd = s.end();
            }
        }
        return out;
    }
}

package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 密钥类：sk-xxx、AKIA...、以及常见 AK/SK 赋值形态。
 * 正则 + 长度阈值，避免把普通英文单词当成密钥。
 */
@Component
public class ApiKeyMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_\\-]{16,}|AKIA[0-9A-Z]{16}|"
                    + "(?:api[_-]?key|access[_-]?key|secret|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-/+=]{16,}[\"']?)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String type() {
        return "API_KEY";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }
}

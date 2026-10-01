package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** IPv4 地址（默认不启用，按租户策略选择）。 */
@Component
public class IpMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN = Pattern.compile(
            "(?<!\\d)(?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(?!\\d)");

    @Override
    public String type() {
        return "IP";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }
}

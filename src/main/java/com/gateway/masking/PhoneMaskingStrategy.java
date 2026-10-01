package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 中国大陆手机号。 */
@Component
public class PhoneMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    @Override
    public String type() {
        return "PHONE";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }
}

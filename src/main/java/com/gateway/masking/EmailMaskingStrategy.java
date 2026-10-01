package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 邮箱地址。 */
@Component
public class EmailMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    @Override
    public String type() {
        return "EMAIL";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }
}

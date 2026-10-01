package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 18 位身份证：正则 + 校验位校验，避免把无关数字误判为身份证。 */
@Component
public class IdCardMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN =
            Pattern.compile("(?<![0-9Xx])([1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])(?![0-9Xx])");

    @Override
    public String type() {
        return "ID_CARD";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected boolean validate(String candidate) {
        return checksumValid(candidate);
    }

    /** GB 11643-1999 校验位算法。 */
    private boolean checksumValid(String id) {
        if (id.length() != 18) {
            return false;
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * weights[i];
        }
        return codes[sum % 11] == Character.toUpperCase(id.charAt(17));
    }
}

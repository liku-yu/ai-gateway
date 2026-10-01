package com.gateway.masking;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 银行卡号：16~19 位数字 + Luhn 校验，显著降低误报。 */
@Component
public class BankCardMaskingStrategy extends AbstractRegexStrategy {

    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)");

    @Override
    public String type() {
        return "BANK_CARD";
    }

    @Override
    protected Pattern pattern() {
        return PATTERN;
    }

    @Override
    protected boolean validate(String candidate) {
        return luhnValid(candidate);
    }

    private boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}

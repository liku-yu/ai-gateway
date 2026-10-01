package com.gateway.ratelimit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis 余额/预算键的统一定义。
 *
 * 放在独立类里而不是散落在各处的字符串拼接：键名一旦不一致，
 * 会出现「预扣扣在一个键、结算退在另一个键」这类极难排查的资损问题。
 */
public final class BalanceKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private BalanceKeys() {
    }

    public static String balance(Long appId) {
        return "gw:bal:app:" + appId;
    }

    public static String daily(Long appId) {
        return "gw:budget:app:" + appId + ":d:" + LocalDate.now().format(DAY);
    }

    public static String monthly(Long appId) {
        return "gw:budget:app:" + appId + ":m:" + LocalDate.now().format(MONTH);
    }
}

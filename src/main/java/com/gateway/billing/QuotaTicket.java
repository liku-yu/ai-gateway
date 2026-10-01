package com.gateway.billing;

import java.math.BigDecimal;

/**
 * 预扣票据：记录本次预扣的金额（元与分双份），结算时据此计算差额退还/补扣。
 *
 * 为什么同时保留元与分：元用于对外展示与校验，分用于实际扣减（整数运算避免浮点误差），
 * 两者在构造时一次性算好，避免后续路径各自换算产生不一致。
 */
public record QuotaTicket(String balanceKey, BigDecimal estimatedCost, long estimatedFen) {

    public static QuotaTicket of(String balanceKey, BigDecimal estimatedCost, long estimatedFen) {
        return new QuotaTicket(balanceKey, estimatedCost, estimatedFen);
    }

    /** 是否真的发生了预扣（0 元票据表示本次不涉及扣费，例如无定价的模型）。 */
    public boolean isCharged() {
        return estimatedFen > 0;
    }
}

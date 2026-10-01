package com.gateway.billing;

import com.gateway.domain.Price;
import com.gateway.protocol.Usage;
import com.gateway.ratelimit.BalanceKeys;
import com.gateway.ratelimit.SettlementStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计费服务：预扣 → 结算 → 差额退还。
 *
 * 分工说明（重要）：
 * - **预扣**由准入控制（gate.lua）完成，与限流、预算校验共享同一次 Redis 往返；
 * - **结算**由本服务完成，同样收敛为一次 Redis 往返（settle.lua）；
 * - 本服务负责纯粹的金额计算：定价 × usage、元/分换算、差额。
 *
 * 三条不变量：
 * 1. 金额全程用「分」（long）运算，杜绝浮点误差导致的资损；
 * 2. 预扣取保守上界（最高单价 × (prompt + max_tokens)），结算按上游真实 usage 多退少补；
 * 3. 结算失败不阻断业务响应，但会留痕以便对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TokenEstimator tokenEstimator;
    private final SettlementStore settlementStore;

    /** 构造预扣票据（金额计算，不涉及 IO）。 */
    public QuotaTicket newTicket(Long appId, BigDecimal estimatedCost) {
        return QuotaTicket.of(BalanceKeys.balance(appId), estimatedCost, toFen(estimatedCost));
    }

    /** 预估成本：定价 × (promptTokens + maxTokens)。用于预扣与 max_cost 校验。 */
    public BigDecimal estimateCost(Price price, int promptTokens, int maxTokens) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.costOf(promptTokens, maxTokens);
    }

    /** 元 → 分，向上取整（宁可多扣一点，也不因取整少扣）。 */
    public long toFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(HUNDRED).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public BigDecimal fromFen(long fen) {
        return BigDecimal.valueOf(fen).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    /**
     * 结算：按真实 usage 计费，退回（或补扣）与预扣的差额，同时累加日/月预算用量。
     * 整个过程一次 Redis 往返完成。
     */
    public Mono<BigDecimal> settle(QuotaTicket ticket, Usage usage, Price price, Long appId) {
        if (ticket == null) {
            return Mono.just(BigDecimal.ZERO);
        }
        BigDecimal actual = price == null ? BigDecimal.ZERO : price.cost(usage);
        long actualFen = toFen(actual);
        long deltaFen = ticket.estimatedFen() - actualFen;

        Long app = appId;
        Mono<long[]> op = app == null
                ? settlementStore.settleWithoutBudget(ticket.balanceKey(), deltaFen, actualFen, 0L)
                : settlementStore.settle(ticket.balanceKey(), app, deltaFen, actualFen, 0L);

        return op.map(r -> {
            if (deltaFen != 0) {
                log.debug("结算完成: 预扣={}分 实际={}分 差额={}分 余额={}分",
                        ticket.estimatedFen(), actualFen, deltaFen, r[0]);
            }
            return actual;
        });
    }

    public int estimatePromptTokens(com.gateway.protocol.ChatRequest request) {
        return tokenEstimator.countMessages(request.getMessages());
    }

    public int estimatePromptTokens(com.gateway.protocol.EmbeddingRequest request) {
        return tokenEstimator.countStrings(request.inputTexts());
    }
}

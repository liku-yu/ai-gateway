package com.gateway.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gateway.protocol.Usage;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** 模型定价（对应 gw_price）：带时效，历史成本可追溯。 */
@Data
@TableName("gw_price")
public class Price {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String provider;

    private String model;

    private BigDecimal inputPrice;

    private BigDecimal outputPrice;

    /** 每 1K 缓存读 token 单价（Prompt Cache 命中部分）。 */
    private BigDecimal cacheReadPrice;

    /** 每 1K 缓存写 token 单价（Prompt Cache 写入部分）。 */
    private BigDecimal cacheWritePrice;

    private String currency;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /**
     * 成本核算，含 Prompt Cache 的差异化计价。
     *
     * 口径（与 {@link Usage} 的规范字段一致）：
     * <pre>
     *   标准输入 = prompt - cached - cacheCreation      （下限 0，容忍脏数据）
     *   成本 = 标准输入*input
     *        + cached*cacheRead
     *        + cacheCreation*cacheWrite
     *        + completion*output
     * </pre>
     * 未配置缓存价时按 0 计（缓存读/写不计费），不会因为缺列而拒绝请求。
     */
    public BigDecimal cost(Usage usage) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = perThousand(usage.standardInputOrZero()).multiply(nz(inputPrice));
        BigDecimal cached = perThousand(usage.cachedOrZero()).multiply(nz(cacheReadPrice));
        BigDecimal cacheWrite = perThousand(usage.cacheCreationOrZero()).multiply(nz(cacheWritePrice));
        BigDecimal out = perThousand(usage.completionOrZero()).multiply(nz(outputPrice));
        return in.add(cached).add(cacheWrite).add(out).setScale(6, RoundingMode.HALF_UP);
    }

    /** 按 token 数估算成本，用于预扣费。预扣不假设缓存命中，故全部按标准输入价。 */
    public BigDecimal costOf(int promptTokens, int completionTokens) {
        return cost(Usage.estimated(promptTokens, completionTokens));
    }

    private static BigDecimal perThousand(int tokens) {
        return BigDecimal.valueOf(tokens).divide(THOUSAND, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

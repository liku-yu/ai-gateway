package com.gateway.filter;

/** 责任链顺序常量。数值越小越先执行，集中定义避免散落魔法数字。 */
public final class FilterOrder {

    /** 鉴权：虚拟 Key -> 租户/应用。 */
    public static final int AUTH = 10;
    /**
     * 预估本次成本（纯 CPU 计算，不做任何 IO）。
     * 必须在准入控制之前：准入要拿着这个金额去一次性地做「限流 + 扣费 + 预算」。
     */
    public static final int COST_ESTIMATE = 20;
    /**
     * 准入控制：限流 + 余额预扣 + 预算校验，合并为**一次 Redis 往返**。
     * 合并的动因见 gate.lua 注释（逐项校验的多次往返会成为吞吐天花板）。
     */
    public static final int ADMISSION = 30;
    /** 入参敏感信息脱敏。 */
    public static final int MASKING = 40;
    /** 路由选择渠道。 */
    public static final int ROUTING = 50;
    /** 熔断判定。 */
    public static final int CIRCUIT = 60;
    /** 调用上游（含重试/故障转移/降级）。 */
    public static final int UPSTREAM_CALL = 70;
    /** Token 统计 + 成本结算。 */
    public static final int BILLING = 80;
    /** 日志与审计异步落库。 */
    public static final int LOGGING = 90;

    private FilterOrder() {
    }
}

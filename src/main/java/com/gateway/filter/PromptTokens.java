package com.gateway.filter;

import com.gateway.billing.BillingService;

/**
 * prompt token 估算的唯一入口。
 *
 * 为什么需要它：预扣费（成本预估）、限流（按 token 的 TPM 维度）、以及上游没回 usage 时的
 * 兜底结算，三处都需要 prompt token 数。若各自独立调用分词器，同一份请求体就会被全量 BPE
 * 分词三次 —— 纯粹重复的 CPU 开销。这里第一次计算后写入上下文，其余环节直接复用。
 */
final class PromptTokens {

    private PromptTokens() {
    }

    /** 取（必要时计算并回填）本次请求的 prompt token 估算值。 */
    static int of(RequestContext ctx, BillingService billing) {
        Integer cached = ctx.getEstimatedPromptTokens();
        if (cached != null) {
            return cached;
        }
        int tokens;
        if (ctx.getChatRequest() != null) {
            tokens = billing.estimatePromptTokens(ctx.getChatRequest());
        } else if (ctx.getEmbeddingRequest() != null) {
            tokens = billing.estimatePromptTokens(ctx.getEmbeddingRequest());
        } else {
            tokens = 0;
        }
        ctx.setEstimatedPromptTokens(tokens);
        return tokens;
    }
}

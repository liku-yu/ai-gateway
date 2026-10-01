package com.gateway.filter;

import com.gateway.infra.ErrorCode;
import com.gateway.infra.GatewayException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 模型访问权限校验（order=15，必须在鉴权之后）。
 * 应用的 allowed_models 决定它能调用哪些逻辑模型，防止低权限应用偷用贵模型。
 */
@Component
@Order(15)
public class ModelPermissionFilter implements GatewayFilter {

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Mono<RequestContext> filter(RequestContext ctx, FilterChain next) {
        String model = ctx.getRequestedModel();
        if (!ctx.getApp().allows(model)) {
            return Mono.error(new GatewayException(ErrorCode.MODEL_NOT_ALLOWED,
                    "应用 " + ctx.getApp().getName() + " 无权访问模型: " + model, "model"));
        }
        return next.proceed(ctx);
    }
}

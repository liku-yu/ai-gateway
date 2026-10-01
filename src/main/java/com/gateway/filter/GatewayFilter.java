package com.gateway.filter;

import reactor.core.publisher.Mono;

/**
 * 责任链节点。
 *
 * 约定：
 * - filter 返回的 Mono 代表「本环节及其之后所有环节」的完成；
 * - 流式响应由末端 CallFilter 直接写入 HTTP 响应，中间过滤器只负责前置校验与后置统计；
 * - 过滤器之间不直接依赖，只通过 {@link RequestContext} 交换数据。
 */
public interface GatewayFilter {

    /** 执行顺序，见 {@link FilterOrder}。 */
    int order();

    /** 是否对本次请求生效（例如脱敏可整体关闭）。 */
    default boolean enabled(RequestContext ctx) {
        return true;
    }

    Mono<RequestContext> filter(RequestContext ctx, FilterChain next);
}

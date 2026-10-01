package com.gateway.filter;

import reactor.core.publisher.Mono;

/** 责任链接口：要么把上下文交给下一个过滤器，要么就此终止链路。 */
@FunctionalInterface
public interface FilterChain {
    Mono<RequestContext> proceed(RequestContext ctx);
}

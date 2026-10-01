package com.gateway.infra;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 上游调用专用 WebClient。
 * 要点：连接池复用、连接/读超时兜底、不聚合缓冲（流式靠下游 take/flush 控制）。
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final GatewayProperties properties;

    @Bean("upstreamWebClient")
    public WebClient upstreamWebClient() {
        GatewayProperties.Http http = properties.getHttp();

        ConnectionProvider provider = ConnectionProvider.builder("upstream")
                .maxConnections(http.getMaxConnections())
                .pendingAcquireTimeout(http.getPendingAcquireTimeout())
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofMinutes(10))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) http.getConnectTimeout().toMillis())
                .responseTimeout(http.getResponseTimeout())
                .keepAlive(true)
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(
                        http.getResponseTimeout().toSeconds(), TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}

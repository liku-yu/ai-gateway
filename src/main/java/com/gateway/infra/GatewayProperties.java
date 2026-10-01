package com.gateway.infra;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** gateway.* 配置绑定。 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String apiKeySalt = "dev-only-salt-change-me";

    private Crypto crypto = new Crypto();

    private Config config = new Config();

    private Http http = new Http();

    private Defaults defaults = new Defaults();

    private Masking masking = new Masking();

    private Logging logging = new Logging();

    private Admin admin = new Admin();

    @Data
    public static class Crypto {
        /** AES-256 主密钥（Base64，32 字节）。生产环境应由 KMS 下发，勿入库。 */
        private String masterKey;
    }

    @Data
    public static class Config {
        /** 本地缓存定时刷新间隔（兜底，正常由 Redis Pub/Sub 触发）。 */
        private Duration refreshInterval = Duration.ofSeconds(30);
        private String redisChannel = "gw:config:refresh";
    }

    @Data
    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration responseTimeout = Duration.ofSeconds(120);
        private int maxConnections = 500;
        private Duration pendingAcquireTimeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Defaults {
        private int requestTimeoutMs = 30000;
        private int maxRetries = 2;
        private int retryBackoffMs = 200;
        private int channelCooldownSeconds = 30;
        /** 缺省路由策略。 */
        private String routingStrategy = "weighted";
        /** 应用级 RPM 兜底（单 Key 泄露时不至于拖垮整个应用）。 */
        private int appRpmLimit = 3000;
        /** 单应用单模型 RPM，防止某个模型被刷爆。 */
        private int appModelRpmLimit = 1500;
        /** 应用级 TPM（按 token 计的令牌桶容量），refill 速率 = capacity/60。 */
        private long appTpmLimit = 300000;
        /** 全局 RPM 兜底。 */
        private long globalRpmLimit = 50000;
        /**
         * 是否启用渠道级并发闸门（对应 gw_channel.concurrency_limit）。
         *
         * 默认开启：它是保护上游的关键手段 —— 渠道并发上限表达的是「上游能同时承受多少路请求」，
         * 没有这道闸门时，网关侧的流量放大（重试、多实例叠加）会把上游直接打挂。
         *
         * 代价：渠道要等 Router 选出之后才知道，因此无法并入准入控制那一次 Lua，
         * 每次渠道调用会多一次 Redis 往返。实测（4 核，c=64）关闭闸门约 2000~2100 req/s，
         * 开启约 1500~1700 req/s，约 20% 吞吐差异。若渠道本身有足够的弹性
         * （如云厂商的托管 API），可关闭以换取吞吐。
         */
        private boolean channelConcurrencyEnabled = true;
        /**
         * 是否强制渠道级 RPM/TPM（对应 gw_channel.rpm_limit / tpm_limit）。
         *
         * 与并发闸门相互独立：两者都在 Router 选出渠道后、调用上游前校验，
         * 并合并进同一次 Redis Lua 往返，因此同时开启不会额外增加 RTT。
         *
         * 默认开启 —— 限流配置「写了就该生效」。只展示不执行会让运维误以为
         * 已有保护，上游被打挂时排查方向完全错位。
         */
        private boolean channelQuotaEnabled = true;
    }

    @Data
    public static class Masking {
        private boolean enabled = true;
        /** 是否在响应中把占位符还原为原文。 */
        private boolean restorePlaceholders = false;
    }

    @Data
    public static class Logging {
        private int asyncQueueCapacity = 20000;
        /** 调试正文采样率，0 表示不记录正文。 */
        private double debugBodySampleRate = 0.1;
    }

    @Data
    public static class Admin {
        /**
         * 机器令牌（可选）。留空则只允许「用户名 + 密码」登录；
         * 非空时可用于脚本/CI 的免登录调用。
         */
        private String token = "";
        /** 控制台登录用户名。 */
        private String username = "admin";
        /** 控制台登录密码（生产必须替换）。 */
        private String password = "admin123";
        /** 登录会话有效期（小时）。 */
        private int sessionHours = 12;
    }
}

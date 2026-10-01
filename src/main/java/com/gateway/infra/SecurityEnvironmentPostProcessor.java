package com.gateway.infra;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 启动期安全校验（在 Spring 环境准备阶段执行，早于数据源/Flyway/任何 Bean）。
 *
 * 为什么放在这么早：如果敏感配置缺失却仍然启动，可能的失败顺序是「先连数据库失败」，
 * 用户看到的会是连接错误而不是「配置缺失」这一真正原因。放在 EnvironmentPostProcessor
 * 阶段，能保证无论数据库是否可用，都先给出明确的安全配置提示并拒绝启动。
 *
 * 规则：
 * - 激活 {@code dev} profile（本地开发）时跳过，仅打印警告；
 * - 其它情况要求 GW_CRYPTO_MASTER_KEY / GW_API_KEY_SALT / GW_ADMIN_PASSWORD 均已注入且非默认/过弱。
 */
public class SecurityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Log log = LogFactory.getLog(SecurityEnvironmentPostProcessor.class);

    private static final String DEV_SALT = "dev-only-salt-change-me";
    private static final String DEV_ADMIN_PASSWORD = "admin123";
    private static final String DEV_MASTER_KEY = "ZGV2LW9ubHktbWFzdGVyLWtleS0zMmJ5dGVzISExMjM=";
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 8;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            log.warn("已激活 dev profile：正在使用开发默认凭据（admin/admin123、开发主密钥/盐值）。"
                    + "仅限本地开发，切勿用于生产。");
            return;
        }

        List<String> problems = new ArrayList<>();

        String master = environment.getProperty("gateway.crypto.master-key", "");
        if (master == null || master.isBlank()) {
            problems.add("GW_CRYPTO_MASTER_KEY 未设置（渠道密钥 AES-256 主密钥）");
        } else if (DEV_MASTER_KEY.equals(master)) {
            problems.add("GW_CRYPTO_MASTER_KEY 仍是开发默认值");
        } else {
            try {
                if (Base64.getDecoder().decode(master).length != 32) {
                    problems.add("GW_CRYPTO_MASTER_KEY 解码后必须是 32 字节（AES-256）");
                }
            } catch (Exception e) {
                problems.add("GW_CRYPTO_MASTER_KEY 不是合法的 Base64");
            }
        }

        String salt = environment.getProperty("gateway.api-key-salt", "");
        if (salt == null || salt.isBlank()) {
            problems.add("GW_API_KEY_SALT 未设置（虚拟 Key 哈希盐）");
        } else if (DEV_SALT.equals(salt) || salt.length() < 16) {
            problems.add("GW_API_KEY_SALT 过弱（应为随机长串，至少 16 位，且不能是开发默认值）");
        }

        String password = environment.getProperty("gateway.admin.password", "");
        if (password == null || password.isBlank()) {
            problems.add("GW_ADMIN_PASSWORD 未设置（Web 控制台登录密码）");
        } else if (DEV_ADMIN_PASSWORD.equals(password) || password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            problems.add("GW_ADMIN_PASSWORD 过弱（至少 " + MIN_ADMIN_PASSWORD_LENGTH + " 位，且不能是默认值 admin123）");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "安全检查未通过，拒绝启动。以下敏感配置需通过环境变量注入：\n  - "
                            + String.join("\n  - ", problems)
                            + "\n\n生成示例：\n"
                            + "  export GW_CRYPTO_MASTER_KEY=\"$(openssl rand -base64 32)\"\n"
                            + "  export GW_API_KEY_SALT=\"$(openssl rand -hex 32)\"\n"
                            + "  export GW_ADMIN_PASSWORD=\"$(openssl rand -base64 18)\"\n"
                            + "本地开发可直接用: --spring.profiles.active=dev");
        }
        log.info("安全检查通过：敏感配置均已注入且非默认值。");
    }
}

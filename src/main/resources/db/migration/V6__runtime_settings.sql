-- ============================================================
-- V6：运行时设置覆盖表
--
-- Web 控制台的「设置」页把一部分可热调的运行参数（重试/超时/熔断/限流默认值/脱敏等）
-- 持久化在这里，修改后立即生效并通过 Redis 广播同步到所有实例。
--
-- 只存「被显式修改过」的键；未出现的键使用 application.yml / 环境变量的默认值。
-- ============================================================

CREATE TABLE IF NOT EXISTS gw_setting (
  setting_key   VARCHAR(64)  NOT NULL COMMENT '设置键，如 defaults.max-retries',
  setting_value VARCHAR(255) NOT NULL COMMENT '设置值（统一以字符串存储）',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时设置覆盖';

-- ============================================================
-- AI 网关 初始化表结构
-- 说明：账号余额用 Redis 计数器承载，DB 只做配置/计费/审计/聚合落库
-- ============================================================

-- 供应商
CREATE TABLE IF NOT EXISTS gw_provider (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  code          VARCHAR(32)  NOT NULL COMMENT 'openai/qwen/deepseek/ollama',
  name          VARCHAR(64)  NOT NULL,
  base_url      VARCHAR(255) NULL,
  adapter_class VARCHAR(128) NULL COMMENT '适配器实现类名（策略）',
  enabled       TINYINT      NOT NULL DEFAULT 1,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上游供应商';

-- 渠道 = provider + api key + 模型映射，路由/熔断/配额的最小单元
CREATE TABLE IF NOT EXISTS gw_channel (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  provider_id       BIGINT       NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  api_key_enc       VARCHAR(512) NULL COMMENT 'AES-GCM 加密，永不回显',
  base_url          VARCHAR(255) NULL COMMENT '覆盖 provider 默认地址',
  weight            INT          NOT NULL DEFAULT 100 COMMENT '加权随机权重',
  priority          INT          NOT NULL DEFAULT 0   COMMENT '故障转移顺序，越小越优先',
  model_mapping     JSON         NULL COMMENT '{"chat-default":"qwen-max"}',
  rpm_limit         INT          NULL,
  tpm_limit         INT          NULL,
  concurrency_limit INT          NULL,
  timeout_ms        INT          NOT NULL DEFAULT 30000,
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/COOLING',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel (provider_id, name),
  KEY idx_channel_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道';

-- 逻辑模型（业务可见）
CREATE TABLE IF NOT EXISTS gw_model (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  logical_name   VARCHAR(64) NOT NULL COMMENT 'chat-default',
  type           VARCHAR(16) NOT NULL COMMENT 'CHAT/EMBEDDING/RERANK',
  description    VARCHAR(255) NULL,
  fallback_chain JSON        NULL COMMENT '["chat-cheap"]',
  enabled        TINYINT     NOT NULL DEFAULT 1,
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_logical_name (logical_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逻辑模型';

-- 租户应用（虚拟 Key 的归属，预算/脱敏策略挂在这里）
CREATE TABLE IF NOT EXISTS gw_app (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT        NOT NULL,
  name           VARCHAR(64)   NOT NULL,
  daily_budget   DECIMAL(12,4) NULL,
  monthly_budget DECIMAL(12,4) NULL,
  allowed_models JSON          NULL COMMENT 'null = 全部允许',
  masking_policy JSON          NULL,
  status         TINYINT       NOT NULL DEFAULT 1,
  create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_app_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户应用';

-- 虚拟 Key（只存 hash，不可逆）
CREATE TABLE IF NOT EXISTS gw_api_key (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  app_id     BIGINT      NOT NULL,
  key_hash   CHAR(64)    NOT NULL COMMENT 'sha256(salt + rawKey)',
  key_prefix VARCHAR(16) NOT NULL COMMENT '仅用于展示，如 sk-gw-abc',
  rpm_limit  INT         NULL,
  tpm_limit  INT         NULL,
  concurrency_limit INT  NULL,
  status     TINYINT     NOT NULL DEFAULT 1,
  expire_at  DATETIME    NULL,
  create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_key_hash (key_hash),
  KEY idx_key_app (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟 Key';

-- 定价表（带时效，历史可追溯）
CREATE TABLE IF NOT EXISTS gw_price (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  provider     VARCHAR(32)   NOT NULL,
  model        VARCHAR(64)   NOT NULL,
  input_price  DECIMAL(12,6) NOT NULL COMMENT '每 1K input tokens 单价',
  output_price DECIMAL(12,6) NOT NULL COMMENT '每 1K output tokens 单价',
  currency     CHAR(3)       NOT NULL DEFAULT 'CNY',
  effective_from DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_to   DATETIME    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_time (provider, model, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型定价';

-- 访问日志（异步写入，绝不阻塞主链路）
CREATE TABLE IF NOT EXISTS gw_request_log (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  trace_id          CHAR(32)      NOT NULL,
  request_id        VARCHAR(64)   NULL,
  tenant_id         BIGINT        NULL,
  app_id            BIGINT        NULL,
  api_key_id        BIGINT        NULL,
  logical_model     VARCHAR(64)   NULL,
  provider          VARCHAR(32)   NULL,
  channel_id        BIGINT        NULL,
  stream            TINYINT       NOT NULL DEFAULT 0,
  success           TINYINT       NOT NULL DEFAULT 0,
  status_code       INT           NULL,
  error_code        VARCHAR(64)   NULL,
  ttfb_ms           INT           NULL,
  cost_ms           INT           NULL,
  prompt_tokens     INT           NOT NULL DEFAULT 0,
  completion_tokens INT           NOT NULL DEFAULT 0,
  usage_estimated   TINYINT       NOT NULL DEFAULT 0,
  cost              DECIMAL(12,6) NOT NULL DEFAULT 0,
  currency          CHAR(3)       NOT NULL DEFAULT 'CNY',
  retry_cnt         INT           NOT NULL DEFAULT 0,
  degraded          TINYINT       NOT NULL DEFAULT 0,
  masked_cnt        INT           NOT NULL DEFAULT 0,
  client_ip         VARCHAR(64)   NULL,
  create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_trace (trace_id),
  KEY idx_tenant_time (tenant_id, create_time),
  KEY idx_app_time (app_id, create_time),
  KEY idx_channel_time (channel_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访问日志';

-- 审计事件（只追加，不可篡改）
CREATE TABLE IF NOT EXISTS gw_audit_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  event_type  VARCHAR(48)  NOT NULL COMMENT 'CONFIG_CHANGE/KEY_ROTATE/CIRCUIT_OPEN/MASK_HIT',
  actor       VARCHAR(64)  NULL,
  target_type VARCHAR(32)  NULL,
  target_id   VARCHAR(64)  NULL,
  detail      JSON         NULL,
  trace_id    CHAR(32)     NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_type_time (event_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计事件';

-- 小时级用量聚合
CREATE TABLE IF NOT EXISTS gw_usage_hourly (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  stat_hour         DATETIME      NOT NULL,
  tenant_id         BIGINT        NULL,
  app_id            BIGINT        NULL,
  provider          VARCHAR(32)   NULL,
  model             VARCHAR(64)   NULL,
  req_cnt           INT           NOT NULL DEFAULT 0,
  err_cnt           INT           NOT NULL DEFAULT 0,
  prompt_tokens     BIGINT        NOT NULL DEFAULT 0,
  completion_tokens BIGINT        NOT NULL DEFAULT 0,
  cost              DECIMAL(14,6) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stat (stat_hour, app_id, provider, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小时级用量聚合';

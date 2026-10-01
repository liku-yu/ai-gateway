-- ============================================================
-- V3：Prompt Cache 计费
--
-- 背景：主流上游都开始按「缓存读/写」差异化计价，且各家 usage 字段不统一：
--   OpenAI   : prompt_tokens 含 cached_tokens（子集），缓存写通常不计费、不单列；
--   Anthropic: input_tokens / cache_read_input_tokens / cache_creation_input_tokens 三者独立；
--   Gemini   : cachedContentTokenCount 类似「缓存读」。
--
-- 为统一，本迁移引入两个规范字段：
--   cached_tokens        从缓存读取的输入 token      -> 按 cache_read_price 结算
--   cache_creation_tokens 写入缓存的输入 token        -> 按 cache_write_price 结算
--
-- 结算口径（同时兼容 OpenAI 与 Anthropic）：
--   标准输入 = prompt_tokens - cached_tokens - cache_creation_tokens
--   成本 = 标准输入*input + cached*cache_read + cache_creation*cache_write + completion*output
-- 其中 prompt_tokens 一律存「输入总量」：
--   OpenAI   : prompt_tokens 原样；
--   Anthropic: input_tokens + cache_read + cache_creation。
-- ============================================================

ALTER TABLE gw_price
  ADD COLUMN cache_read_price  DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '每 1K 缓存读 token 单价',
  ADD COLUMN cache_write_price DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '每 1K 缓存写 token 单价';

ALTER TABLE gw_request_log
  ADD COLUMN cached_tokens         INT NOT NULL DEFAULT 0 COMMENT '缓存读 token',
  ADD COLUMN cache_creation_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存写 token';

ALTER TABLE gw_usage_hourly
  ADD COLUMN cached_tokens         BIGINT NOT NULL DEFAULT 0 COMMENT '缓存读 token',
  ADD COLUMN cache_creation_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写 token';

-- 为既有定价补一个保守占位：缓存读按输入价 1 折，缓存写按输入价 1.25 倍
-- （Anthropic 的常见比例）。真实价格请通过管理接口或 CLI 覆盖。
UPDATE gw_price SET cache_read_price = input_price * 0.1 WHERE cache_read_price = 0;
UPDATE gw_price SET cache_write_price = input_price * 1.25 WHERE cache_write_price = 0;

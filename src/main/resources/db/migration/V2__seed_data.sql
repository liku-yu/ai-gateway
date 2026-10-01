-- ============================================================
-- 种子数据
-- 注意：不写入任何真实上游 Key。渠道 api_key_enc 留空且状态为 DISABLED，
--      待通过管理 API 或环境变量注入后再启用（详见 README 的凭据注入说明）。
-- ============================================================

INSERT INTO gw_provider (code, name, base_url, adapter_class, enabled) VALUES
  ('openai',   'OpenAI',        'https://api.openai.com/v1',                             'openAiProvider',  1),
  ('qwen',     '通义千问(百炼)', 'https://dashscope.aliyuncs.com/compatible-mode/v1',      'qwenProvider',    1),
  ('deepseek', 'DeepSeek',      'https://api.deepseek.com',                              'deepSeekProvider',1),
  ('ollama',   'Ollama(自建)',   'http://127.0.0.1:11434',                               'ollamaProvider',  1)
ON DUPLICATE KEY UPDATE name = VALUES(name), base_url = VALUES(base_url);

-- 逻辑模型（业务侧只看这些名字）
INSERT INTO gw_model (logical_name, type, description, fallback_chain, enabled) VALUES
  ('chat-default',   'CHAT',      '默认对话模型',       '["chat-cheap"]', 1),
  ('chat-cheap',     'CHAT',      '廉价对话模型',       NULL,             1),
  ('embed-default',  'EMBEDDING', '默认向量化模型',     NULL,             1)
ON DUPLICATE KEY UPDATE type = VALUES(type);

-- 演示租户应用 + 虚拟 Key（Key 明文为 sk-gw-dev-demo-0001，仅本地开发用；
-- 哈希 = sha256(gateway.api-key-salt + 明文)，salt 默认 dev-only-salt-change-me）
INSERT INTO gw_app (id, tenant_id, name, daily_budget, monthly_budget, allowed_models, masking_policy, status) VALUES
  (1, 1, 'demo-app', 10.0000, 200.0000,
   '["chat-default","chat-cheap","embed-default"]',
   '{"enabled":true,"types":["PHONE","ID_CARD","EMAIL","BANK_CARD","API_KEY"]}', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO gw_api_key (id, app_id, key_hash, key_prefix, rpm_limit, tpm_limit, concurrency_limit, status) VALUES
  (1, 1, 'bcf7c87b701edcf36e8225f037b38e6f890fa3d03291f4d97513ae2100b7a89d',
   'sk-gw-dev', 600, 200000, 50, 1)
ON DUPLICATE KEY UPDATE rpm_limit = VALUES(rpm_limit);

-- 渠道（占位，未注入 Key 前一律 DISABLED，不会参与路由）
INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority, model_mapping, rpm_limit, tpm_limit, concurrency_limit, status)
SELECT p.id, 'openai-main', NULL, NULL, 100, 0,
       '{"chat-default":"gpt-4o-mini","chat-cheap":"gpt-4o-mini","embed-default":"text-embedding-3-small"}',
       500, 200000, 100, 'DISABLED'
FROM gw_provider p WHERE p.code = 'openai'
ON DUPLICATE KEY UPDATE status = 'DISABLED';

INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority, model_mapping, rpm_limit, tpm_limit, concurrency_limit, status)
SELECT p.id, 'qwen-main', NULL, NULL, 100, 1,
       '{"chat-default":"qwen-max","chat-cheap":"qwen-turbo","embed-default":"text-embedding-v3"}',
       500, 200000, 100, 'DISABLED'
FROM gw_provider p WHERE p.code = 'qwen'
ON DUPLICATE KEY UPDATE status = 'DISABLED';

INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority, model_mapping, rpm_limit, tpm_limit, concurrency_limit, status)
SELECT p.id, 'deepseek-main', NULL, NULL, 100, 2,
       '{"chat-default":"deepseek-chat","chat-cheap":"deepseek-chat"}',
       500, 200000, 100, 'DISABLED'
FROM gw_provider p WHERE p.code = 'deepseek'
ON DUPLICATE KEY UPDATE status = 'DISABLED';

INSERT INTO gw_channel (provider_id, name, api_key_enc, base_url, weight, priority, model_mapping, rpm_limit, tpm_limit, concurrency_limit, status)
SELECT p.id, 'ollama-local', NULL, NULL, 100, 3,
       '{"chat-default":"qwen2.5:7b","chat-cheap":"qwen2.5:3b","embed-default":"nomic-embed-text"}',
       60, 20000, 4, 'DISABLED'
FROM gw_provider p WHERE p.code = 'ollama'
ON DUPLICATE KEY UPDATE status = 'DISABLED';

-- 定价（演示值，单位：元 / 1K tokens）
INSERT INTO gw_price (provider, model, input_price, output_price, currency, effective_from) VALUES
  ('openai',   'gpt-4o-mini',              0.001050, 0.004200, 'CNY', '2026-01-01 00:00:00'),
  ('qwen',     'qwen-max',                 0.002400, 0.009600, 'CNY', '2026-01-01 00:00:00'),
  ('qwen',     'qwen-turbo',               0.000300, 0.000600, 'CNY', '2026-01-01 00:00:00'),
  ('deepseek', 'deepseek-chat',            0.001000, 0.002000, 'CNY', '2026-01-01 00:00:00'),
  ('ollama',   'qwen2.5:7b',               0.000000, 0.000000, 'CNY', '2026-01-01 00:00:00')
ON DUPLICATE KEY UPDATE input_price = VALUES(input_price), output_price = VALUES(output_price);

-- ============================================================
-- V4：新增内置上游适配器
--
-- 只需写入供应商行并把 adapter_class 指向适配器别名即可启用；
-- 渠道（gw_channel）仍需各自配置密钥与模型映射。
--   anthropic         -> Anthropic Messages（Claude）
--   gemini            -> Google Gemini generateContent
--   openai-responses  -> OpenAI Responses (/v1/responses)
--   azure             -> Azure OpenAI（api-key + deployment）
--   openai-compatible -> 任意 OpenAI 兼容供应商（base_url 完全由渠道决定）
-- ============================================================

INSERT INTO gw_provider (code, name, base_url, adapter_class, enabled) VALUES
  ('anthropic',         'Anthropic (Claude)',        'https://api.anthropic.com',                 'anthropic',         1),
  ('gemini',            'Google Gemini',             'https://generativelanguage.googleapis.com', 'gemini',            1),
  ('openai-responses',  'OpenAI Responses',          'https://api.openai.com/v1',                 'openai-responses',  1),
  ('azure',             'Azure OpenAI',              NULL,                                        'azure',             1),
  ('openai-compatible', 'OpenAI 兼容（自定义）',        NULL,                                        'openai-compatible', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), base_url = VALUES(base_url), adapter_class = VALUES(adapter_class);

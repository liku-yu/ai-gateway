-- ============================================================
-- V5：清空演示/种子数据，交付一个空白系统
--
-- 背景：本项目现在以 Web 控制台为主要入口，使用者希望从零开始，
--       自行添加供应商 / 渠道 / 模型 / 应用 / 密钥 / 定价。
--       因此把 V2 写入的演示数据全部清掉，只保留表结构与 UI。
--
-- 说明：V2 作为历史迁移保留不动（改它会破坏已应用实例的 Flyway 校验和）；
--       新库会先执行 V2 播种、再被本迁移清空，结果同样为空。
-- ============================================================

DELETE FROM gw_usage_hourly;
DELETE FROM gw_request_log;
DELETE FROM gw_audit_event;
DELETE FROM gw_price;
DELETE FROM gw_api_key;
DELETE FROM gw_channel;
DELETE FROM gw_model;
DELETE FROM gw_app;
DELETE FROM gw_provider;

ALTER TABLE gw_provider  AUTO_INCREMENT = 1;
ALTER TABLE gw_channel   AUTO_INCREMENT = 1;
ALTER TABLE gw_model     AUTO_INCREMENT = 1;
ALTER TABLE gw_app       AUTO_INCREMENT = 1;
ALTER TABLE gw_api_key   AUTO_INCREMENT = 1;
ALTER TABLE gw_price     AUTO_INCREMENT = 1;

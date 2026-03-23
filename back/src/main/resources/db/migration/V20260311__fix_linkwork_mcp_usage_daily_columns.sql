-- 补齐 MCP 使用量日汇总表缺失列，兼容已存在的旧表结构
-- 注意：该脚本文件名属于历史 Flyway 版本标识，线上已执行版本不可重命名。
ALTER TABLE linkwork_mcp_usage_daily
    ADD COLUMN IF NOT EXISTS req_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '请求字节数',
    ADD COLUMN IF NOT EXISTS resp_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '响应字节数';

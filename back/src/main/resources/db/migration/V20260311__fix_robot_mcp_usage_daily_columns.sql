-- 补齐 MCP 使用量日汇总表缺失列，兼容已存在的旧表结构
ALTER TABLE linkwork_mcp_usage_daily
    ADD COLUMN IF NOT EXISTS req_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '请求字节数',
    ADD COLUMN IF NOT EXISTS resp_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '响应字节数';

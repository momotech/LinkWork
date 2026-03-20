-- MCP Gateway 迁移: 新增 network_zone 字段 + 用户凭证表 + 使用量日汇总表

-- 1. linkwork_mcp_server 表新增 network_zone 字段
ALTER TABLE linkwork_mcp_server
    ADD COLUMN network_zone VARCHAR(20) NOT NULL DEFAULT 'external'
    COMMENT '网段标记: internal(服务器内网), office(办公网), external(外部互联网)'
    AFTER health_check_url;

-- 2. 用户个人 MCP 凭证表
CREATE TABLE IF NOT EXISTS linkwork_mcp_user_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID',
    mcp_server_id BIGINT NOT NULL COMMENT 'MCP Server ID (FK linkwork_mcp_server.id)',
    headers JSON COMMENT '用户个人 Headers (加密存储的 JSON)',
    url_params JSON COMMENT '用户个人 URL 参数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) DEFAULT 0,
    UNIQUE KEY uk_user_mcp (user_id, mcp_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 MCP 个人凭证配置';

-- 3. MCP 使用量日汇总表
CREATE TABLE IF NOT EXISTS linkwork_mcp_usage_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date DATE NOT NULL COMMENT '统计日期',
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID',
    mcp_name VARCHAR(128) NOT NULL COMMENT 'MCP Server 名称',
    call_count INT NOT NULL DEFAULT 0 COMMENT '调用次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date_user_mcp (date, user_id, mcp_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 使用量日汇总';

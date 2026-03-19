package com.linkwork.mapper.mcp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.mcp.McpServerRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface McpServerMapper extends BaseMapper<McpServerRecord> {
}

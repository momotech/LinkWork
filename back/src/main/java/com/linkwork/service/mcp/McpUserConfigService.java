package com.linkwork.service.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.mapper.mcp.McpUserConfigMapper;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.model.mcp.McpUserConfigRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class McpUserConfigService extends ServiceImpl<McpUserConfigMapper, McpUserConfigRecord> {

    private final McpServerService mcpServerService;
    private final McpRequestContextService contextService;

    public McpUserConfigService(McpServerService mcpServerService,
                                McpRequestContextService contextService) {
        this.mcpServerService = mcpServerService;
        this.contextService = contextService;
    }

    public McpUserConfigRecord getByUserAndMcpName(String userId, String mcpName) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(mcpName)) {
            return null;
        }
        LambdaQueryWrapper<McpServerRecord> serverWrapper = new LambdaQueryWrapper<>();
        serverWrapper.eq(McpServerRecord::getName, mcpName);
        McpServerRecord server = mcpServerService.getOne(serverWrapper, false);
        if (server == null) {
            return null;
        }
        return getByUserAndServer(userId, server.getId());
    }

    public McpUserConfigRecord getByUserAndServer(String userId, Long mcpServerId) {
        LambdaQueryWrapper<McpUserConfigRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigRecord::getUserId, userId)
            .eq(McpUserConfigRecord::getMcpServerId, mcpServerId);
        return this.getOne(wrapper, false);
    }

    public List<McpUserConfigRecord> listCurrentUser() {
        LambdaQueryWrapper<McpUserConfigRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigRecord::getUserId, contextService.currentUserId());
        return this.list(wrapper);
    }

    public McpUserConfigRecord getCurrentUserConfig(Long mcpServerId) {
        return getByUserAndServer(contextService.currentUserId(), mcpServerId);
    }

    @SuppressWarnings("unchecked")
    public McpUserConfigRecord saveOrUpdateCurrentUser(Long mcpServerId, Map<String, Object> request) {
        String userId = contextService.currentUserId();
        McpUserConfigRecord record = getByUserAndServer(userId, mcpServerId);
        if (record == null) {
            record = new McpUserConfigRecord();
            record.setUserId(userId);
            record.setMcpServerId(mcpServerId);
        }
        if (request.containsKey("headers")) {
            record.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("urlParams")) {
            record.setUrlParams((Map<String, String>) request.get("urlParams"));
        }
        this.saveOrUpdate(record);
        return record;
    }

    public void deleteCurrentUserConfig(Long mcpServerId) {
        LambdaQueryWrapper<McpUserConfigRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigRecord::getUserId, contextService.currentUserId())
            .eq(McpUserConfigRecord::getMcpServerId, mcpServerId);
        this.remove(wrapper);
    }
}

package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.mapper.McpUserConfigMapper;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.McpUserConfigEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpUserConfigService extends ServiceImpl<McpUserConfigMapper, McpUserConfigEntity> {

    private final McpServerService mcpServerService;

    public McpUserConfigEntity getByUserAndMcpName(String userId, String mcpName) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(mcpName)) {
            return null;
        }

        LambdaQueryWrapper<McpServerEntity> serverWrapper = new LambdaQueryWrapper<>();
        serverWrapper.eq(McpServerEntity::getName, mcpName);
        McpServerEntity server = mcpServerService.getOne(serverWrapper, false);
        if (server == null) {
            return null;
        }

        return getByUserAndServer(userId, server.getId());
    }

    public McpUserConfigEntity getByUserAndServer(String userId, Long mcpServerId) {
        LambdaQueryWrapper<McpUserConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigEntity::getUserId, userId)
                .eq(McpUserConfigEntity::getMcpServerId, mcpServerId);
        return this.getOne(wrapper, false);
    }

    public List<McpUserConfigEntity> listByUser(String userId) {
        LambdaQueryWrapper<McpUserConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigEntity::getUserId, userId);
        return this.list(wrapper);
    }

    @SuppressWarnings("unchecked")
    public McpUserConfigEntity saveOrUpdate(String userId, Long mcpServerId, Map<String, Object> request) {
        McpUserConfigEntity entity = getByUserAndServer(userId, mcpServerId);
        if (entity == null) {
            entity = new McpUserConfigEntity();
            entity.setUserId(userId);
            entity.setMcpServerId(mcpServerId);
        }

        if (request.containsKey("headers")) {
            entity.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("urlParams")) {
            entity.setUrlParams((Map<String, String>) request.get("urlParams"));
        }

        this.saveOrUpdate(entity);
        log.info("Saved MCP user config for userId={}, mcpServerId={}", userId, mcpServerId);
        return entity;
    }

    public void deleteConfig(String userId, Long mcpServerId) {
        LambdaQueryWrapper<McpUserConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUserConfigEntity::getUserId, userId)
                .eq(McpUserConfigEntity::getMcpServerId, mcpServerId);
        this.remove(wrapper);
        log.info("Deleted MCP user config for userId={}, mcpServerId={}", userId, mcpServerId);
    }
}

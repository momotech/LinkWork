package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.mapper.BuildRecordMapper;
import com.linkwork.model.entity.BuildRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 构建记录 Service
 * 
 * 管理镜像构建记录的完整生命周期
 */
@Slf4j
@Service
public class BuildRecordService extends ServiceImpl<BuildRecordMapper, BuildRecordEntity> {

    /**
     * 创建构建记录
     * 
     * @param buildNo 构建编号（由前端生成）
     * @param roleId 岗位 ID
     * @param roleName 岗位名称
     * @param configSnapshot 构建配置快照
     * @param creatorId 创建者 ID
     * @param creatorName 创建者名称
     * @return 构建记录实体
     */
    public BuildRecordEntity createBuildRecord(String buildNo, Long roleId, String roleName,
                                                Map<String, Object> configSnapshot,
                                                String creatorId, String creatorName) {
        BuildRecordEntity entity = new BuildRecordEntity();
        entity.setBuildNo(buildNo);
        entity.setRoleId(roleId);
        entity.setRoleName(roleName);
        entity.setStatus(BuildRecordEntity.STATUS_PENDING);
        entity.setConfigSnapshot(configSnapshot);
        entity.setCreatorId(creatorId);
        entity.setCreatorName(creatorName);
        
        this.save(entity);
        log.info("Created build record: {} for role {} by user {}", buildNo, roleId, creatorId);
        return entity;
    }

    /**
     * 更新构建状态为 BUILDING
     */
    public void markBuilding(String buildNo) {
        updateStatus(buildNo, BuildRecordEntity.STATUS_BUILDING, null, null, null);
    }

    /**
     * 更新构建状态为 SUCCESS
     */
    public void markSuccess(String buildNo, String imageTag, Long durationMs) {
        updateStatus(buildNo, BuildRecordEntity.STATUS_SUCCESS, imageTag, durationMs, null);
    }

    /**
     * 更新构建状态为 FAILED
     */
    public void markFailed(String buildNo, String errorMessage, Long durationMs) {
        updateStatus(buildNo, BuildRecordEntity.STATUS_FAILED, null, durationMs, errorMessage);
    }

    /**
     * 更新构建状态为 CANCELLED
     */
    public void markCancelled(String buildNo) {
        updateStatus(buildNo, BuildRecordEntity.STATUS_CANCELLED, null, null, "Build cancelled by user");
    }

    /**
     * 更新构建日志 URL
     */
    public void updateLogUrl(String buildNo, String logUrl) {
        BuildRecordEntity entity = getByBuildNo(buildNo);
        if (entity == null) {
            log.warn("Build record not found for log URL update: {}", buildNo);
            return;
        }
        entity.setLogUrl(logUrl);
        this.updateById(entity);
        log.info("Updated build record {} log URL", buildNo);
    }

    /**
     * 更新构建记录状态
     */
    public void updateStatus(String buildNo, String status, String imageTag, 
                             Long durationMs, String errorMessage) {
        BuildRecordEntity entity = getByBuildNo(buildNo);
        if (entity == null) {
            log.warn("Build record not found: {}", buildNo);
            return;
        }
        
        entity.setStatus(status);
        if (imageTag != null) {
            entity.setImageTag(imageTag);
        }
        if (durationMs != null) {
            entity.setDurationMs(durationMs);
        }
        if (errorMessage != null) {
            entity.setErrorMessage(errorMessage);
        }
        
        this.updateById(entity);
        log.info("Updated build record {} status to {}", buildNo, status);
    }

    /**
     * 获取岗位最新构建记录
     */
    public BuildRecordEntity getLatestByRoleId(Long roleId) {
        LambdaQueryWrapper<BuildRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BuildRecordEntity::getRoleId, roleId);
        wrapper.orderByDesc(BuildRecordEntity::getCreatedAt);
        wrapper.last("LIMIT 1");
        return this.getOne(wrapper, false);
    }

    /**
     * 根据构建编号获取记录
     */
    public BuildRecordEntity getByBuildNo(String buildNo) {
        LambdaQueryWrapper<BuildRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BuildRecordEntity::getBuildNo, buildNo);
        return this.getOne(wrapper);
    }

    /**
     * 获取岗位的构建历史
     */
    public Map<String, Object> listByRoleId(Long roleId, int page, int pageSize) {
        Page<BuildRecordEntity> pageObj = new Page<>(page, pageSize);
        
        LambdaQueryWrapper<BuildRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BuildRecordEntity::getRoleId, roleId);
        wrapper.orderByDesc(BuildRecordEntity::getCreatedAt);
        
        Page<BuildRecordEntity> result = this.page(pageObj, wrapper);
        
        List<Map<String, Object>> items = result.getRecords().stream()
                .map(this::toResponseMap)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
                "page", result.getCurrent(),
                "pageSize", result.getSize(),
                "total", result.getTotal(),
                "totalPages", result.getPages()
        ));
        return response;
    }

    /**
     * 获取最近的构建记录（全局）
     */
    public Map<String, Object> listRecent(int page, int pageSize, String status) {
        Page<BuildRecordEntity> pageObj = new Page<>(page, pageSize);
        
        LambdaQueryWrapper<BuildRecordEntity> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(BuildRecordEntity::getStatus, status);
        }
        wrapper.orderByDesc(BuildRecordEntity::getCreatedAt);
        
        Page<BuildRecordEntity> result = this.page(pageObj, wrapper);
        
        List<Map<String, Object>> items = result.getRecords().stream()
                .map(this::toResponseMap)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
                "page", result.getCurrent(),
                "pageSize", result.getSize(),
                "total", result.getTotal(),
                "totalPages", result.getPages()
        ));
        return response;
    }

    /**
     * 转换为响应 Map
     */
    private Map<String, Object> toResponseMap(BuildRecordEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("buildNo", entity.getBuildNo());
        map.put("roleId", entity.getRoleId() != null ? entity.getRoleId().toString() : null);
        map.put("roleName", entity.getRoleName());
        map.put("status", entity.getStatus());
        map.put("imageTag", entity.getImageTag());
        map.put("durationMs", entity.getDurationMs());
        map.put("errorMessage", entity.getErrorMessage());
        map.put("configSnapshot", entity.getConfigSnapshot());
        map.put("creatorId", entity.getCreatorId());
        map.put("creatorName", entity.getCreatorName());
        map.put("logUrl", entity.getLogUrl());
        map.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        map.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return map;
    }
}

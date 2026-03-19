package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.mapper.BuildRecordMapper;
import com.linkwork.model.entity.LinkworkBuildRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BuildRecordService extends ServiceImpl<BuildRecordMapper, LinkworkBuildRecord> {

    public LinkworkBuildRecord createBuildRecord(String buildNo, Long roleId, String roleName,
                                                  Map<String, Object> configSnapshot,
                                                  String creatorId, String creatorName) {
        LinkworkBuildRecord entity = new LinkworkBuildRecord();
        entity.setBuildNo(buildNo);
        entity.setRoleId(roleId);
        entity.setRoleName(roleName);
        entity.setStatus(LinkworkBuildRecord.STATUS_PENDING);
        entity.setConfigSnapshot(configSnapshot);
        entity.setCreatorId(creatorId);
        entity.setCreatorName(creatorName);
        this.save(entity);
        log.info("Created build record: {} for role {} by user {}", buildNo, roleId, creatorId);
        return entity;
    }

    public void markBuilding(String buildNo) {
        updateStatus(buildNo, LinkworkBuildRecord.STATUS_BUILDING, null, null, null);
    }

    public void markSuccess(String buildNo, String imageTag, Long durationMs) {
        updateStatus(buildNo, LinkworkBuildRecord.STATUS_SUCCESS, imageTag, durationMs, null);
    }

    public void markFailed(String buildNo, String errorMessage, Long durationMs) {
        updateStatus(buildNo, LinkworkBuildRecord.STATUS_FAILED, null, durationMs, errorMessage);
    }

    public void markCancelled(String buildNo) {
        updateStatus(buildNo, LinkworkBuildRecord.STATUS_CANCELLED, null, null, "Build cancelled by user");
    }

    public void updateLogUrl(String buildNo, String logUrl) {
        LinkworkBuildRecord entity = getByBuildNo(buildNo);
        if (entity == null) return;
        entity.setLogUrl(logUrl);
        this.updateById(entity);
    }

    public void updateStatus(String buildNo, String status, String imageTag,
                             Long durationMs, String errorMessage) {
        LinkworkBuildRecord entity = getByBuildNo(buildNo);
        if (entity == null) {
            log.warn("Build record not found: {}", buildNo);
            return;
        }
        entity.setStatus(status);
        if (imageTag != null) entity.setImageTag(imageTag);
        if (durationMs != null) entity.setDurationMs(durationMs);
        if (errorMessage != null) entity.setErrorMessage(errorMessage);
        this.updateById(entity);
        log.info("Updated build record {} status to {}", buildNo, status);
    }

    public LinkworkBuildRecord getLatestByRoleId(Long roleId) {
        LambdaQueryWrapper<LinkworkBuildRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkBuildRecord::getRoleId, roleId);
        wrapper.orderByDesc(LinkworkBuildRecord::getCreatedAt);
        wrapper.last("LIMIT 1");
        return this.getOne(wrapper, false);
    }

    public LinkworkBuildRecord getByBuildNo(String buildNo) {
        LambdaQueryWrapper<LinkworkBuildRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkBuildRecord::getBuildNo, buildNo);
        return this.getOne(wrapper);
    }

    public Map<String, Object> listByRoleId(Long roleId, int page, int pageSize) {
        Page<LinkworkBuildRecord> pageObj = new Page<>(page, pageSize);
        LambdaQueryWrapper<LinkworkBuildRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkBuildRecord::getRoleId, roleId);
        wrapper.orderByDesc(LinkworkBuildRecord::getCreatedAt);
        Page<LinkworkBuildRecord> result = this.page(pageObj, wrapper);
        return toPageResponse(result);
    }

    public Map<String, Object> listRecent(int page, int pageSize, String status) {
        Page<LinkworkBuildRecord> pageObj = new Page<>(page, pageSize);
        LambdaQueryWrapper<LinkworkBuildRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(LinkworkBuildRecord::getStatus, status);
        }
        wrapper.orderByDesc(LinkworkBuildRecord::getCreatedAt);
        Page<LinkworkBuildRecord> result = this.page(pageObj, wrapper);
        return toPageResponse(result);
    }

    private Map<String, Object> toPageResponse(Page<LinkworkBuildRecord> result) {
        List<Map<String, Object>> items = result.getRecords().stream()
                .map(this::toResponseMap).collect(Collectors.toList());
        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages()));
        return response;
    }

    private Map<String, Object> toResponseMap(LinkworkBuildRecord entity) {
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
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }
}

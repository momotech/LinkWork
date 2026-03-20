package com.linkwork.controller;

import com.linkwork.model.entity.BuildRecordEntity;
import com.linkwork.service.BuildRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 构建记录 Controller
 * 
 * 提供构建历史查询接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/build-records")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BuildRecordController {

    private final BuildRecordService buildRecordService;

    /**
     * 获取构建记录列表
     * 
     * @param roleId 岗位 ID（可选，不传则查询所有）
     * @param status 状态过滤（可选）
     * @param page 页码
     * @param pageSize 每页数量
     */
    @GetMapping
    public Map<String, Object> listBuildRecords(
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", LocalDateTime.now().toString());
        
        Map<String, Object> data;
        if (roleId != null) {
            data = buildRecordService.listByRoleId(roleId, page, pageSize);
        } else {
            data = buildRecordService.listRecent(page, pageSize, status);
        }
        response.put("data", data);
        
        return response;
    }

    /**
     * 根据构建编号获取详情
     */
    @GetMapping("/{buildNo}")
    public Map<String, Object> getBuildRecord(@PathVariable String buildNo) {
        Map<String, Object> response = new HashMap<>();
        
        BuildRecordEntity entity = buildRecordService.getByBuildNo(buildNo);
        if (entity == null) {
            response.put("code", 404);
            response.put("msg", "Build record not found: " + buildNo);
            response.put("timestamp", LocalDateTime.now().toString());
            return response;
        }
        
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", LocalDateTime.now().toString());
        
        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId().toString());
        data.put("buildNo", entity.getBuildNo());
        data.put("roleId", entity.getRoleId() != null ? entity.getRoleId().toString() : null);
        data.put("roleName", entity.getRoleName());
        data.put("status", entity.getStatus());
        data.put("imageTag", entity.getImageTag());
        data.put("durationMs", entity.getDurationMs());
        data.put("errorMessage", entity.getErrorMessage());
        data.put("configSnapshot", entity.getConfigSnapshot());
        data.put("creatorId", entity.getCreatorId());
        data.put("creatorName", entity.getCreatorName());
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        
        response.put("data", data);
        return response;
    }

    /**
     * 获取岗位最新一次构建记录
     */
    @GetMapping("/role/{roleId}/latest")
    public Map<String, Object> getLatestBuildRecord(@PathVariable Long roleId) {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, Object> records = buildRecordService.listByRoleId(roleId, 1, 1);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) records.get("items");
        
        if (items == null || items.isEmpty()) {
            response.put("code", 0);
            response.put("msg", "success");
            response.put("data", null);
            response.put("timestamp", LocalDateTime.now().toString());
            return response;
        }
        
        response.put("code", 0);
        response.put("msg", "success");
        response.put("data", items.get(0));
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }
}

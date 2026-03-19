package com.linkwork.controller.v1;

import com.linkwork.model.entity.LinkworkBuildRecord;
import com.linkwork.service.BuildRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/build-records")
@RequiredArgsConstructor
public class BuildRecordController {

    private final BuildRecordService buildRecordService;

    @GetMapping
    public Map<String, Object> listBuildRecords(
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> data;
        if (roleId != null) {
            data = buildRecordService.listByRoleId(roleId, page, pageSize);
        } else {
            data = buildRecordService.listRecent(page, pageSize, status);
        }
        return Map.of("code", 0, "data", data);
    }

    @GetMapping("/{buildNo}")
    public Map<String, Object> getBuildRecord(@PathVariable String buildNo) {
        LinkworkBuildRecord entity = buildRecordService.getByBuildNo(buildNo);
        if (entity == null) {
            return Map.of("code", 404, "msg", "Build record not found: " + buildNo);
        }
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
        data.put("createdAt", entity.getCreatedAt());
        data.put("updatedAt", entity.getUpdatedAt());
        return Map.of("code", 0, "data", data);
    }

    @GetMapping("/role/{roleId}/latest")
    public Map<String, Object> getLatestBuildRecord(@PathVariable Long roleId) {
        LinkworkBuildRecord entity = buildRecordService.getLatestByRoleId(roleId);
        if (entity == null) {
            return Map.of("code", 0, "data", Map.of());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId().toString());
        data.put("buildNo", entity.getBuildNo());
        data.put("roleId", entity.getRoleId() != null ? entity.getRoleId().toString() : null);
        data.put("roleName", entity.getRoleName());
        data.put("status", entity.getStatus());
        data.put("imageTag", entity.getImageTag());
        data.put("durationMs", entity.getDurationMs());
        data.put("createdAt", entity.getCreatedAt());
        return Map.of("code", 0, "data", data);
    }
}

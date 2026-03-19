package com.linkwork.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.mapper.LinkworkTaskMapper;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/build-records")
@RequiredArgsConstructor
public class BuildRecordController {

    private final LinkworkTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Map<String, Object> listBuildRecords(
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        int currentPage = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));

        LambdaQueryWrapper<LinkworkTask> wrapper = new LambdaQueryWrapper<>();
        if (roleId != null) {
            wrapper.eq(LinkworkTask::getWorkstationId, roleId);
        }
        TaskStatus taskStatus = parseTaskStatus(status);
        if (taskStatus != null) {
            wrapper.eq(LinkworkTask::getStatus, taskStatus);
        }
        wrapper.orderByDesc(LinkworkTask::getCreatedAt);

        Page<LinkworkTask> result = taskMapper.selectPage(new Page<>(currentPage, size), wrapper);
        List<Map<String, Object>> items = result.getRecords().stream().map(this::toBuildRecord).toList();

        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("total", result.getTotal());
        data.put("totalPages", result.getPages());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", LocalDateTime.now());
        response.put("data", data);
        return response;
    }

    @GetMapping("/{buildNo}")
    public Map<String, Object> getBuildRecord(@PathVariable String buildNo) {
        LinkworkTask task = findByTaskNo(buildNo);

        Map<String, Object> response = new HashMap<>();
        if (task == null) {
            response.put("code", 404);
            response.put("msg", "Build record not found: " + buildNo);
            response.put("timestamp", LocalDateTime.now());
            return response;
        }

        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", LocalDateTime.now());
        response.put("data", toBuildRecord(task));
        return response;
    }

    @GetMapping("/role/{roleId}/latest")
    public Map<String, Object> getLatestBuildRecord(@PathVariable Long roleId) {
        LambdaQueryWrapper<LinkworkTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkTask::getWorkstationId, roleId)
                .orderByDesc(LinkworkTask::getCreatedAt)
                .last("limit 1");
        LinkworkTask task = taskMapper.selectOne(wrapper);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", LocalDateTime.now());
        response.put("data", task == null ? null : toBuildRecord(task));
        return response;
    }

    private LinkworkTask findByTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) return null;
        LambdaQueryWrapper<LinkworkTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkTask::getTaskNo, taskNo);
        return taskMapper.selectOne(wrapper);
    }

    private Map<String, Object> toBuildRecord(LinkworkTask task) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", task.getId() == null ? null : String.valueOf(task.getId()));
        data.put("buildNo", task.getTaskNo());
        data.put("roleId", task.getWorkstationId() == null ? null : String.valueOf(task.getWorkstationId()));
        data.put("roleName", task.getWorkstationName());
        data.put("status", task.getStatus() == null ? null : task.getStatus().getCode());
        data.put("imageTag", task.getImage());
        data.put("durationMs", task.getDurationMs());
        data.put("errorMessage", extractErrorMessage(task.getReportJson()));
        data.put("configSnapshot", task.getConfigJson());
        data.put("creatorId", task.getCreatorId());
        data.put("creatorName", task.getCreatorName());
        data.put("createdAt", task.getCreatedAt());
        data.put("updatedAt", task.getUpdatedAt());
        return data;
    }

    private String extractErrorMessage(String reportJson) {
        if (!StringUtils.hasText(reportJson)) return null;
        try {
            Map<String, Object> report = objectMapper.readValue(reportJson, new TypeReference<Map<String, Object>>() {});
            Object message = report.get("errorMessage");
            if (message == null) message = report.get("error");
            return message == null ? null : String.valueOf(message);
        } catch (Exception ex) {
            log.debug("Failed to parse reportJson for build-record: {}", ex.getMessage());
            return null;
        }
    }

    private TaskStatus parseTaskStatus(String status) {
        if (!StringUtils.hasText(status)) return null;
        String normalized = status.trim();
        for (TaskStatus value : TaskStatus.values()) {
            if (value.name().equalsIgnoreCase(normalized) || value.getCode().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}

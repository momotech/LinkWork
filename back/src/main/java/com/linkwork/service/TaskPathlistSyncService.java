package com.linkwork.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理 TASK_OUTPUT_PATHLIST_READY 事件：
 * - 统一构建前端可展示 artifacts
 * - 同步写入 USER/WORKSTATION 文件索引
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPathlistSyncService {

    private final TaskOutputWorkspaceSyncService taskOutputWorkspaceSyncService;
    private final TaskService taskService;
    private final NfsStorageService nfsStorageService;
    private final Map<String, String> taskWorkstationCache = new ConcurrentHashMap<>();

    public void onEvent(String taskNo, Map<String, Object> eventData) {
        try {
            PathlistContext context = buildContext(taskNo, eventData);
            if (!context.validForSync()) {
                return;
            }
            long upsertCount = context.artifacts().stream()
                    .filter(item -> !"deleted".equalsIgnoreCase(asText(item.get("action"))))
                    .count();
            long deletedCount = context.artifacts().size() - upsertCount;
            taskOutputWorkspaceSyncService.syncTaskPathListArtifacts(
                    context.taskNo(),
                    context.userId(),
                    context.workstationId(),
                    context.artifacts()
            );
            log.info("TASK_OUTPUT_PATHLIST_READY synced: taskNo={}, userId={}, workstationId={}, path_count={}, upsert_count={}, deleted_count={}, skipped_count={}",
                    context.taskNo(),
                    context.userId(),
                    context.workstationId(),
                    context.artifacts().size(),
                    upsertCount,
                    deletedCount,
                    context.skippedCount());
        } catch (Exception e) {
            log.error("sync TASK_OUTPUT_PATHLIST_READY failed: taskNo={}, err={}", taskNo, e.getMessage(), e);
        }
    }

    public void enrichEventForDisplay(String taskNo, Map<String, Object> eventData) {
        try {
            PathlistContext context = buildContext(taskNo, eventData);
            if (!context.validForDisplay()) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) eventData.get("data");
            data.put("artifacts", context.artifacts());
            data.put("count", context.artifacts().size());
            data.put("artifacts_pending", Boolean.FALSE);
        } catch (Exception e) {
            log.warn("enrich TASK_OUTPUT_PATHLIST_READY failed: taskNo={}, err={}", taskNo, e.getMessage(), e);
        }
    }

    private PathlistContext buildContext(String taskNo, Map<String, Object> eventData) {
        String eventType = asText(eventData.get("event_type"));
        if (!"TASK_OUTPUT_PATHLIST_READY".equals(eventType)) {
            return PathlistContext.empty();
        }

        Object dataObj = eventData.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            return PathlistContext.empty();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) dataMap;
        Object rawPathList = data.get("path_list");
        if (!(rawPathList instanceof List<?> pathList) || pathList.isEmpty()) {
            return PathlistContext.empty();
        }

        String safeTaskNo = firstNonBlank(taskNo, asText(eventData.get("task_no")), asText(eventData.get("task_id")));
        String userId = firstNonBlank(
                asText(data.get("user_id")),
                asText(eventData.get("user_id"))
        );
        String workstationId = resolveWorkstationId(safeTaskNo);

        List<Map<String, Object>> artifacts = new ArrayList<>();
        int skippedCount = 0;
        for (Object itemObj : pathList) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) {
                skippedCount++;
                continue;
            }
            String relativePath = asText(itemMap.get("relative_path"));
            if (!StringUtils.hasText(relativePath)) {
                relativePath = normalizeWorkspaceRelativePath(asText(itemMap.get("path")));
            }
            if (!StringUtils.hasText(relativePath)) {
                skippedCount++;
                continue;
            }

            String action = asText(itemMap.get("action"));
            if (!StringUtils.hasText(action)) {
                action = "upsert";
            }

            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("name", extractFileName(relativePath));
            artifact.put("relative_path", relativePath);
            artifact.put("path", "/workspace/" + relativePath);
            artifact.put("category", asText(itemMap.get("category")));
            artifact.put("action", action);
            artifact.put("size", itemMap.get("size"));

            String objectName = resolveObjectName(relativePath, safeTaskNo, userId, workstationId);
            if (StringUtils.hasText(objectName)) {
                artifact.put("object_name", objectName);
                if (nfsStorageService.isConfigured() && !"deleted".equalsIgnoreCase(action)) {
                    artifact.put("download_url", nfsStorageService.buildTaskOutputDownloadUrl(objectName));
                    artifact.put("nfs_path", nfsStorageService.getAbsolutePath(objectName).toString());
                }
            }
            artifacts.add(artifact);
        }

        return new PathlistContext(safeTaskNo, userId, workstationId, artifacts, skippedCount);
    }

    private String resolveWorkstationId(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            return null;
        }
        String cached = taskWorkstationCache.get(taskNo);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        try {
            Long roleId = taskService.getTaskByNo(taskNo).getRoleId();
            if (roleId == null) {
                return null;
            }
            String workstationId = String.valueOf(roleId);
            taskWorkstationCache.put(taskNo, workstationId);
            return workstationId;
        } catch (Exception e) {
            log.debug("resolve workstation id failed: taskNo={}, err={}", taskNo, e.getMessage());
            return null;
        }
    }

    private String resolveObjectName(String relativePath, String taskNo, String userId, String workstationId) {
        String normalized = normalizeWorkspaceRelativePath(relativePath);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }

        if (normalized.startsWith("logs/")) {
            String subPath = normalized.substring("logs/".length());
            if (!StringUtils.hasText(subPath)
                    || !StringUtils.hasText(taskNo)
                    || !StringUtils.hasText(userId)
                    || !StringUtils.hasText(workstationId)) {
                return "";
            }
            return String.format("system/%s/logs/%s/%s/%s", workstationId, userId, taskNo, subPath);
        }

        if (normalized.startsWith("user/")) {
            String subPath = normalized.substring("user/".length());
            if (!StringUtils.hasText(subPath) || !StringUtils.hasText(userId)) {
                return "";
            }
            return String.format("user-files/%s/%s", userId, subPath);
        }

        if (normalized.startsWith("workstation/")) {
            String subPath = normalized.substring("workstation/".length());
            if (!StringUtils.hasText(subPath)
                    || !StringUtils.hasText(userId)
                    || !StringUtils.hasText(workstationId)) {
                return "";
            }
            return String.format("workstation/%s/%s/%s", workstationId, userId, subPath);
        }

        return "";
    }

    private String extractFileName(String relativePath) {
        String normalized = normalizeWorkspaceRelativePath(relativePath);
        int index = normalized.lastIndexOf('/');
        if (index < 0 || index >= normalized.length() - 1) {
            return normalized;
        }
        return normalized.substring(index + 1);
    }

    private String normalizeWorkspaceRelativePath(String path) {
        String normalized = asText(path).replace('\\', '/');
        if (normalized.startsWith("/workspace/")) {
            normalized = normalized.substring("/workspace/".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String asText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record PathlistContext(
            String taskNo,
            String userId,
            String workstationId,
            List<Map<String, Object>> artifacts,
            int skippedCount
    ) {
        private static PathlistContext empty() {
            return new PathlistContext(null, null, null, List.of(), 0);
        }

        private boolean validForDisplay() {
            return artifacts != null && !artifacts.isEmpty();
        }

        private boolean validForSync() {
            return validForDisplay()
                    && StringUtils.hasText(taskNo)
                    && StringUtils.hasText(userId)
                    && StringUtils.hasText(workstationId);
        }
    }
}

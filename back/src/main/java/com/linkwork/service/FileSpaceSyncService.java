package com.linkwork.service;

import com.linkwork.model.dto.FileSpaceSyncRequest;
import com.linkwork.model.dto.FileSpaceSyncResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileSpaceSyncService {

    private static final String SPACE_TYPE_USER = "USER";
    private static final String SPACE_TYPE_WORKSTATION = "WORKSTATION";

    private final NfsStorageService nfsStorageService;
    private final TaskOutputWorkspaceSyncService taskOutputWorkspaceSyncService;

    public FileSpaceSyncResponse syncSpace(String userId, FileSpaceSyncRequest request) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (!nfsStorageService.isConfigured()) {
            throw new IllegalStateException("NFS storage is not configured");
        }

        String spaceType = normalizeSpaceType(request.getSpaceType());
        String workstationId = normalizeWorkstationId(spaceType, request.getWorkstationId());
        List<SyncScope> scopes = resolveScopes(spaceType, workstationId, userId);
        int scannedCount = 0;
        int skippedCount = 0;
        Map<String, Map<String, Object>> artifactsByObjectName = new LinkedHashMap<>();

        for (SyncScope scope : scopes) {
            List<String> objectNames = nfsStorageService.listObjects(scope.prefix());
            scannedCount += objectNames.size();
            for (String objectName : objectNames) {
                String normalizedObjectName = normalizePath(objectName);
                String relativePath = resolveRelativePath(scope, normalizedObjectName);
                if (!StringUtils.hasText(relativePath) || shouldSkipRelativePath(relativePath)) {
                    skippedCount++;
                    continue;
                }
                if (artifactsByObjectName.containsKey(normalizedObjectName)) {
                    skippedCount++;
                    continue;
                }
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("relative_path", relativePath);
                artifact.put("object_name", normalizedObjectName);
                artifact.put("action", "upsert");
                artifact.put("size", resolveFileSize(normalizedObjectName));
                artifactsByObjectName.put(normalizedObjectName, artifact);
            }
        }

        List<Map<String, Object>> artifacts = new ArrayList<>(artifactsByObjectName.values());
        if (!artifacts.isEmpty()) {
            taskOutputWorkspaceSyncService.syncTaskPathListArtifacts(
                    "MANUAL_SYNC",
                    userId,
                    workstationId,
                    artifacts
            );
        }

        log.info("manual file space sync done: userId={}, spaceType={}, workstationId={}, scanned={}, synced={}, skipped={}",
                userId, spaceType, workstationId, scannedCount, artifacts.size(), skippedCount);
        return new FileSpaceSyncResponse(
                spaceType,
                workstationId,
                scannedCount,
                artifacts.size(),
                skippedCount
        );
    }

    private String normalizeSpaceType(String spaceType) {
        if (!StringUtils.hasText(spaceType)) {
            throw new IllegalArgumentException("spaceType is required");
        }
        String normalized = spaceType.trim().toUpperCase(Locale.ROOT);
        if (!SPACE_TYPE_USER.equals(normalized) && !SPACE_TYPE_WORKSTATION.equals(normalized)) {
            throw new IllegalArgumentException("spaceType must be USER or WORKSTATION");
        }
        return normalized;
    }

    private String normalizeWorkstationId(String spaceType, String workstationId) {
        if (!SPACE_TYPE_WORKSTATION.equals(spaceType)) {
            return null;
        }
        if (!StringUtils.hasText(workstationId)) {
            throw new IllegalArgumentException("workstationId is required when spaceType=WORKSTATION");
        }
        return workstationId.trim();
    }

    private List<SyncScope> resolveScopes(String spaceType, String workstationId, String userId) {
        if (SPACE_TYPE_USER.equals(spaceType)) {
            return List.of(new SyncScope("user-files/" + userId, "user/", true));
        }
        // WORKSTATION 手动同步补扫任务日志（system/{ws}/logs/{userId}/**），
        // 并以 workstation/logs/** 形态入库展示在记忆空间。
        return List.of(
                new SyncScope("workstation/" + workstationId + "/" + userId, "workstation/", true),
                new SyncScope("system/" + workstationId + "/logs/" + userId, "workstation/logs/", false)
        );
    }

    private String resolveRelativePath(SyncScope scope, String objectName) {
        String normalized = normalizePath(objectName);
        String prefix = scope.prefix();
        String prefixWithSlash = prefix.endsWith("/") ? prefix : prefix + "/";
        if (!normalized.startsWith(prefixWithSlash)) {
            return null;
        }
        String subPath = normalized.substring(prefixWithSlash.length());
        if (!StringUtils.hasText(subPath)) {
            return null;
        }
        if (scope.skipDerivedArtifacts() && (subPath.startsWith("original/") || subPath.startsWith("parsed/"))) {
            return null;
        }
        return scope.relativePrefix() + subPath;
    }

    private boolean shouldSkipRelativePath(String relativePath) {
        String normalized = normalizePath(relativePath);
        return "user/MEMORY.md".equalsIgnoreCase(normalized)
                || "workstation/MEMORY.md".equalsIgnoreCase(normalized);
    }

    private long resolveFileSize(String objectName) {
        try {
            Path path = nfsStorageService.getAbsolutePath(objectName);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            log.debug("resolve nfs file size failed: objectName={}, err={}", objectName, e.getMessage());
            return 0L;
        }
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private record SyncScope(String prefix, String relativePrefix, boolean skipDerivedArtifacts) {
    }
}

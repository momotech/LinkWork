package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.FileNodeMapper;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.model.entity.FileNodeEntity;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 将 TASK_OUTPUT_READY 的 OSS 产物同步写入文件管理索引（WORKSTATION 空间）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskOutputWorkspaceSyncService {

    private static final int NODE_NAME_MAX_LENGTH = 512;
    private static final String DELETED_NAME_MARKER = "__deleted__";
    private static final String SPACE_TYPE_WORKSTATION = "WORKSTATION";
    private static final String SPACE_TYPE_USER = "USER";
    private static final String ROOT_DIR_NAME = "任务产出";
    private static final String ROOT_DIR_FALLBACK_NAME = "任务产出_系统";

    private final TaskService taskService;
    private final DispatchConfig dispatchConfig;
    private final NfsStorageService nfsStorageService;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final FileNodeMapper fileNodeMapper;

    public record WorkspaceSyncContext(String workstationId, String parentNodeId, String taskNodeId) {}

    @Transactional(rollbackFor = Exception.class)
    public void syncTaskPathListArtifacts(
            String taskNo,
            String userId,
            String workstationId,
            List<Map<String, Object>> artifacts
    ) {
        if (artifacts == null || artifacts.isEmpty()) {
            return;
        }
        for (Map<String, Object> artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            String relativePath = normalizeWorkspaceRelativePath(String.valueOf(artifact.getOrDefault("relative_path", "")));
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }

            if (relativePath.startsWith("logs/")) {
                continue;
            }
            if (relativePath.equalsIgnoreCase("user/MEMORY.md")
                    || relativePath.equalsIgnoreCase("workstation/MEMORY.md")) {
                continue;
            }

            String action = String.valueOf(artifact.getOrDefault("action", "upsert")).trim().toLowerCase(Locale.ROOT);
            String objectName = normalizeOssPath(String.valueOf(artifact.getOrDefault("object_name", "")));
            if (!StringUtils.hasText(objectName)) {
                objectName = buildObjectNameFromRelative(relativePath, userId, workstationId);
            }

            if ("deleted".equals(action)) {
                markDeletedByObjectName(userId, workstationId, objectName);
                continue;
            }

            upsertPathListArtifact(userId, workstationId, relativePath, objectName, extractSize(artifact.get("size")));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<WorkspaceSyncContext> syncTaskOutput(String taskNo, Map<String, Object> outputData) {
        if (outputData == null) {
            return Optional.empty();
        }

        String outputType = String.valueOf(outputData.getOrDefault("output_type", "")).trim().toLowerCase(Locale.ROOT);
        if (!"oss".equals(outputType)) {
            return Optional.empty();
        }

        String ossPath = normalizeOssPath(String.valueOf(outputData.getOrDefault("oss_path", "")));
        if (!StringUtils.hasText(ossPath)) {
            return Optional.empty();
        }
        String resolvedOssPath = normalizeOssPath(String.valueOf(outputData.getOrDefault("oss_path_resolved", "")));

        TaskContext context = resolveTaskContext(taskNo, ossPath);
        if (!context.isValid()) {
            log.warn("TASK_OUTPUT_READY 索引跳过：任务上下文缺失 taskNo={}, ossPath={}", taskNo, ossPath);
            return Optional.empty();
        }

        FileNodeEntity outputRoot = ensureRootDirectory(context.userId(), context.workstationId());
        FileNodeEntity taskDirectory = ensureTaskDirectory(context.userId(), context.workstationId(),
                outputRoot.getNodeId(), context.taskNo());

        List<String> objectNames = List.of();
        for (String prefix : buildCandidatePrefixes(context.workstationId(), ossPath, resolvedOssPath)) {
            objectNames = nfsStorageService.listObjects(prefix);
            if (!objectNames.isEmpty()) {
                break;
            }
        }
        for (String objectName : objectNames) {
            String fileName = extractFileName(objectName);
            if (!StringUtils.hasText(fileName)) {
                continue;
            }
            WorkspaceFile workspaceFile = ensureWorkspaceFile(
                    context.userId(),
                    context.workstationId(),
                    objectName,
                    fileName,
                    resolveFileSize(objectName));
            ensureFileNode(context.userId(), context.workstationId(), taskDirectory.getNodeId(), workspaceFile, fileName);
        }

        return Optional.of(new WorkspaceSyncContext(
                context.workstationId(),
                outputRoot.getNodeId(),
                taskDirectory.getNodeId()));
    }

    private TaskContext resolveTaskContext(String taskNo, String ossPath) {
        String safeTaskNo = StringUtils.hasText(taskNo) ? taskNo : extractTaskNoFromPath(ossPath);
        String workstationId = null;
        String userId = null;

        if (StringUtils.hasText(safeTaskNo)) {
            try {
                Task task = taskService.getTaskByNo(safeTaskNo);
                workstationId = dispatchConfig.resolveWorkstationId(task.getRoleId());
                userId = task.getCreatorId();
            } catch (Exception e) {
                log.debug("TASK_OUTPUT_READY 任务上下文查询失败，回退路径解析: taskNo={}, error={}",
                        safeTaskNo, e.getMessage());
            }
        }

        if (!StringUtils.hasText(workstationId)) {
            workstationId = extractWorkstationIdFromPath(ossPath);
        }
        if (!StringUtils.hasText(userId)) {
            userId = extractUserIdFromPath(ossPath);
        }

        return new TaskContext(safeTaskNo, workstationId, userId);
    }

    private FileNodeEntity ensureRootDirectory(String userId, String workstationId) {
        FileNodeEntity primary = findNodeByName(userId, workstationId, null, ROOT_DIR_NAME);
        if (primary != null) {
            if ("DIR".equals(primary.getEntryType())) {
                return primary;
            }
            FileNodeEntity fallback = findNodeByName(userId, workstationId, null, ROOT_DIR_FALLBACK_NAME);
            if (fallback != null && "DIR".equals(fallback.getEntryType())) {
                return fallback;
            }
            if (fallback == null) {
                return createDirectory(userId, workstationId, null, ROOT_DIR_FALLBACK_NAME);
            }
            String uniqueName = resolveUniqueName(userId, workstationId, null, ROOT_DIR_FALLBACK_NAME);
            return createDirectory(userId, workstationId, null, uniqueName);
        }
        return createDirectory(userId, workstationId, null, ROOT_DIR_NAME);
    }

    private FileNodeEntity ensureTaskDirectory(String userId, String workstationId, String parentId, String taskNo) {
        String safeTaskNo = StringUtils.hasText(taskNo) ? taskNo : "未命名任务";
        FileNodeEntity existed = findNodeByName(userId, workstationId, parentId, safeTaskNo);
        if (existed != null) {
            if ("DIR".equals(existed.getEntryType())) {
                return existed;
            }
            String fallbackName = safeTaskNo + "_产出";
            FileNodeEntity fallback = findNodeByName(userId, workstationId, parentId, fallbackName);
            if (fallback != null && "DIR".equals(fallback.getEntryType())) {
                return fallback;
            }
            if (fallback == null) {
                return createDirectory(userId, workstationId, parentId, fallbackName);
            }
            String uniqueName = resolveUniqueName(userId, workstationId, parentId, fallbackName);
            return createDirectory(userId, workstationId, parentId, uniqueName);
        }
        return createDirectory(userId, workstationId, parentId, safeTaskNo);
    }

    private WorkspaceFile ensureWorkspaceFile(
            String userId,
            String workstationId,
            String objectName,
            String fileName,
            long fileSize) {
        WorkspaceFile existed = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getSpaceType, SPACE_TYPE_WORKSTATION)
                .eq(WorkspaceFile::getWorkstationId, workstationId)
                .eq(WorkspaceFile::getOssPath, objectName)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1"));
        if (existed != null) {
            boolean changed = false;
            if (!fileName.equals(existed.getFileName())) {
                existed.setFileName(fileName);
                changed = true;
            }
            String fileType = extractFileType(fileName);
            if (!fileType.equals(existed.getFileType())) {
                existed.setFileType(fileType);
                changed = true;
            }
            if (!Long.valueOf(fileSize).equals(existed.getFileSize())) {
                existed.setFileSize(fileSize);
                changed = true;
            }
            if (changed) {
                existed.setUpdatedAt(LocalDateTime.now());
                workspaceFileMapper.updateById(existed);
            }
            return existed;
        }

        WorkspaceFile created = new WorkspaceFile();
        created.setFileId(UUID.randomUUID().toString().replace("-", ""));
        created.setFileName(fileName);
        created.setFileSize(fileSize);
        created.setFileType(extractFileType(fileName));
        created.setSpaceType(SPACE_TYPE_WORKSTATION);
        created.setWorkstationId(workstationId);
        created.setUserId(userId);
        created.setOssPath(objectName);
        created.setParseStatus("SKIP");
        created.setMemoryIndexStatus("SKIP");
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.insert(created);
        return created;
    }

    private void ensureFileNode(String userId, String workstationId, String parentId, WorkspaceFile workspaceFile, String fileName) {
        FileNodeEntity existed = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, SPACE_TYPE_WORKSTATION)
                .eq(FileNodeEntity::getWorkstationId, workstationId)
                .eq(FileNodeEntity::getParentId, parentId)
                .eq(FileNodeEntity::getFileId, workspaceFile.getFileId())
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1"));
        if (existed != null) {
            if (!fileName.equals(existed.getName())) {
                existed.setName(fileName);
                existed.setUpdatedAt(LocalDateTime.now());
                fileNodeMapper.updateById(existed);
            }
            return;
        }

        String finalName = resolveUniqueName(userId, workstationId, parentId, fileName);

        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(parentId);
        node.setEntryType("FILE");
        node.setName(finalName);
        node.setSpaceType(SPACE_TYPE_WORKSTATION);
        node.setWorkstationId(workstationId);
        node.setUserId(userId);
        node.setFileId(workspaceFile.getFileId());
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.insert(node);
    }

    private String resolveUniqueName(String userId, String workstationId, String parentId, String expectedName) {
        FileNodeEntity conflict = findNodeByName(userId, workstationId, parentId, expectedName);
        if (conflict == null) {
            return expectedName;
        }
        String base;
        String ext;
        int dotIdx = expectedName.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < expectedName.length() - 1) {
            base = expectedName.substring(0, dotIdx);
            ext = expectedName.substring(dotIdx);
        } else {
            base = expectedName;
            ext = "";
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (findNodeByName(userId, workstationId, parentId, candidate) == null) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    private FileNodeEntity createDirectory(String userId, String workstationId, String parentId, String name) {
        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(parentId);
        node.setEntryType("DIR");
        node.setName(name);
        node.setSpaceType(SPACE_TYPE_WORKSTATION);
        node.setWorkstationId(workstationId);
        node.setUserId(userId);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.insert(node);
        return node;
    }

    private FileNodeEntity findNodeByName(String userId, String workstationId, String parentId, String name) {
        LambdaQueryWrapper<FileNodeEntity> wrapper = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, SPACE_TYPE_WORKSTATION)
                .eq(FileNodeEntity::getWorkstationId, workstationId)
                .eq(FileNodeEntity::getName, name)
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1");

        if (StringUtils.hasText(parentId)) {
            wrapper.eq(FileNodeEntity::getParentId, parentId);
        } else {
            wrapper.isNull(FileNodeEntity::getParentId);
        }
        return fileNodeMapper.selectOne(wrapper);
    }

    private String extractFileName(String objectName) {
        if (!StringUtils.hasText(objectName)) {
            return "";
        }
        int idx = objectName.lastIndexOf('/');
        if (idx < 0 || idx == objectName.length() - 1) {
            return objectName;
        }
        return objectName.substring(idx + 1);
    }

    private String extractFileType(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String extractWorkstationIdFromPath(String ossPath) {
        String[] parts = safeSplitPath(ossPath);
        if (parts.length >= 4 && "logs".equals(parts[0])) {
            return parts[1];
        }
        return null;
    }

    private String extractUserIdFromPath(String ossPath) {
        String[] parts = safeSplitPath(ossPath);
        if (parts.length >= 4 && "logs".equals(parts[0])) {
            return parts[2];
        }
        return null;
    }

    private String extractTaskNoFromPath(String ossPath) {
        String[] parts = safeSplitPath(ossPath);
        if (parts.length >= 4 && "logs".equals(parts[0])) {
            return parts[3];
        }
        return null;
    }

    private String normalizeOssPath(String rawOssPath) {
        String normalized = rawOssPath == null ? "" : rawOssPath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private List<String> buildCandidatePrefixes(String workstationId, String ossPath, String resolvedOssPath) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(resolvedOssPath)) {
            candidates.add(resolvedOssPath);
        }
        if (StringUtils.hasText(ossPath)) {
            candidates.add(ossPath);
            if (!ossPath.startsWith("system/") && StringUtils.hasText(workstationId)) {
                candidates.add("system/" + workstationId + "/" + ossPath);
            }
        }
        return List.copyOf(candidates);
    }

    private long resolveFileSize(String objectName) {
        if (!nfsStorageService.isConfigured()) {
            return 0L;
        }
        try {
            Path path = nfsStorageService.getAbsolutePath(objectName);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            log.debug("读取任务产出文件大小失败: objectName={}, error={}", objectName, e.getMessage());
            return 0L;
        }
    }

    private String[] safeSplitPath(String ossPath) {
        if (!StringUtils.hasText(ossPath)) {
            return new String[0];
        }
        return ossPath.split("/");
    }

    private void upsertPathListArtifact(
            String userId,
            String workstationId,
            String relativePath,
            String objectName,
            long fileSize
    ) {
        SpaceResolution resolution = resolveSpace(relativePath, workstationId);
        if (!resolution.valid()) {
            return;
        }

        String fileName = extractFileName(resolution.subPath());
        if (!StringUtils.hasText(fileName)) {
            return;
        }
        String parentDir = extractParentPath(resolution.subPath());
        String parentId = ensureDirectoryChain(userId, resolution.workstationId(), resolution.spaceType(), parentDir);

        WorkspaceFile workspaceFile = ensureWorkspaceFileGeneric(
                userId,
                resolution.workstationId(),
                resolution.spaceType(),
                objectName,
                fileName,
                fileSize
        );
        ensureFileNodeGeneric(
                userId,
                resolution.workstationId(),
                resolution.spaceType(),
                parentId,
                workspaceFile,
                fileName
        );
    }

    private void markDeletedByObjectName(String userId, String workstationId, String objectName) {
        if (!StringUtils.hasText(objectName)) {
            return;
        }
        WorkspaceFile existed = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getOssPath, objectName)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1"));
        if (existed == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        existed.setDeletedAt(now);
        existed.setUpdatedAt(now);
        workspaceFileMapper.updateById(existed);

        List<FileNodeEntity> nodes = fileNodeMapper.selectList(new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getFileId, existed.getFileId())
                .isNull(FileNodeEntity::getDeletedAt));
        for (FileNodeEntity node : nodes) {
            node.setDeletedAt(now);
            node.setUpdatedAt(now);
            node.setName(buildDeletedTombstoneName(node.getName(), node.getNodeId()));
            fileNodeMapper.updateById(node);
        }
    }

    private String buildDeletedTombstoneName(String originalName, String nodeId) {
        String baseName = StringUtils.hasText(originalName) ? originalName : "node";
        String safeNodeId = StringUtils.hasText(nodeId)
                ? nodeId
                : UUID.randomUUID().toString().replace("-", "");
        String suffix = DELETED_NAME_MARKER + safeNodeId;
        int allowedBaseLength = NODE_NAME_MAX_LENGTH - suffix.length();
        if (allowedBaseLength <= 0) {
            return suffix.substring(Math.max(0, suffix.length() - NODE_NAME_MAX_LENGTH));
        }
        if (baseName.length() > allowedBaseLength) {
            baseName = baseName.substring(0, allowedBaseLength);
        }
        return baseName + suffix;
    }

    private WorkspaceFile ensureWorkspaceFileGeneric(
            String userId,
            String workstationId,
            String spaceType,
            String objectName,
            String fileName,
            long fileSize
    ) {
        LambdaQueryWrapper<WorkspaceFile> query = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getSpaceType, spaceType)
                .eq(WorkspaceFile::getOssPath, objectName)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1");
        if (SPACE_TYPE_WORKSTATION.equals(spaceType)) {
            query.eq(WorkspaceFile::getWorkstationId, workstationId);
        } else {
            query.isNull(WorkspaceFile::getWorkstationId);
        }

        WorkspaceFile existed = workspaceFileMapper.selectOne(query);
        if (existed != null) {
            boolean changed = false;
            String fileType = extractFileType(fileName);
            if (!fileName.equals(existed.getFileName())) {
                existed.setFileName(fileName);
                changed = true;
            }
            if (!fileType.equals(existed.getFileType())) {
                existed.setFileType(fileType);
                changed = true;
            }
            if (!Long.valueOf(fileSize).equals(existed.getFileSize())) {
                existed.setFileSize(fileSize);
                changed = true;
            }
            if (changed) {
                existed.setUpdatedAt(LocalDateTime.now());
                workspaceFileMapper.updateById(existed);
            }
            return existed;
        }

        WorkspaceFile created = new WorkspaceFile();
        created.setFileId(UUID.randomUUID().toString().replace("-", ""));
        created.setFileName(fileName);
        created.setFileSize(fileSize);
        created.setFileType(extractFileType(fileName));
        created.setSpaceType(spaceType);
        created.setWorkstationId(SPACE_TYPE_WORKSTATION.equals(spaceType) ? workstationId : null);
        created.setUserId(userId);
        created.setOssPath(objectName);
        created.setParseStatus("SKIP");
        created.setMemoryIndexStatus("SKIP");
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.insert(created);
        return created;
    }

    private void ensureFileNodeGeneric(
            String userId,
            String workstationId,
            String spaceType,
            String parentId,
            WorkspaceFile workspaceFile,
            String fileName
    ) {
        String desiredName = fileName;
        LambdaQueryWrapper<FileNodeEntity> query = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, spaceType)
                .eq(FileNodeEntity::getFileId, workspaceFile.getFileId())
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1");
        if (SPACE_TYPE_WORKSTATION.equals(spaceType)) {
            query.eq(FileNodeEntity::getWorkstationId, workstationId);
        } else {
            query.isNull(FileNodeEntity::getWorkstationId);
        }

        FileNodeEntity existed = fileNodeMapper.selectOne(query);
        if (existed != null) {
            if (!Objects.equals(existed.getParentId(), parentId)) {
                desiredName = resolveUniqueNameGenericExceptNode(
                        userId, workstationId, spaceType, parentId, fileName, existed.getNodeId());
            } else if (!fileName.equals(existed.getName())) {
                desiredName = resolveUniqueNameGenericExceptNode(
                        userId, workstationId, spaceType, parentId, fileName, existed.getNodeId());
            }

            boolean changed = false;
            if (!Objects.equals(existed.getParentId(), parentId)) {
                existed.setParentId(parentId);
                changed = true;
            }
            if (!desiredName.equals(existed.getName())) {
                existed.setName(desiredName);
                changed = true;
            }
            if (changed) {
                existed.setUpdatedAt(LocalDateTime.now());
                fileNodeMapper.updateById(existed);
            }
            return;
        }

        String finalName = resolveUniqueNameGeneric(userId, workstationId, spaceType, parentId, desiredName);
        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(parentId);
        node.setEntryType("FILE");
        node.setName(finalName);
        node.setSpaceType(spaceType);
        node.setWorkstationId(SPACE_TYPE_WORKSTATION.equals(spaceType) ? workstationId : null);
        node.setUserId(userId);
        node.setFileId(workspaceFile.getFileId());
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.insert(node);
    }

    private String ensureDirectoryChain(
            String userId,
            String workstationId,
            String spaceType,
            String parentPath
    ) {
        if (!StringUtils.hasText(parentPath)) {
            return null;
        }
        String parentId = null;
        for (String segment : parentPath.split("/")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            FileNodeEntity existed = findNodeByNameGeneric(userId, workstationId, spaceType, parentId, segment);
            if (existed != null && "DIR".equals(existed.getEntryType())) {
                parentId = existed.getNodeId();
                continue;
            }
            String dirName = resolveUniqueNameGeneric(userId, workstationId, spaceType, parentId, segment);
            FileNodeEntity dir = createDirectoryGeneric(userId, workstationId, spaceType, parentId, dirName);
            parentId = dir.getNodeId();
        }
        return parentId;
    }

    private FileNodeEntity findNodeByNameGeneric(
            String userId,
            String workstationId,
            String spaceType,
            String parentId,
            String name
    ) {
        LambdaQueryWrapper<FileNodeEntity> wrapper = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, spaceType)
                .eq(FileNodeEntity::getName, name)
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1");
        if (SPACE_TYPE_WORKSTATION.equals(spaceType)) {
            wrapper.eq(FileNodeEntity::getWorkstationId, workstationId);
        } else {
            wrapper.isNull(FileNodeEntity::getWorkstationId);
        }
        if (StringUtils.hasText(parentId)) {
            wrapper.eq(FileNodeEntity::getParentId, parentId);
        } else {
            wrapper.isNull(FileNodeEntity::getParentId);
        }
        return fileNodeMapper.selectOne(wrapper);
    }

    private String resolveUniqueNameGeneric(
            String userId,
            String workstationId,
            String spaceType,
            String parentId,
            String expectedName
    ) {
        return resolveUniqueNameGenericExceptNode(
                userId, workstationId, spaceType, parentId, expectedName, null);
    }

    private String resolveUniqueNameGenericExceptNode(
            String userId,
            String workstationId,
            String spaceType,
            String parentId,
            String expectedName,
            String ignoredNodeId
    ) {
        FileNodeEntity conflict = findNodeByNameGeneric(userId, workstationId, spaceType, parentId, expectedName);
        if (conflict == null || Objects.equals(conflict.getNodeId(), ignoredNodeId)) {
            return expectedName;
        }
        String base;
        String ext;
        int dotIdx = expectedName.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < expectedName.length() - 1) {
            base = expectedName.substring(0, dotIdx);
            ext = expectedName.substring(dotIdx);
        } else {
            base = expectedName;
            ext = "";
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = base + " (" + i + ")" + ext;
            FileNodeEntity candidateConflict = findNodeByNameGeneric(
                    userId, workstationId, spaceType, parentId, candidate);
            if (candidateConflict == null || Objects.equals(candidateConflict.getNodeId(), ignoredNodeId)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    private FileNodeEntity createDirectoryGeneric(
            String userId,
            String workstationId,
            String spaceType,
            String parentId,
            String name
    ) {
        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(parentId);
        node.setEntryType("DIR");
        node.setName(name);
        node.setSpaceType(spaceType);
        node.setWorkstationId(SPACE_TYPE_WORKSTATION.equals(spaceType) ? workstationId : null);
        node.setUserId(userId);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.insert(node);
        return node;
    }

    private String normalizeWorkspaceRelativePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
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

    private String extractParentPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return "";
        }
        return path.substring(0, idx);
    }

    private long extractSize(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private String buildObjectNameFromRelative(String relativePath, String userId, String workstationId) {
        if (relativePath.startsWith("user/")) {
            String subPath = relativePath.substring("user/".length());
            if (StringUtils.hasText(subPath) && StringUtils.hasText(userId)) {
                return "user-files/" + userId + "/" + subPath;
            }
        }
        if (relativePath.startsWith("workstation/")) {
            String subPath = relativePath.substring("workstation/".length());
            if (StringUtils.hasText(subPath) && StringUtils.hasText(userId) && StringUtils.hasText(workstationId)) {
                return "workstation/" + workstationId + "/" + userId + "/" + subPath;
            }
        }
        return "";
    }

    private SpaceResolution resolveSpace(String relativePath, String workstationId) {
        if (relativePath.startsWith("user/")) {
            return new SpaceResolution(SPACE_TYPE_USER, null, relativePath.substring("user/".length()));
        }
        if (relativePath.startsWith("workstation/")) {
            return new SpaceResolution(SPACE_TYPE_WORKSTATION, workstationId, relativePath.substring("workstation/".length()));
        }
        return new SpaceResolution("", "", "");
    }

    private record SpaceResolution(String spaceType, String workstationId, String subPath) {
        private boolean valid() {
            return StringUtils.hasText(spaceType) && StringUtils.hasText(subPath);
        }
    }

    private record TaskContext(String taskNo, String workstationId, String userId) {
        private boolean isValid() {
            return StringUtils.hasText(taskNo) && StringUtils.hasText(workstationId) && StringUtils.hasText(userId);
        }
    }
}

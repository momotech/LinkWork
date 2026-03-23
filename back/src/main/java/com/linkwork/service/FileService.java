package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.FileConflictException;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.model.enums.ConflictPolicy;
import com.linkwork.model.dto.FileMentionResponse;
import com.linkwork.model.dto.FileResponse;
import com.linkwork.model.dto.FileTransferRequest;
import com.linkwork.model.dto.MemoryIndexJob;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.service.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "txt", "md", "csv", "doc", "docx", "pdf", "ppt", "pptx", "xlsx", "xls",
            "jpg", "jpeg", "png", "gif"
    );
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Set<String> PARSE_REQUIRED_TYPES = Set.of("doc", "docx", "pdf", "ppt", "pptx");
    private static final Set<String> MEMORY_DIRECT_TYPES = Set.of("txt", "md", "csv");
    private static final Set<String> MEMORY_SKIP_TYPES = Set.of("xlsx", "xls", "jpg", "jpeg", "png", "gif");
    private static final String FILE_PARSE_QUEUE_KEY = "file:parse:jobs";
    private static final String FILE_TRANSFER_DEDUP_KEY_PREFIX = "file:transfer:dedup";
    private static final long FILE_TRANSFER_DEDUP_SECONDS = 5L;

    private final WorkspaceFileMapper workspaceFileMapper;
    private final NfsStorageService nfsStorageService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final com.linkwork.config.MemoryConfig memoryConfig;
    private final FileNodeService fileNodeService;

    @Autowired(required = false)
    private MemoryService memoryService;

    public FileResponse uploadFile(MultipartFile file, String spaceType, String workstationId, String userId) {
        return uploadFile(file, spaceType, workstationId, userId, null, null);
    }

    public FileResponse uploadFile(MultipartFile file, String spaceType, String workstationId,
                                   String userId, String conflictPolicyStr) {
        return uploadFile(file, spaceType, workstationId, userId, conflictPolicyStr, null);
    }

    public FileResponse uploadFile(MultipartFile file, String spaceType, String workstationId,
                                   String userId, String conflictPolicyStr, String parentId) {
        validateUpload(file, spaceType, workstationId, userId);
        String normalizedSpace = spaceType.toUpperCase(Locale.ROOT);
        fileNodeService.validateParentId(parentId, userId, normalizedSpace, workstationId);
        ConflictPolicy policy = ConflictPolicy.fromString(conflictPolicyStr);
        String originalName = file.getOriginalFilename();

        com.linkwork.model.entity.FileNodeEntity existingNode = fileNodeService.findSameNameNode(
                userId, normalizedSpace, workstationId, parentId, originalName);
        if (existingNode != null) {
            switch (policy) {
                case REJECT -> {
                    WorkspaceFile existingFile = "FILE".equals(existingNode.getEntryType()) && existingNode.getFileId() != null
                            ? findActiveByFileId(existingNode.getFileId()) : null;
                    throw new FileConflictException(
                            "目标目录已存在同名" + ("DIR".equals(existingNode.getEntryType()) ? "目录" : "文件"),
                            existingNode.getFileId() != null ? existingNode.getFileId() : existingNode.getNodeId(),
                            existingNode.getName(), existingNode.getEntryType(),
                            existingFile != null ? existingFile.getFileSize() : null,
                            existingNode.getUpdatedAt());
                }
                case OVERWRITE -> {
                    if ("DIR".equals(existingNode.getEntryType())) {
                        throw new IllegalArgumentException("无法用文件覆盖目录");
                    }
                    WorkspaceFile existingFile = findActiveByFileId(existingNode.getFileId());
                    return overwriteUpload(existingFile, file);
                }
                case RENAME -> originalName = generateUniqueNodeName(userId, normalizedSpace, workstationId, parentId, originalName);
            }
        }

        String ext = getExtension(originalName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String ossPath = buildOssPath(normalizedSpace, workstationId, userId, fileId, ext);

        WorkspaceFile workspaceFile = new WorkspaceFile();
        workspaceFile.setFileId(fileId);
        workspaceFile.setFileName(originalName);
        workspaceFile.setFileSize(file.getSize());
        workspaceFile.setFileType(ext);
        workspaceFile.setContentType(file.getContentType());
        workspaceFile.setSpaceType(normalizedSpace);
        workspaceFile.setWorkstationId(workstationId);
        workspaceFile.setUserId(userId);
        workspaceFile.setOssPath(ossPath);
        workspaceFile.setMemoryIndexStatus("NONE");
        workspaceFile.setParseStatus(PARSE_REQUIRED_TYPES.contains(ext) ? "NONE" : "SKIP");
        workspaceFile.setFileHash(computeSha256(file));
        workspaceFile.setCreatedAt(LocalDateTime.now());
        workspaceFile.setUpdatedAt(LocalDateTime.now());

        try {
            nfsStorageService.uploadFileToPath(file, ossPath);
        } catch (IOException e) {
            throw new IllegalStateException("上传文件到存储失败", e);
        }
        workspaceFileMapper.insert(workspaceFile);

        fileNodeService.createFileNode(originalName, normalizedSpace, workstationId, userId, fileId, parentId);

        if ("NONE".equals(workspaceFile.getParseStatus())) {
            workspaceFile.setParseStatus("PARSING");
            workspaceFile.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(workspaceFile);
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, fileId);
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(workspaceFile);
        }

        return toResponse(workspaceFile);
    }

    private FileResponse overwriteUpload(WorkspaceFile target, MultipartFile newFile) {
        String ext = getExtension(newFile.getOriginalFilename());
        try {
            nfsStorageService.uploadFileToPath(newFile, target.getOssPath());
        } catch (IOException e) {
            throw new IllegalStateException("覆盖上传文件失败", e);
        }

        if (StringUtils.hasText(target.getParsedOssPath())) {
            try {
                nfsStorageService.deleteFile(target.getParsedOssPath());
            } catch (Exception e) {
                log.warn("删除旧解析文件失败: fileId={}, err={}", target.getFileId(), e.getMessage());
            }
        }

        target.setFileSize(newFile.getSize());
        target.setFileType(ext);
        target.setContentType(newFile.getContentType());
        target.setFileHash(computeSha256(newFile));
        target.setParseStatus(PARSE_REQUIRED_TYPES.contains(ext) ? "PARSING" : "SKIP");
        target.setMemoryIndexStatus("NONE");
        target.setParsedOssPath(null);
        target.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.updateById(target);

        if (PARSE_REQUIRED_TYPES.contains(ext)) {
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, target.getFileId());
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(target);
        }

        return toResponse(target);
    }

    public Map<String, Object> listFiles(String spaceType, String workstationId, String fileType,
                                         String keyword, Integer page, Integer pageSize, String userId) {
        validateSpaceType(spaceType, workstationId);
        int currentPage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        if (size > 100) {
            size = 100;
        }

        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getSpaceType, spaceType.toUpperCase(Locale.ROOT))
                .isNull(WorkspaceFile::getDeletedAt)
                .orderByDesc(WorkspaceFile::getCreatedAt);

        if ("WORKSTATION".equalsIgnoreCase(spaceType)) {
            wrapper.eq(WorkspaceFile::getWorkstationId, workstationId);
        }
        if (StringUtils.hasText(fileType)) {
            wrapper.eq(WorkspaceFile::getFileType, fileType.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(WorkspaceFile::getFileName, keyword.trim());
        }

        Page<WorkspaceFile> result = workspaceFileMapper.selectPage(new Page<>(currentPage, size), wrapper);
        List<FileResponse> items = result.getRecords().stream().map(this::toResponse).toList();

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());

        Map<String, Object> payload = new HashMap<>();
        payload.put("items", items);
        payload.put("pagination", pagination);
        return payload;
    }

    public FileResponse getFileDetail(String fileId, String userId) {
        WorkspaceFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        return toResponse(file);
    }

    public DownloadInfo getDownloadInfo(String fileId, String userId) {
        WorkspaceFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        return new DownloadInfo(file.getOssPath(), file.getFileName(), file.getContentType());
    }

    public record DownloadInfo(String storagePath, String fileName, String contentType) {}

    public void deleteFile(String fileId, String userId) {
        WorkspaceFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);

        file.setDeletedAt(LocalDateTime.now());
        file.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.updateById(file);

        com.linkwork.model.entity.FileNodeEntity node = fileNodeService.findByFileId(
                fileId, userId, file.getSpaceType(), file.getWorkstationId());
        if (node != null) {
            fileNodeService.deleteNode(node.getNodeId(), userId);
        }

        try {
            nfsStorageService.deleteFile(file.getOssPath());
        } catch (Exception e) {
            log.warn("删除原文件失败: fileId={}, path={}, err={}", fileId, file.getOssPath(), e.getMessage());
        }
        if (StringUtils.hasText(file.getParsedOssPath())) {
            try {
                nfsStorageService.deleteFile(file.getParsedOssPath());
            } catch (Exception e) {
                log.warn("删除解析文件失败: fileId={}, path={}, err={}", fileId, file.getParsedOssPath(), e.getMessage());
            }
        }
        if (memoryService != null) {
            try {
                String source = StringUtils.hasText(file.getParsedOssPath()) ? file.getParsedOssPath() : file.getOssPath();
                memoryService.deleteSource(file.getWorkstationId(), file.getUserId(), source);
            } catch (Exception e) {
                log.warn("删除 Memory 索引失败: fileId={}, err={}", fileId, e.getMessage());
            }
        }
    }

    public FileResponse replaceFile(String fileId, MultipartFile newFile, String userId) {
        WorkspaceFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        validateUpload(newFile, file.getSpaceType(), file.getWorkstationId(), userId);

        String ext = getExtension(newFile.getOriginalFilename());
        try {
            nfsStorageService.uploadFileToPath(newFile, file.getOssPath());
        } catch (IOException e) {
            throw new IllegalStateException("覆盖上传文件失败", e);
        }

        if (StringUtils.hasText(file.getParsedOssPath())) {
            try {
                nfsStorageService.deleteFile(file.getParsedOssPath());
            } catch (Exception e) {
                log.warn("删除旧解析文件失败: fileId={}, err={}", fileId, e.getMessage());
            }
        }

        file.setFileName(newFile.getOriginalFilename());
        file.setFileSize(newFile.getSize());
        file.setFileType(ext);
        file.setContentType(newFile.getContentType());
        file.setFileHash(computeSha256(newFile));
        file.setParseStatus(PARSE_REQUIRED_TYPES.contains(ext) ? "PARSING" : "SKIP");
        file.setMemoryIndexStatus("NONE");
        file.setParsedOssPath(null);
        file.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.updateById(file);

        if (PARSE_REQUIRED_TYPES.contains(ext)) {
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, file.getFileId());
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(file);
        }

        return toResponse(file);
    }

    public FileResponse copyFile(String fileId, FileTransferRequest request, String userId) {
        WorkspaceFile source = findActiveByFileId(fileId);
        checkPermission(source, userId);
        validateSpaceType(request.getTargetSpaceType(), request.getTargetWorkstationId());
        String targetSpaceType = request.getTargetSpaceType().toUpperCase(Locale.ROOT);
        String targetParentId = request.getTargetParentId();
        fileNodeService.validateParentId(targetParentId, userId, targetSpaceType, request.getTargetWorkstationId());

        ConflictPolicy policy = request.resolveConflictPolicy();
        com.linkwork.model.entity.FileNodeEntity conflictNode = fileNodeService.findSameNameNode(
                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, source.getFileName());
        String targetFileName = source.getFileName();

        if (conflictNode != null) {
            switch (policy) {
                case REJECT -> {
                    WorkspaceFile conflictFile = "FILE".equals(conflictNode.getEntryType()) && conflictNode.getFileId() != null
                            ? findActiveByFileId(conflictNode.getFileId()) : null;
                    throw new FileConflictException(
                            "目标目录已存在同名" + ("DIR".equals(conflictNode.getEntryType()) ? "目录" : "文件"),
                            conflictNode.getFileId() != null ? conflictNode.getFileId() : conflictNode.getNodeId(),
                            conflictNode.getName(), conflictNode.getEntryType(),
                            conflictFile != null ? conflictFile.getFileSize() : null,
                            conflictNode.getUpdatedAt());
                }
                case OVERWRITE -> {
                    if ("DIR".equals(conflictNode.getEntryType())) {
                        throw new IllegalArgumentException("无法用文件覆盖目录");
                    }
                    WorkspaceFile conflictFile = findActiveByFileId(conflictNode.getFileId());
                    acquireTransferDedup(source.getFileId(), userId, "copy", targetSpaceType,
                            request.getTargetWorkstationId(), policy, targetParentId);
                    return toResponse(overwriteTargetFile(source, conflictFile));
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        com.linkwork.model.entity.FileNodeEntity newNameConflict = fileNodeService.findSameNameNode(
                                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, request.getNewName());
                        if (newNameConflict != null) {
                            WorkspaceFile conflictFile = "FILE".equals(newNameConflict.getEntryType()) && newNameConflict.getFileId() != null
                                    ? findActiveByFileId(newNameConflict.getFileId()) : null;
                            throw new FileConflictException(
                                    "目标目录已存在同名" + ("DIR".equals(newNameConflict.getEntryType()) ? "目录" : "文件"),
                                    newNameConflict.getFileId() != null ? newNameConflict.getFileId() : newNameConflict.getNodeId(),
                                    newNameConflict.getName(), newNameConflict.getEntryType(),
                                    conflictFile != null ? conflictFile.getFileSize() : null,
                                    newNameConflict.getUpdatedAt());
                        }
                        targetFileName = request.getNewName();
                    } else {
                        targetFileName = generateUniqueNodeName(userId, targetSpaceType,
                                request.getTargetWorkstationId(), targetParentId, source.getFileName());
                    }
                }
            }
        }

        acquireTransferDedup(source.getFileId(), userId, "copy", targetSpaceType,
                request.getTargetWorkstationId(), policy, targetParentId);

        String newFileId = UUID.randomUUID().toString().replace("-", "");
        String ext = source.getFileType();
        String targetOssPath = buildOssPath(targetSpaceType, request.getTargetWorkstationId(), userId, newFileId, ext);
        nfsStorageService.copyObject(source.getOssPath(), targetOssPath);

        String targetParsedPath = null;
        if (StringUtils.hasText(source.getParsedOssPath())) {
            targetParsedPath = buildParsedPath(targetOssPath);
            nfsStorageService.copyObject(source.getParsedOssPath(), targetParsedPath);
        }

        WorkspaceFile copied = new WorkspaceFile();
        copied.setFileId(newFileId);
        copied.setFileName(targetFileName);
        copied.setFileSize(source.getFileSize());
        copied.setFileType(source.getFileType());
        copied.setContentType(source.getContentType());
        copied.setSpaceType(targetSpaceType);
        copied.setWorkstationId("WORKSTATION".equals(targetSpaceType) ? request.getTargetWorkstationId() : null);
        copied.setUserId(userId);
        copied.setOssPath(targetOssPath);
        copied.setParsedOssPath(targetParsedPath);
        copied.setParseStatus(source.getParseStatus());
        copied.setMemoryIndexStatus("NONE");
        copied.setFileHash(source.getFileHash());
        copied.setCreatedAt(LocalDateTime.now());
        copied.setUpdatedAt(LocalDateTime.now());

        try {
            workspaceFileMapper.insert(copied);
        } catch (Exception e) {
            try { nfsStorageService.deleteFile(targetOssPath); } catch (Exception ignored) { }
            if (targetParsedPath != null) {
                try { nfsStorageService.deleteFile(targetParsedPath); } catch (Exception ignored) { }
            }
            throw e;
        }

        fileNodeService.createFileNode(targetFileName, targetSpaceType, request.getTargetWorkstationId(),
                userId, newFileId, targetParentId);

        if ("PARSED".equals(copied.getParseStatus()) || MEMORY_DIRECT_TYPES.contains(copied.getFileType())) {
            triggerMemoryIndex(copied);
        }

        return toResponse(copied);
    }

    public FileResponse moveFile(String fileId, FileTransferRequest request, String userId) {
        WorkspaceFile source = findActiveByFileId(fileId);
        checkPermission(source, userId);
        validateSpaceType(request.getTargetSpaceType(), request.getTargetWorkstationId());
        String targetSpaceType = request.getTargetSpaceType().toUpperCase(Locale.ROOT);
        String targetParentId = request.getTargetParentId();
        fileNodeService.validateParentId(targetParentId, userId, targetSpaceType, request.getTargetWorkstationId());

        ConflictPolicy policy = request.resolveConflictPolicy();

        com.linkwork.model.entity.FileNodeEntity sourceNode = fileNodeService.findByFileId(
                fileId, userId, source.getSpaceType(), source.getWorkstationId());
        com.linkwork.model.entity.FileNodeEntity conflictNode = fileNodeService.findSameNameNode(
                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, source.getFileName());
        // move 排除自身节点
        if (conflictNode != null && sourceNode != null && conflictNode.getNodeId().equals(sourceNode.getNodeId())) {
            conflictNode = null;
        }

        // ── 阶段 1：校验（可能抛 409，不占 dedup，不做写操作） ──
        if (conflictNode != null) {
            switch (policy) {
                case REJECT -> {
                    WorkspaceFile conflictFile = "FILE".equals(conflictNode.getEntryType()) && conflictNode.getFileId() != null
                            ? findActiveByFileId(conflictNode.getFileId()) : null;
                    throw new FileConflictException(
                            "目标目录已存在同名" + ("DIR".equals(conflictNode.getEntryType()) ? "目录" : "文件"),
                            conflictNode.getFileId() != null ? conflictNode.getFileId() : conflictNode.getNodeId(),
                            conflictNode.getName(), conflictNode.getEntryType(),
                            conflictFile != null ? conflictFile.getFileSize() : null,
                            conflictNode.getUpdatedAt());
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        com.linkwork.model.entity.FileNodeEntity newNameConflict = fileNodeService.findSameNameNode(
                                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, request.getNewName());
                        if (newNameConflict != null && (sourceNode == null || !newNameConflict.getNodeId().equals(sourceNode.getNodeId()))) {
                            WorkspaceFile cf = "FILE".equals(newNameConflict.getEntryType()) && newNameConflict.getFileId() != null
                                    ? findActiveByFileId(newNameConflict.getFileId()) : null;
                            throw new FileConflictException(
                                    "目标目录已存在同名" + ("DIR".equals(newNameConflict.getEntryType()) ? "目录" : "文件"),
                                    newNameConflict.getFileId() != null ? newNameConflict.getFileId() : newNameConflict.getNodeId(),
                                    newNameConflict.getName(), newNameConflict.getEntryType(),
                                    cf != null ? cf.getFileSize() : null,
                                    newNameConflict.getUpdatedAt());
                        }
                    }
                }
                default -> { }
            }
        }

        // ── 阶段 2：dedup → 执行写操作 ──
        acquireTransferDedup(source.getFileId(), userId, "move", targetSpaceType,
                request.getTargetWorkstationId(), policy, targetParentId);

        if (conflictNode != null) {
            switch (policy) {
                case OVERWRITE -> {
                    if ("DIR".equals(conflictNode.getEntryType())) {
                        throw new IllegalArgumentException("无法用文件覆盖目录");
                    }
                    WorkspaceFile conflictFile = findActiveByFileId(conflictNode.getFileId());
                    conflictFile.setDeletedAt(LocalDateTime.now());
                    conflictFile.setUpdatedAt(LocalDateTime.now());
                    workspaceFileMapper.updateById(conflictFile);
                    fileNodeService.deleteNode(conflictNode.getNodeId(), userId);
                    try {
                        nfsStorageService.deleteFile(conflictFile.getOssPath());
                        if (StringUtils.hasText(conflictFile.getParsedOssPath())) {
                            nfsStorageService.deleteFile(conflictFile.getParsedOssPath());
                        }
                    } catch (Exception e) {
                        log.warn("清理被覆盖文件失败: fileId={}, err={}", conflictFile.getFileId(), e.getMessage());
                    }
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        source.setFileName(request.getNewName());
                    } else {
                        String newName = generateUniqueNodeName(userId, targetSpaceType,
                                request.getTargetWorkstationId(), targetParentId, source.getFileName());
                        source.setFileName(newName);
                    }
                }
                default -> { }
            }
        }

        String oldWorkstationId = source.getWorkstationId();
        String oldSpaceType = source.getSpaceType();
        String oldOssPath = source.getOssPath();
        String oldParsedPath = source.getParsedOssPath();

        String targetOssPath = buildOssPath(targetSpaceType, request.getTargetWorkstationId(), userId, source.getFileId(), source.getFileType());
        boolean storagePathChanged = !Objects.equals(oldOssPath, targetOssPath);
        String targetParsedPath = StringUtils.hasText(oldParsedPath) ? buildParsedPath(targetOssPath) : null;

        if (storagePathChanged) {
            nfsStorageService.copyObject(oldOssPath, targetOssPath);
            if (StringUtils.hasText(oldParsedPath)) {
                nfsStorageService.copyObject(oldParsedPath, targetParsedPath);
            }
        } else {
            // Same path (e.g. move within same space/directory tree), no copy/delete required.
            targetParsedPath = oldParsedPath;
        }

        source.setSpaceType(targetSpaceType);
        source.setWorkstationId("WORKSTATION".equals(targetSpaceType) ? request.getTargetWorkstationId() : null);
        source.setOssPath(targetOssPath);
        source.setParsedOssPath(targetParsedPath);
        source.setUpdatedAt(LocalDateTime.now());
        source.setMemoryIndexStatus("NONE");
        workspaceFileMapper.updateById(source);

        // 更新 file node 归属
        if (sourceNode != null) {
            sourceNode.setParentId(targetParentId);
            sourceNode.setSpaceType(targetSpaceType);
            sourceNode.setWorkstationId("WORKSTATION".equals(targetSpaceType) ? request.getTargetWorkstationId() : null);
            sourceNode.setName(source.getFileName());
            sourceNode.setUpdatedAt(LocalDateTime.now());
            fileNodeService.updateNode(sourceNode);
        }

        if (storagePathChanged) {
            try {
                nfsStorageService.deleteFile(oldOssPath);
            } catch (Exception e) {
                log.warn("删除旧原文件失败: fileId={}, path={}, err={}", fileId, oldOssPath, e.getMessage());
            }
            if (StringUtils.hasText(oldParsedPath) && !Objects.equals(oldParsedPath, targetParsedPath)) {
                try {
                    nfsStorageService.deleteFile(oldParsedPath);
                } catch (Exception e) {
                    log.warn("删除旧解析文件失败: fileId={}, path={}, err={}", fileId, oldParsedPath, e.getMessage());
                }
            }
        }

        if (memoryService != null) {
            try {
                if ("WORKSTATION".equals(oldSpaceType)) {
                    memoryService.deleteSource(oldWorkstationId, source.getUserId(), oldOssPath);
                } else {
                    memoryService.deleteSource(null, source.getUserId(), oldOssPath);
                }
            } catch (Exception e) {
                log.warn("清理旧Memory索引失败: {}", e.getMessage());
            }
        }

        if ("PARSED".equals(source.getParseStatus()) || MEMORY_DIRECT_TYPES.contains(source.getFileType())) {
            triggerMemoryIndex(source);
        }

        return toResponse(source);
    }

    public List<FileMentionResponse> mentionFiles(String workstationId, String keyword, String userId) {
        List<WorkspaceFile> wsFiles = listBySpaceForMention(userId, "WORKSTATION", workstationId, keyword);
        List<WorkspaceFile> userFiles = listBySpaceForMention(userId, "USER", null, keyword);
        return Stream.concat(wsFiles.stream(), userFiles.stream())
                .limit(50)
                .map(this::toMentionResponse)
                .toList();
    }

    public void triggerMemoryIndex(WorkspaceFile file) {
        if (memoryService == null || file == null) {
            return;
        }

        String fileType = file.getFileType();
        String objectName = null;
        if (MEMORY_DIRECT_TYPES.contains(fileType)) {
            objectName = file.getOssPath();
        } else if (PARSE_REQUIRED_TYPES.contains(fileType) && "PARSED".equals(file.getParseStatus())) {
            objectName = file.getParsedOssPath();
        } else if (MEMORY_SKIP_TYPES.contains(fileType)) {
            file.setMemoryIndexStatus("SKIP");
            file.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(file);
            return;
        } else {
            return;
        }

        if (!StringUtils.hasText(objectName)) {
            return;
        }

        if ("WORKSTATION".equals(file.getSpaceType())) {
            RoleEntity role = null;
            if (StringUtils.hasText(file.getWorkstationId())) {
                role = roleService.getOne(new LambdaQueryWrapper<RoleEntity>()
                        .eq(RoleEntity::getRoleNo, file.getWorkstationId())
                        .last("limit 1"));
                if (role == null) {
                    try {
                        role = roleService.getById(Long.valueOf(file.getWorkstationId()));
                    } catch (Exception ignored) {
                        // ignore non-numeric workstation id
                    }
                }
            }
            Boolean enabled = role != null && role.getConfigJson() != null ? role.getConfigJson().getMemoryEnabled() : null;
            if (Boolean.FALSE.equals(enabled)) {
                file.setMemoryIndexStatus("SKIP");
                file.setUpdatedAt(LocalDateTime.now());
                workspaceFileMapper.updateById(file);
                return;
            }
        }

        String collectionName = "USER".equals(file.getSpaceType())
                ? memoryConfig.userCollectionName(file.getUserId())
                : memoryConfig.collectionName(file.getWorkstationId(), file.getUserId());

        MemoryIndexJob job = MemoryIndexJob.builder()
                .jobId(UUID.randomUUID().toString())
                .workstationId(file.getWorkstationId())
                .userId(file.getUserId())
                .jobType(MemoryIndexJob.JobType.FILE_UPLOAD)
                .fileType(fileType)
                .source(objectName)
                .storageType("NFS")
                .objectName(objectName)
                .collectionName(collectionName)
                .build();

        try {
            String payload = objectMapper.writeValueAsString(job);
            redisTemplate.opsForList().leftPush(memoryConfig.getIndex().getQueueKey(), payload);
            file.setMemoryIndexStatus("INDEXING");
            file.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(file);
        } catch (Exception e) {
            throw new IllegalStateException("触发 Memory 索引失败", e);
        }
    }

    private List<WorkspaceFile> listBySpaceForMention(String userId, String spaceType, String workstationId, String keyword) {
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getSpaceType, spaceType)
                .isNull(WorkspaceFile::getDeletedAt)
                .orderByDesc(WorkspaceFile::getCreatedAt)
                .last("limit 50");
        if (StringUtils.hasText(workstationId)) {
            wrapper.eq(WorkspaceFile::getWorkstationId, workstationId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(WorkspaceFile::getFileName, keyword.trim());
        }
        return workspaceFileMapper.selectList(wrapper);
    }

    private FileMentionResponse toMentionResponse(WorkspaceFile file) {
        FileMentionResponse response = new FileMentionResponse();
        response.setFileId(file.getFileId());
        response.setFileName(file.getFileName());
        response.setFileType(file.getFileType());
        response.setFileSize(file.getFileSize());
        response.setSpaceType(file.getSpaceType());
        response.setWorkstationId(file.getWorkstationId());
        response.setCreatedAt(file.getCreatedAt());
        return response;
    }

    private void acquireTransferDedup(String fileId, String userId, String operation,
                                      String targetSpaceType, String targetWorkstationId,
                                      ConflictPolicy policy, String targetParentId) {
        String key = buildTransferDedupKey(fileId, userId, operation, targetSpaceType, targetWorkstationId, policy, targetParentId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, "1", FILE_TRANSFER_DEDUP_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("重复提交，请稍后重试");
        }
    }

    private WorkspaceFile findSameNameFile(String userId, String spaceType, String workstationId,
                                        String fileName, String excludeFileId) {
        LambdaQueryWrapper<WorkspaceFile> wrapper = new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getUserId, userId)
                .eq(WorkspaceFile::getSpaceType, spaceType)
                .eq(WorkspaceFile::getFileName, fileName)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1");
        if ("WORKSTATION".equals(spaceType)) {
            wrapper.eq(WorkspaceFile::getWorkstationId, workstationId);
        } else {
            wrapper.isNull(WorkspaceFile::getWorkstationId);
        }
        if (StringUtils.hasText(excludeFileId)) {
            wrapper.ne(WorkspaceFile::getFileId, excludeFileId);
        }
        return workspaceFileMapper.selectOne(wrapper);
    }

    private String generateUniqueNodeName(String userId, String spaceType, String workstationId,
                                           String parentId, String originalName) {
        String baseName;
        String extension;
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = originalName.substring(0, dotIdx);
            extension = originalName.substring(dotIdx);
        } else {
            baseName = originalName;
            extension = "";
        }

        for (int i = 1; i <= 100; i++) {
            String candidate = baseName + " (" + i + ")" + extension;
            com.linkwork.model.entity.FileNodeEntity existing = fileNodeService.findSameNameNode(
                    userId, spaceType, workstationId, parentId, candidate);
            if (existing == null) {
                return candidate;
            }
        }
        return baseName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")" + extension;
    }

    private String generateUniqueName(String userId, String spaceType, String workstationId, String originalName) {
        String baseName;
        String extension;
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            baseName = originalName.substring(0, dotIdx);
            extension = originalName.substring(dotIdx);
        } else {
            baseName = originalName;
            extension = "";
        }

        for (int i = 1; i <= 100; i++) {
            String candidate = baseName + " (" + i + ")" + extension;
            WorkspaceFile existing = findSameNameFile(userId, spaceType, workstationId, candidate, null);
            if (existing == null) {
                return candidate;
            }
        }
        return baseName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")" + extension;
    }

    private WorkspaceFile overwriteTargetFile(WorkspaceFile source, WorkspaceFile target) {
        nfsStorageService.copyObject(source.getOssPath(), target.getOssPath());

        String targetParsedPath = target.getParsedOssPath();
        if (StringUtils.hasText(source.getParsedOssPath())) {
            if (!StringUtils.hasText(targetParsedPath)) {
                targetParsedPath = buildParsedPath(target.getOssPath());
            }
            nfsStorageService.copyObject(source.getParsedOssPath(), targetParsedPath);
        } else if (StringUtils.hasText(targetParsedPath)) {
            try {
                nfsStorageService.deleteFile(targetParsedPath);
            } catch (Exception e) {
                log.warn("清理覆盖前解析文件失败: fileId={}, path={}, err={}",
                        target.getFileId(), targetParsedPath, e.getMessage());
            }
            targetParsedPath = null;
        }

        target.setFileSize(source.getFileSize());
        target.setFileType(source.getFileType());
        target.setContentType(source.getContentType());
        target.setFileHash(source.getFileHash());
        target.setParseStatus(source.getParseStatus());
        target.setParsedOssPath(targetParsedPath);
        target.setMemoryIndexStatus("NONE");
        target.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.updateById(target);

        if ("PARSED".equals(target.getParseStatus()) || MEMORY_DIRECT_TYPES.contains(target.getFileType())) {
            triggerMemoryIndex(target);
        }

        return target;
    }

    private String buildTransferDedupKey(String fileId, String userId, String operation,
                                         String targetSpaceType, String targetWorkstationId,
                                         ConflictPolicy policy, String targetParentId) {
        String safeTargetWorkstationId = StringUtils.hasText(targetWorkstationId)
                ? sanitizePathSegment(targetWorkstationId)
                : "-";
        String safeTargetParentId = StringUtils.hasText(targetParentId)
                ? sanitizePathSegment(targetParentId)
                : "root";
        return String.format("%s:%s:%s:%s:%s:%s:%s:%s",
                FILE_TRANSFER_DEDUP_KEY_PREFIX,
                sanitizePathSegment(userId),
                sanitizePathSegment(fileId),
                sanitizePathSegment(operation),
                sanitizePathSegment(targetSpaceType),
                safeTargetWorkstationId,
                policy.name(),
                safeTargetParentId);
    }

    private void validateUpload(MultipartFile file, String spaceType, String workstationId, String userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 100MB");
        }
        validateSpaceType(spaceType, workstationId);
        String ext = getExtension(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext);
        }
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户信息缺失");
        }
    }

    private void validateSpaceType(String spaceType, String workstationId) {
        if (!StringUtils.hasText(spaceType)) {
            throw new IllegalArgumentException("spaceType 不能为空");
        }
        String normalized = spaceType.toUpperCase(Locale.ROOT);
        if (!"USER".equals(normalized) && !"WORKSTATION".equals(normalized)) {
            throw new IllegalArgumentException("spaceType 仅支持 USER 或 WORKSTATION");
        }
        if ("WORKSTATION".equals(normalized) && !StringUtils.hasText(workstationId)) {
            throw new IllegalArgumentException("WORKSTATION 空间必须提供 workstationId");
        }
    }

    private WorkspaceFile findActiveByFileId(String fileId) {
        WorkspaceFile file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getFileId, fileId)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1"));
        if (file == null) {
            throw new ResourceNotFoundException("文件不存在: " + fileId);
        }
        return file;
    }

    private void checkPermission(WorkspaceFile file, String userId) {
        if (!Objects.equals(file.getUserId(), userId)) {
            throw new ForbiddenOperationException("无权限访问该文件");
        }
    }

    public String buildParsedPath(String ossPath) {
        String parsed = ossPath.replace("/original/", "/parsed/");
        int dotIndex = parsed.lastIndexOf('.');
        if (dotIndex > 0) {
            return parsed.substring(0, dotIndex) + ".md";
        }
        return parsed + ".md";
    }

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            throw new IllegalArgumentException("文件名缺少扩展名");
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String buildOssPath(String spaceType, String workstationId, String userId, String fileId, String ext) {
        String normalized = spaceType.toUpperCase(Locale.ROOT);
        String safeUserId = sanitizePathSegment(userId);
        if ("USER".equals(normalized)) {
            return String.format("user-files/%s/original/%s.%s", safeUserId, fileId, ext);
        }
        String safeWorkstationId = sanitizePathSegment(workstationId);
        return String.format("workstation/%s/%s/original/%s.%s", safeWorkstationId, safeUserId, fileId, ext);
    }

    private String sanitizePathSegment(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private String computeSha256(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) > 0) {
                digest.update(buffer, 0, len);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }

    public FileResponse toResponse(WorkspaceFile file) {
        FileResponse response = new FileResponse();
        response.setFileId(file.getFileId());
        response.setFileName(file.getFileName());
        response.setFileSize(file.getFileSize());
        response.setFileType(file.getFileType());
        response.setContentType(file.getContentType());
        response.setSpaceType(file.getSpaceType());
        response.setWorkstationId(file.getWorkstationId());
        response.setParseStatus(file.getParseStatus());
        response.setMemoryIndexStatus(file.getMemoryIndexStatus());
        response.setCreatedAt(file.getCreatedAt());
        return response;
    }
}

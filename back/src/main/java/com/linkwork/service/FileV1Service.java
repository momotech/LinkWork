package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.exception.FileConflictException;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.common.exception.ResourceNotFoundException;
import com.linkwork.config.MemoryConfig;
import com.linkwork.mapper.LinkworkFileMapper;
import com.linkwork.model.dto.FileMentionResponse;
import com.linkwork.model.dto.FileResponse;
import com.linkwork.model.dto.FileTransferRequest;
import com.linkwork.model.dto.MemoryIndexJob;
import com.linkwork.model.entity.FileNodeEntity;
import com.linkwork.model.entity.LinkworkFile;
import com.linkwork.model.enums.ConflictPolicy;
import com.linkwork.agent.storage.core.StorageClient;
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
public class FileV1Service {

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

    private final LinkworkFileMapper linkworkFileMapper;
    private final StorageClient storageClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryConfig memoryConfig;
    private final FileNodeService fileNodeService;

    @Autowired(required = false)
    private MemoryV1Service memoryService;

    // ==================== Upload ====================

    public FileResponse uploadFile(MultipartFile file, String spaceType, String workstationId,
                                   String userId, String conflictPolicyStr, String parentId) {
        validateUpload(file, spaceType, workstationId, userId);
        String normalizedSpace = spaceType.toUpperCase(Locale.ROOT);
        fileNodeService.validateParentId(parentId, userId, normalizedSpace, workstationId);
        ConflictPolicy policy = ConflictPolicy.fromString(conflictPolicyStr);
        String originalName = file.getOriginalFilename();

        FileNodeEntity existingNode = fileNodeService.findSameNameNode(
                userId, normalizedSpace, workstationId, parentId, originalName);
        if (existingNode != null) {
            switch (policy) {
                case REJECT -> {
                    LinkworkFile existingFile = "FILE".equals(existingNode.getEntryType()) && existingNode.getFileId() != null
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
                    LinkworkFile existingFile = findActiveByFileId(existingNode.getFileId());
                    return overwriteUpload(existingFile, file);
                }
                case RENAME -> originalName = generateUniqueNodeName(userId, normalizedSpace, workstationId, parentId, originalName);
            }
        }

        String ext = getExtension(originalName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String ossPath = buildOssPath(normalizedSpace, workstationId, userId, fileId, ext);

        LinkworkFile linkworkFile = new LinkworkFile();
        linkworkFile.setFileId(fileId);
        linkworkFile.setFileName(originalName);
        linkworkFile.setFileSize(file.getSize());
        linkworkFile.setFileType(ext);
        linkworkFile.setContentType(file.getContentType());
        linkworkFile.setSpaceType(normalizedSpace);
        linkworkFile.setWorkstationId(workstationId);
        linkworkFile.setUserId(userId);
        linkworkFile.setOssPath(ossPath);
        linkworkFile.setMemoryIndexStatus("NONE");
        linkworkFile.setParseStatus(PARSE_REQUIRED_TYPES.contains(ext) ? "NONE" : "SKIP");
        linkworkFile.setFileHash(computeSha256(file));
        linkworkFile.setCreatedAt(LocalDateTime.now());
        linkworkFile.setUpdatedAt(LocalDateTime.now());

        try {
            storageClient.uploadToPath(file.getInputStream(), ossPath, file.getSize());
        } catch (IOException e) {
            throw new IllegalStateException("上传文件到存储失败", e);
        }
        linkworkFileMapper.insert(linkworkFile);

        fileNodeService.createFileNode(originalName, normalizedSpace, workstationId, userId, fileId, parentId);

        if ("NONE".equals(linkworkFile.getParseStatus())) {
            linkworkFile.setParseStatus("PARSING");
            linkworkFile.setUpdatedAt(LocalDateTime.now());
            linkworkFileMapper.updateById(linkworkFile);
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, fileId);
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(linkworkFile);
        }

        return toResponse(linkworkFile);
    }

    private FileResponse overwriteUpload(LinkworkFile target, MultipartFile newFile) {
        String ext = getExtension(newFile.getOriginalFilename());
        try {
            storageClient.uploadToPath(newFile.getInputStream(), target.getOssPath(), newFile.getSize());
        } catch (IOException e) {
            throw new IllegalStateException("覆盖上传文件失败", e);
        }

        if (StringUtils.hasText(target.getParsedOssPath())) {
            try {
                storageClient.deleteObject(target.getParsedOssPath());
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
        linkworkFileMapper.updateById(target);

        if (PARSE_REQUIRED_TYPES.contains(ext)) {
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, target.getFileId());
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(target);
        }

        return toResponse(target);
    }

    // ==================== List / Detail ====================

    public Map<String, Object> listFiles(String spaceType, String workstationId, String fileType,
                                         String keyword, Integer page, Integer pageSize, String userId) {
        validateSpaceType(spaceType, workstationId);
        int currentPage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);

        LambdaQueryWrapper<LinkworkFile> wrapper = new LambdaQueryWrapper<LinkworkFile>()
                .eq(LinkworkFile::getUserId, userId)
                .eq(LinkworkFile::getSpaceType, spaceType.toUpperCase(Locale.ROOT))
                .isNull(LinkworkFile::getDeletedAt)
                .orderByDesc(LinkworkFile::getCreatedAt);

        if ("WORKSTATION".equalsIgnoreCase(spaceType)) {
            wrapper.eq(LinkworkFile::getWorkstationId, workstationId);
        }
        if (StringUtils.hasText(fileType)) {
            wrapper.eq(LinkworkFile::getFileType, fileType.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(LinkworkFile::getFileName, keyword.trim());
        }

        Page<LinkworkFile> result = linkworkFileMapper.selectPage(new Page<>(currentPage, size), wrapper);
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
        LinkworkFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        return toResponse(file);
    }

    public DownloadInfo getDownloadInfo(String fileId, String userId) {
        LinkworkFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        return new DownloadInfo(file.getOssPath(), file.getFileName(), file.getContentType());
    }

    public record DownloadInfo(String storagePath, String fileName, String contentType) {}

    // ==================== Delete ====================

    public void deleteFile(String fileId, String userId) {
        LinkworkFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);

        file.setDeletedAt(LocalDateTime.now());
        file.setUpdatedAt(LocalDateTime.now());
        linkworkFileMapper.updateById(file);

        FileNodeEntity node = fileNodeService.findByFileId(
                fileId, userId, file.getSpaceType(), file.getWorkstationId());
        if (node != null) {
            fileNodeService.deleteNode(node.getNodeId(), userId);
        }

        try { storageClient.deleteObject(file.getOssPath()); }
        catch (Exception e) { log.warn("删除原文件失败: fileId={}, err={}", fileId, e.getMessage()); }

        if (StringUtils.hasText(file.getParsedOssPath())) {
            try { storageClient.deleteObject(file.getParsedOssPath()); }
            catch (Exception e) { log.warn("删除解析文件失败: fileId={}, err={}", fileId, e.getMessage()); }
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

    // ==================== Replace ====================

    public FileResponse replaceFile(String fileId, MultipartFile newFile, String userId) {
        LinkworkFile file = findActiveByFileId(fileId);
        checkPermission(file, userId);
        validateUpload(newFile, file.getSpaceType(), file.getWorkstationId(), userId);

        String ext = getExtension(newFile.getOriginalFilename());
        try {
            storageClient.uploadToPath(newFile.getInputStream(), file.getOssPath(), newFile.getSize());
        } catch (IOException e) {
            throw new IllegalStateException("覆盖上传文件失败", e);
        }

        if (StringUtils.hasText(file.getParsedOssPath())) {
            try { storageClient.deleteObject(file.getParsedOssPath()); }
            catch (Exception e) { log.warn("删除旧解析文件失败: fileId={}, err={}", fileId, e.getMessage()); }
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
        linkworkFileMapper.updateById(file);

        if (PARSE_REQUIRED_TYPES.contains(ext)) {
            redisTemplate.opsForList().leftPush(FILE_PARSE_QUEUE_KEY, file.getFileId());
        } else if (MEMORY_DIRECT_TYPES.contains(ext)) {
            triggerMemoryIndex(file);
        }

        return toResponse(file);
    }

    // ==================== Copy ====================

    public FileResponse copyFile(String fileId, FileTransferRequest request, String userId) {
        LinkworkFile source = findActiveByFileId(fileId);
        checkPermission(source, userId);
        validateSpaceType(request.getTargetSpaceType(), request.getTargetWorkstationId());
        String targetSpaceType = request.getTargetSpaceType().toUpperCase(Locale.ROOT);
        String targetParentId = request.getTargetParentId();
        fileNodeService.validateParentId(targetParentId, userId, targetSpaceType, request.getTargetWorkstationId());

        ConflictPolicy policy = request.resolveConflictPolicy();
        FileNodeEntity conflictNode = fileNodeService.findSameNameNode(
                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, source.getFileName());
        String targetFileName = source.getFileName();

        if (conflictNode != null) {
            switch (policy) {
                case REJECT -> {
                    LinkworkFile conflictFile = "FILE".equals(conflictNode.getEntryType()) && conflictNode.getFileId() != null
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
                    LinkworkFile conflictFile = findActiveByFileId(conflictNode.getFileId());
                    acquireTransferDedup(source.getFileId(), userId, "copy", targetSpaceType,
                            request.getTargetWorkstationId(), policy, targetParentId);
                    return toResponse(overwriteTargetFile(source, conflictFile));
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        FileNodeEntity nnc = fileNodeService.findSameNameNode(
                                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, request.getNewName());
                        if (nnc != null) {
                            LinkworkFile cf = "FILE".equals(nnc.getEntryType()) && nnc.getFileId() != null
                                    ? findActiveByFileId(nnc.getFileId()) : null;
                            throw new FileConflictException("目标目录已存在同名文件",
                                    nnc.getFileId() != null ? nnc.getFileId() : nnc.getNodeId(),
                                    nnc.getName(), nnc.getEntryType(),
                                    cf != null ? cf.getFileSize() : null, nnc.getUpdatedAt());
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
        storageClient.copyObject(source.getOssPath(), targetOssPath);

        String targetParsedPath = null;
        if (StringUtils.hasText(source.getParsedOssPath())) {
            targetParsedPath = buildParsedPath(targetOssPath);
            storageClient.copyObject(source.getParsedOssPath(), targetParsedPath);
        }

        LinkworkFile copied = new LinkworkFile();
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
            linkworkFileMapper.insert(copied);
        } catch (Exception e) {
            try { storageClient.deleteObject(targetOssPath); } catch (Exception ignored) {}
            if (targetParsedPath != null) {
                try { storageClient.deleteObject(targetParsedPath); } catch (Exception ignored) {}
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

    // ==================== Move ====================

    public FileResponse moveFile(String fileId, FileTransferRequest request, String userId) {
        LinkworkFile source = findActiveByFileId(fileId);
        checkPermission(source, userId);
        validateSpaceType(request.getTargetSpaceType(), request.getTargetWorkstationId());
        String targetSpaceType = request.getTargetSpaceType().toUpperCase(Locale.ROOT);
        String targetParentId = request.getTargetParentId();
        fileNodeService.validateParentId(targetParentId, userId, targetSpaceType, request.getTargetWorkstationId());

        ConflictPolicy policy = request.resolveConflictPolicy();

        FileNodeEntity sourceNode = fileNodeService.findByFileId(
                fileId, userId, source.getSpaceType(), source.getWorkstationId());
        FileNodeEntity conflictNode = fileNodeService.findSameNameNode(
                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, source.getFileName());
        if (conflictNode != null && sourceNode != null && conflictNode.getNodeId().equals(sourceNode.getNodeId())) {
            conflictNode = null;
        }

        if (conflictNode != null) {
            switch (policy) {
                case REJECT -> {
                    LinkworkFile cf = "FILE".equals(conflictNode.getEntryType()) && conflictNode.getFileId() != null
                            ? findActiveByFileId(conflictNode.getFileId()) : null;
                    throw new FileConflictException("目标目录已存在同名文件",
                            conflictNode.getFileId() != null ? conflictNode.getFileId() : conflictNode.getNodeId(),
                            conflictNode.getName(), conflictNode.getEntryType(),
                            cf != null ? cf.getFileSize() : null, conflictNode.getUpdatedAt());
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        FileNodeEntity nnc = fileNodeService.findSameNameNode(
                                userId, targetSpaceType, request.getTargetWorkstationId(), targetParentId, request.getNewName());
                        if (nnc != null && (sourceNode == null || !nnc.getNodeId().equals(sourceNode.getNodeId()))) {
                            LinkworkFile ncf = "FILE".equals(nnc.getEntryType()) && nnc.getFileId() != null
                                    ? findActiveByFileId(nnc.getFileId()) : null;
                            throw new FileConflictException("目标目录已存在同名文件",
                                    nnc.getFileId() != null ? nnc.getFileId() : nnc.getNodeId(),
                                    nnc.getName(), nnc.getEntryType(),
                                    ncf != null ? ncf.getFileSize() : null, nnc.getUpdatedAt());
                        }
                    }
                }
                default -> {}
            }
        }

        acquireTransferDedup(source.getFileId(), userId, "move", targetSpaceType,
                request.getTargetWorkstationId(), policy, targetParentId);

        if (conflictNode != null) {
            switch (policy) {
                case OVERWRITE -> {
                    if ("DIR".equals(conflictNode.getEntryType())) {
                        throw new IllegalArgumentException("无法用文件覆盖目录");
                    }
                    LinkworkFile conflictFile = findActiveByFileId(conflictNode.getFileId());
                    conflictFile.setDeletedAt(LocalDateTime.now());
                    conflictFile.setUpdatedAt(LocalDateTime.now());
                    linkworkFileMapper.updateById(conflictFile);
                    fileNodeService.deleteNode(conflictNode.getNodeId(), userId);
                    try {
                        storageClient.deleteObject(conflictFile.getOssPath());
                        if (StringUtils.hasText(conflictFile.getParsedOssPath())) {
                            storageClient.deleteObject(conflictFile.getParsedOssPath());
                        }
                    } catch (Exception e) {
                        log.warn("清理被覆盖文件失败: fileId={}, err={}", conflictFile.getFileId(), e.getMessage());
                    }
                }
                case RENAME -> {
                    if (StringUtils.hasText(request.getNewName())) {
                        source.setFileName(request.getNewName());
                    } else {
                        source.setFileName(generateUniqueNodeName(userId, targetSpaceType,
                                request.getTargetWorkstationId(), targetParentId, source.getFileName()));
                    }
                }
                default -> {}
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
            storageClient.copyObject(oldOssPath, targetOssPath);
            if (StringUtils.hasText(oldParsedPath)) {
                storageClient.copyObject(oldParsedPath, targetParsedPath);
            }
        } else {
            targetParsedPath = oldParsedPath;
        }

        source.setSpaceType(targetSpaceType);
        source.setWorkstationId("WORKSTATION".equals(targetSpaceType) ? request.getTargetWorkstationId() : null);
        source.setOssPath(targetOssPath);
        source.setParsedOssPath(targetParsedPath);
        source.setUpdatedAt(LocalDateTime.now());
        source.setMemoryIndexStatus("NONE");
        linkworkFileMapper.updateById(source);

        if (sourceNode != null) {
            sourceNode.setParentId(targetParentId);
            sourceNode.setSpaceType(targetSpaceType);
            sourceNode.setWorkstationId("WORKSTATION".equals(targetSpaceType) ? request.getTargetWorkstationId() : null);
            sourceNode.setName(source.getFileName());
            sourceNode.setUpdatedAt(LocalDateTime.now());
            fileNodeService.updateNode(sourceNode);
        }

        if (storagePathChanged) {
            try { storageClient.deleteObject(oldOssPath); }
            catch (Exception e) { log.warn("删除旧原文件失败: fileId={}, err={}", fileId, e.getMessage()); }
            if (StringUtils.hasText(oldParsedPath) && !Objects.equals(oldParsedPath, targetParsedPath)) {
                try { storageClient.deleteObject(oldParsedPath); }
                catch (Exception e) { log.warn("删除旧解析文件失败: fileId={}, err={}", fileId, e.getMessage()); }
            }
        }

        if (memoryService != null) {
            try {
                memoryService.deleteSource(
                        "WORKSTATION".equals(oldSpaceType) ? oldWorkstationId : null,
                        source.getUserId(), oldOssPath);
            } catch (Exception e) { log.warn("清理旧Memory索引失败: {}", e.getMessage()); }
        }

        if ("PARSED".equals(source.getParseStatus()) || MEMORY_DIRECT_TYPES.contains(source.getFileType())) {
            triggerMemoryIndex(source);
        }

        return toResponse(source);
    }

    // ==================== Mention ====================

    public List<FileMentionResponse> mentionFiles(String workstationId, String keyword, String userId) {
        List<LinkworkFile> wsFiles = listBySpaceForMention(userId, "WORKSTATION", workstationId, keyword);
        List<LinkworkFile> userFiles = listBySpaceForMention(userId, "USER", null, keyword);
        return Stream.concat(wsFiles.stream(), userFiles.stream())
                .limit(50)
                .map(this::toMentionResponse)
                .toList();
    }

    // ==================== Memory Index ====================

    public void triggerMemoryIndex(LinkworkFile file) {
        if (memoryService == null || file == null) return;

        String fileType = file.getFileType();
        String objectName = null;
        if (MEMORY_DIRECT_TYPES.contains(fileType)) {
            objectName = file.getOssPath();
        } else if (PARSE_REQUIRED_TYPES.contains(fileType) && "PARSED".equals(file.getParseStatus())) {
            objectName = file.getParsedOssPath();
        } else if (MEMORY_SKIP_TYPES.contains(fileType)) {
            file.setMemoryIndexStatus("SKIP");
            file.setUpdatedAt(LocalDateTime.now());
            linkworkFileMapper.updateById(file);
            return;
        } else {
            return;
        }

        if (!StringUtils.hasText(objectName)) return;

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
            linkworkFileMapper.updateById(file);
        } catch (Exception e) {
            throw new IllegalStateException("触发 Memory 索引失败", e);
        }
    }

    // ==================== Helpers ====================

    public String buildParsedPath(String ossPath) {
        String parsed = ossPath.replace("/original/", "/parsed/");
        int dotIndex = parsed.lastIndexOf('.');
        if (dotIndex > 0) {
            return parsed.substring(0, dotIndex) + ".md";
        }
        return parsed + ".md";
    }

    public FileResponse toResponse(LinkworkFile file) {
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

    private List<LinkworkFile> listBySpaceForMention(String userId, String spaceType, String workstationId, String keyword) {
        LambdaQueryWrapper<LinkworkFile> wrapper = new LambdaQueryWrapper<LinkworkFile>()
                .eq(LinkworkFile::getUserId, userId)
                .eq(LinkworkFile::getSpaceType, spaceType)
                .isNull(LinkworkFile::getDeletedAt)
                .orderByDesc(LinkworkFile::getCreatedAt)
                .last("limit 50");
        if (StringUtils.hasText(workstationId)) wrapper.eq(LinkworkFile::getWorkstationId, workstationId);
        if (StringUtils.hasText(keyword)) wrapper.like(LinkworkFile::getFileName, keyword.trim());
        return linkworkFileMapper.selectList(wrapper);
    }

    private FileMentionResponse toMentionResponse(LinkworkFile file) {
        FileMentionResponse r = new FileMentionResponse();
        r.setFileId(file.getFileId());
        r.setFileName(file.getFileName());
        r.setFileType(file.getFileType());
        r.setFileSize(file.getFileSize());
        r.setSpaceType(file.getSpaceType());
        r.setWorkstationId(file.getWorkstationId());
        r.setCreatedAt(file.getCreatedAt());
        return r;
    }

    private LinkworkFile overwriteTargetFile(LinkworkFile source, LinkworkFile target) {
        storageClient.copyObject(source.getOssPath(), target.getOssPath());
        String targetParsedPath = target.getParsedOssPath();
        if (StringUtils.hasText(source.getParsedOssPath())) {
            if (!StringUtils.hasText(targetParsedPath)) targetParsedPath = buildParsedPath(target.getOssPath());
            storageClient.copyObject(source.getParsedOssPath(), targetParsedPath);
        } else if (StringUtils.hasText(targetParsedPath)) {
            try { storageClient.deleteObject(targetParsedPath); } catch (Exception e) { log.warn("清理覆盖前解析文件失败: {}", e.getMessage()); }
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
        linkworkFileMapper.updateById(target);
        if ("PARSED".equals(target.getParseStatus()) || MEMORY_DIRECT_TYPES.contains(target.getFileType())) {
            triggerMemoryIndex(target);
        }
        return target;
    }

    private void acquireTransferDedup(String fileId, String userId, String operation,
                                      String targetSpaceType, String targetWorkstationId,
                                      ConflictPolicy policy, String targetParentId) {
        String key = String.format("%s:%s:%s:%s:%s:%s:%s:%s",
                FILE_TRANSFER_DEDUP_KEY_PREFIX, san(userId), san(fileId), san(operation),
                san(targetSpaceType), StringUtils.hasText(targetWorkstationId) ? san(targetWorkstationId) : "-",
                policy.name(), StringUtils.hasText(targetParentId) ? san(targetParentId) : "root");
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", FILE_TRANSFER_DEDUP_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("重复提交，请稍后重试");
        }
    }

    private String generateUniqueNodeName(String userId, String spaceType, String workstationId,
                                           String parentId, String originalName) {
        String baseName;
        String extension;
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) { baseName = originalName.substring(0, dotIdx); extension = originalName.substring(dotIdx); }
        else { baseName = originalName; extension = ""; }

        for (int i = 1; i <= 100; i++) {
            String candidate = baseName + " (" + i + ")" + extension;
            if (fileNodeService.findSameNameNode(userId, spaceType, workstationId, parentId, candidate) == null) {
                return candidate;
            }
        }
        return baseName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")" + extension;
    }

    private LinkworkFile findActiveByFileId(String fileId) {
        LinkworkFile file = linkworkFileMapper.selectOne(new LambdaQueryWrapper<LinkworkFile>()
                .eq(LinkworkFile::getFileId, fileId)
                .isNull(LinkworkFile::getDeletedAt)
                .last("limit 1"));
        if (file == null) throw new ResourceNotFoundException("文件不存在: " + fileId);
        return file;
    }

    private void checkPermission(LinkworkFile file, String userId) {
        if (!Objects.equals(file.getUserId(), userId)) {
            throw new ForbiddenOperationException("无权限访问该文件");
        }
    }

    private void validateUpload(MultipartFile file, String spaceType, String workstationId, String userId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("文件大小不能超过 100MB");
        validateSpaceType(spaceType, workstationId);
        String ext = getExtension(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(ext)) throw new IllegalArgumentException("不支持的文件类型: " + ext);
        if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("用户信息缺失");
    }

    private void validateSpaceType(String spaceType, String workstationId) {
        if (!StringUtils.hasText(spaceType)) throw new IllegalArgumentException("spaceType 不能为空");
        String n = spaceType.toUpperCase(Locale.ROOT);
        if (!"USER".equals(n) && !"WORKSTATION".equals(n)) throw new IllegalArgumentException("spaceType 仅支持 USER 或 WORKSTATION");
        if ("WORKSTATION".equals(n) && !StringUtils.hasText(workstationId)) throw new IllegalArgumentException("WORKSTATION 空间必须提供 workstationId");
    }

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) throw new IllegalArgumentException("文件名缺少扩展名");
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String buildOssPath(String spaceType, String workstationId, String userId, String fileId, String ext) {
        String n = spaceType.toUpperCase(Locale.ROOT);
        if ("USER".equals(n)) return String.format("user-files/%s/original/%s.%s", san(userId), fileId, ext);
        return String.format("workstation/%s/%s/original/%s.%s", san(workstationId), san(userId), fileId, ext);
    }

    private String san(String s) { return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_.-]", "_"); }

    private String computeSha256(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) digest.update(buf, 0, len);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }
}

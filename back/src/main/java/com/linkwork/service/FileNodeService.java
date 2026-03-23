package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.common.FileConflictException;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.mapper.FileNodeMapper;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.model.dto.CreateFolderRequest;
import com.linkwork.model.dto.FileNodeResponse;
import com.linkwork.model.entity.FileNodeEntity;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.service.memory.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileNodeService {

    private static final int NODE_NAME_MAX_LENGTH = 512;
    private static final String DELETED_NAME_MARKER = "__deleted__";

    private final FileNodeMapper fileNodeMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final NfsStorageService nfsStorageService;

    @Autowired(required = false)
    private MemoryService memoryService;

    public FileNodeResponse createFolder(CreateFolderRequest request, String userId) {
        validateSpaceType(request.getSpaceType(), request.getWorkstationId());
        String normalizedSpace = request.getSpaceType().toUpperCase(Locale.ROOT);

        if (!StringUtils.hasText(request.getName()) || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("目录名称不能为空");
        }
        String folderName = request.getName().trim();

        validateParentId(request.getParentId(), userId, normalizedSpace, request.getWorkstationId());

        FileNodeEntity existing = findSameNameNode(userId, normalizedSpace,
                request.getWorkstationId(), request.getParentId(), folderName);
        if (existing != null) {
            throw new FileConflictException("目标目录已存在同名节点",
                    existing.getFileId() != null ? existing.getFileId() : existing.getNodeId(),
                    existing.getName(), existing.getEntryType(),
                    null, existing.getUpdatedAt());
        }

        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(request.getParentId());
        node.setEntryType("DIR");
        node.setName(folderName);
        node.setSpaceType(normalizedSpace);
        node.setWorkstationId("WORKSTATION".equals(normalizedSpace) ? request.getWorkstationId() : null);
        node.setUserId(userId);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());

        fileNodeMapper.insert(node);
        return toResponse(node, false);
    }

    public List<FileNodeResponse> listChildren(String spaceType, String workstationId,
                                                String parentId, String userId) {
        validateSpaceType(spaceType, workstationId);
        String normalizedSpace = spaceType.toUpperCase(Locale.ROOT);

        LambdaQueryWrapper<FileNodeEntity> wrapper = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, normalizedSpace)
                .isNull(FileNodeEntity::getDeletedAt)
                .orderByAsc(FileNodeEntity::getEntryType)
                .orderByAsc(FileNodeEntity::getName);

        if ("WORKSTATION".equals(normalizedSpace)) {
            wrapper.eq(FileNodeEntity::getWorkstationId, workstationId);
        } else {
            wrapper.isNull(FileNodeEntity::getWorkstationId);
        }

        if (StringUtils.hasText(parentId)) {
            wrapper.eq(FileNodeEntity::getParentId, parentId);
        } else {
            wrapper.isNull(FileNodeEntity::getParentId);
        }

        List<FileNodeEntity> nodes = fileNodeMapper.selectList(wrapper);

        Set<String> dirNodeIds = nodes.stream()
                .filter(n -> "DIR".equals(n.getEntryType()))
                .map(FileNodeEntity::getNodeId)
                .collect(Collectors.toSet());

        Set<String> dirsWithChildren = new HashSet<>();
        if (!dirNodeIds.isEmpty()) {
            LambdaQueryWrapper<FileNodeEntity> childCheck = new LambdaQueryWrapper<FileNodeEntity>()
                    .in(FileNodeEntity::getParentId, dirNodeIds)
                    .isNull(FileNodeEntity::getDeletedAt)
                    .select(FileNodeEntity::getParentId)
                    .groupBy(FileNodeEntity::getParentId);
            List<FileNodeEntity> childResults = fileNodeMapper.selectList(childCheck);
            childResults.forEach(c -> dirsWithChildren.add(c.getParentId()));
        }

        Set<String> fileIds = nodes.stream()
                .filter(n -> "FILE".equals(n.getEntryType()) && StringUtils.hasText(n.getFileId()))
                .map(FileNodeEntity::getFileId)
                .collect(Collectors.toSet());

        Map<String, WorkspaceFile> fileMap = new HashMap<>();
        if (!fileIds.isEmpty()) {
            LambdaQueryWrapper<WorkspaceFile> fileWrapper = new LambdaQueryWrapper<WorkspaceFile>()
                    .in(WorkspaceFile::getFileId, fileIds)
                    .isNull(WorkspaceFile::getDeletedAt);
            workspaceFileMapper.selectList(fileWrapper)
                    .forEach(f -> fileMap.put(f.getFileId(), f));
        }

        return nodes.stream().map(node -> {
            boolean hasChildren = dirsWithChildren.contains(node.getNodeId());
            FileNodeResponse resp = toResponse(node, hasChildren);
            if ("FILE".equals(node.getEntryType()) && node.getFileId() != null) {
                WorkspaceFile rf = fileMap.get(node.getFileId());
                if (rf != null) {
                    resp.setFileSize(rf.getFileSize());
                    resp.setFileType(rf.getFileType());
                    resp.setParseStatus(rf.getParseStatus());
                    resp.setMemoryIndexStatus(rf.getMemoryIndexStatus());
                }
            }
            return resp;
        }).toList();
    }

    public FileNodeEntity createFileNode(String fileName, String spaceType, String workstationId,
                                          String userId, String fileId, String parentId) {
        validateParentId(parentId, userId, spaceType, workstationId);

        FileNodeEntity node = new FileNodeEntity();
        node.setNodeId(UUID.randomUUID().toString().replace("-", ""));
        node.setParentId(parentId);
        node.setEntryType("FILE");
        node.setName(fileName);
        node.setSpaceType(spaceType.toUpperCase(Locale.ROOT));
        node.setWorkstationId("WORKSTATION".equals(spaceType.toUpperCase(Locale.ROOT)) ? workstationId : null);
        node.setUserId(userId);
        node.setFileId(fileId);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.insert(node);
        return node;
    }

    public void renameNode(String nodeId, String newName, String userId) {
        FileNodeEntity node = findActiveNode(nodeId);
        if (!Objects.equals(node.getUserId(), userId)) {
            throw new ForbiddenOperationException("无权限操作该节点");
        }
        if (!StringUtils.hasText(newName) || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String trimmedName = newName.trim();

        FileNodeEntity conflict = findSameNameNode(userId, node.getSpaceType(),
                node.getWorkstationId(), node.getParentId(), trimmedName);
        if (conflict != null && !conflict.getNodeId().equals(nodeId)) {
            throw new FileConflictException("目标目录已存在同名节点",
                    conflict.getFileId() != null ? conflict.getFileId() : conflict.getNodeId(),
                    conflict.getName(), conflict.getEntryType(),
                    null, conflict.getUpdatedAt());
        }

        node.setName(trimmedName);
        node.setUpdatedAt(LocalDateTime.now());
        fileNodeMapper.updateById(node);

        if ("FILE".equals(node.getEntryType()) && StringUtils.hasText(node.getFileId())) {
            WorkspaceFile file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getFileId, node.getFileId())
                    .isNull(WorkspaceFile::getDeletedAt)
                    .last("limit 1"));
            if (file != null) {
                file.setFileName(trimmedName);
                file.setUpdatedAt(LocalDateTime.now());
                workspaceFileMapper.updateById(file);
            }
        }
    }

    public void deleteNode(String nodeId, String userId) {
        FileNodeEntity node = findActiveNode(nodeId);
        if (!Objects.equals(node.getUserId(), userId)) {
            throw new ForbiddenOperationException("无权限操作该节点");
        }
        softDeleteRecursive(node);
    }

    public void updateNode(FileNodeEntity node) {
        fileNodeMapper.updateById(node);
    }

    public FileNodeEntity findByFileId(String fileId) {
        return findByFileId(fileId, null, null, null);
    }

    public FileNodeEntity findByFileId(String fileId, String userId, String spaceType, String workstationId) {
        LambdaQueryWrapper<FileNodeEntity> wrapper = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getFileId, fileId)
                .isNull(FileNodeEntity::getDeletedAt)
                .orderByDesc(FileNodeEntity::getUpdatedAt)
                .last("limit 1");

        if (StringUtils.hasText(userId)) {
            wrapper.eq(FileNodeEntity::getUserId, userId);
        }

        if (StringUtils.hasText(spaceType)) {
            String normalized = spaceType.toUpperCase(Locale.ROOT);
            wrapper.eq(FileNodeEntity::getSpaceType, normalized);
            if ("WORKSTATION".equals(normalized) && StringUtils.hasText(workstationId)) {
                wrapper.eq(FileNodeEntity::getWorkstationId, workstationId);
            } else if ("USER".equals(normalized)) {
                wrapper.isNull(FileNodeEntity::getWorkstationId);
            }
        }

        return fileNodeMapper.selectOne(wrapper);
    }

    private void softDeleteRecursive(FileNodeEntity node) {
        LocalDateTime now = LocalDateTime.now();
        node.setDeletedAt(now);
        node.setUpdatedAt(now);
        node.setName(buildDeletedTombstoneName(node.getName(), node.getNodeId()));
        fileNodeMapper.updateById(node);

        if ("FILE".equals(node.getEntryType()) && StringUtils.hasText(node.getFileId())) {
            cleanupFileResources(node.getFileId());
        }

        if ("DIR".equals(node.getEntryType())) {
            List<FileNodeEntity> children = fileNodeMapper.selectList(
                    new LambdaQueryWrapper<FileNodeEntity>()
                            .eq(FileNodeEntity::getParentId, node.getNodeId())
                            .isNull(FileNodeEntity::getDeletedAt));
            for (FileNodeEntity child : children) {
                softDeleteRecursive(child);
            }
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

    private void cleanupFileResources(String fileId) {
        WorkspaceFile file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getFileId, fileId)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1"));
        if (file == null) return;

        file.setDeletedAt(LocalDateTime.now());
        file.setUpdatedAt(LocalDateTime.now());
        workspaceFileMapper.updateById(file);

        try {
            nfsStorageService.deleteFile(file.getOssPath());
        } catch (Exception e) {
            log.warn("目录级联删除原文件失败: fileId={}, path={}, err={}", fileId, file.getOssPath(), e.getMessage());
        }
        if (StringUtils.hasText(file.getParsedOssPath())) {
            try {
                nfsStorageService.deleteFile(file.getParsedOssPath());
            } catch (Exception e) {
                log.warn("目录级联删除解析文件失败: fileId={}, path={}, err={}", fileId, file.getParsedOssPath(), e.getMessage());
            }
        }
        if (memoryService != null) {
            try {
                String source = StringUtils.hasText(file.getParsedOssPath()) ? file.getParsedOssPath() : file.getOssPath();
                memoryService.deleteSource(file.getWorkstationId(), file.getUserId(), source);
            } catch (Exception e) {
                log.warn("目录级联删除 Memory 索引失败: fileId={}, err={}", fileId, e.getMessage());
            }
        }
    }

    /**
     * 校验 parentId 合法性：存在、是目录、同用户、同空间。
     * parentId 为空表示根目录，直接放行。
     */
    public void validateParentId(String parentId, String userId, String spaceType, String workstationId) {
        if (!StringUtils.hasText(parentId)) return;

        FileNodeEntity parent = findActiveNode(parentId);
        if (!"DIR".equals(parent.getEntryType())) {
            throw new IllegalArgumentException("父节点不是目录");
        }
        if (!Objects.equals(parent.getUserId(), userId)) {
            throw new ForbiddenOperationException("无权在该目录下操作");
        }
        String normalizedSpace = spaceType.toUpperCase(Locale.ROOT);
        if (!parent.getSpaceType().equals(normalizedSpace)) {
            throw new IllegalArgumentException("父节点与目标空间类型不匹配");
        }
        String expectedWs = "WORKSTATION".equals(normalizedSpace) ? workstationId : null;
        if (!Objects.equals(parent.getWorkstationId(), expectedWs)) {
            throw new IllegalArgumentException("父节点与目标工作站不匹配");
        }
    }

    FileNodeEntity findSameNameNode(String userId, String spaceType, String workstationId,
                                     String parentId, String name) {
        LambdaQueryWrapper<FileNodeEntity> wrapper = new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getUserId, userId)
                .eq(FileNodeEntity::getSpaceType, spaceType)
                .eq(FileNodeEntity::getName, name)
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1");

        if ("WORKSTATION".equals(spaceType)) {
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

    private FileNodeEntity findActiveNode(String nodeId) {
        FileNodeEntity node = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNodeEntity>()
                .eq(FileNodeEntity::getNodeId, nodeId)
                .isNull(FileNodeEntity::getDeletedAt)
                .last("limit 1"));
        if (node == null) {
            throw new ResourceNotFoundException("节点不存在: " + nodeId);
        }
        return node;
    }

    private FileNodeResponse toResponse(FileNodeEntity node, boolean hasChildren) {
        FileNodeResponse resp = new FileNodeResponse();
        resp.setNodeId(node.getNodeId());
        resp.setParentId(node.getParentId());
        resp.setEntryType(node.getEntryType());
        resp.setName(node.getName());
        resp.setSpaceType(node.getSpaceType());
        resp.setWorkstationId(node.getWorkstationId());
        resp.setFileId(node.getFileId());
        resp.setCreatedAt(node.getCreatedAt());
        resp.setUpdatedAt(node.getUpdatedAt());
        resp.setHasChildren(hasChildren);
        return resp;
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
}

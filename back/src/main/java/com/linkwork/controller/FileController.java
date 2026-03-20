package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.CreateFolderRequest;
import com.linkwork.model.dto.FileMentionResponse;
import com.linkwork.model.dto.FileNodeResponse;
import com.linkwork.model.dto.FileResponse;
import com.linkwork.model.dto.FileSpaceSyncRequest;
import com.linkwork.model.dto.FileSpaceSyncResponse;
import com.linkwork.model.dto.FileTransferRequest;
import com.linkwork.service.FileNodeService;
import com.linkwork.service.FileSpaceSyncService;
import com.linkwork.service.FileService;
import com.linkwork.service.NfsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileNodeService fileNodeService;
    private final FileSpaceSyncService fileSpaceSyncService;
    private final NfsStorageService nfsStorageService;

    @PostMapping("/upload")
    public ApiResponse<FileResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("spaceType") String spaceType,
            @RequestParam(value = "workstationId", required = false) String workstationId,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            @RequestParam(value = "parentId", required = false) String parentId) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.success(fileService.uploadFile(file, spaceType, workstationId, userId, conflictPolicy, parentId));
    }

    @GetMapping("/tree")
    public ApiResponse<List<FileNodeResponse>> listTree(
            @RequestParam("spaceType") String spaceType,
            @RequestParam(value = "workstationId", required = false) String workstationId,
            @RequestParam(value = "parentId", required = false) String parentId) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.success(fileNodeService.listChildren(spaceType, workstationId, parentId, userId));
    }

    @PostMapping("/sync")
    public ApiResponse<FileSpaceSyncResponse> syncSpace(@RequestBody FileSpaceSyncRequest request) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.success(fileSpaceSyncService.syncSpace(userId, request));
    }

    @PostMapping("/folders")
    public ApiResponse<FileNodeResponse> createFolder(@RequestBody CreateFolderRequest request) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.success(fileNodeService.createFolder(request, userId));
    }

    @PutMapping("/nodes/{nodeId}/rename")
    public ApiResponse<Void> renameNode(@PathVariable String nodeId,
                                        @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        fileNodeService.renameNode(nodeId, body.get("name"), userId);
        return ApiResponse.success();
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ApiResponse<Void> deleteNode(@PathVariable String nodeId) {
        String userId = UserContext.getCurrentUserId();
        fileNodeService.deleteNode(nodeId, userId);
        return ApiResponse.success();
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> listFiles(
            @RequestParam("spaceType") String spaceType,
            @RequestParam(value = "workstationId", required = false) String workstationId,
            @RequestParam(value = "fileType", required = false) String fileType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(fileService.listFiles(spaceType, workstationId, fileType, keyword, page, pageSize,
                UserContext.getCurrentUserId()));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileResponse> getFileDetail(@PathVariable String fileId) {
        return ApiResponse.success(fileService.getFileDetail(fileId, UserContext.getCurrentUserId()));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String fileId,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) throws IOException {
        String userId = UserContext.getCurrentUserId();
        FileService.DownloadInfo info = fileService.getDownloadInfo(fileId, userId);

        Path filePath = nfsStorageService.getAbsolutePath(info.storagePath());
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("文件不存在于存储中: " + info.storagePath());
        }

        String encodedFileName = URLEncoder.encode(info.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String contentType = resolveContentType(info.contentType(), info.fileName(), filePath);
        String disposition = inline ? "inline" : "attachment";
        InputStream inputStream = Files.newInputStream(filePath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(Files.size(filePath))
                .body(new InputStreamResource(inputStream));
    }

    private String resolveContentType(String rawContentType, String fileName, Path filePath) {
        if (rawContentType != null && !rawContentType.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(rawContentType)) {
            return rawContentType;
        }
        try {
            String detected = Files.probeContentType(filePath);
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (IOException ignore) {
            // noop, fall through to extension mapping
        }
        String lowerName = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (lowerName.endsWith(".md") || lowerName.endsWith(".txt") || lowerName.endsWith(".log")) {
            return MediaType.TEXT_PLAIN_VALUE;
        }
        if (lowerName.endsWith(".json")) {
            return MediaType.APPLICATION_JSON_VALUE;
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> deleteFile(@PathVariable String fileId) {
        fileService.deleteFile(fileId, UserContext.getCurrentUserId());
        return ApiResponse.success();
    }

    @PutMapping("/{fileId}")
    public ApiResponse<FileResponse> replaceFile(
            @PathVariable String fileId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(fileService.replaceFile(fileId, file, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{fileId}/copy")
    public ApiResponse<FileResponse> copyFile(@PathVariable String fileId,
                                              @RequestBody FileTransferRequest request) {
        return ApiResponse.success(fileService.copyFile(fileId, request, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{fileId}/move")
    public ApiResponse<FileResponse> moveFile(@PathVariable String fileId,
                                              @RequestBody FileTransferRequest request) {
        return ApiResponse.success(fileService.moveFile(fileId, request, UserContext.getCurrentUserId()));
    }

    @GetMapping("/mention")
    public ApiResponse<List<FileMentionResponse>> mentionFiles(
            @RequestParam("workstationId") String workstationId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.success(fileService.mentionFiles(workstationId, keyword, UserContext.getCurrentUserId()));
    }
}

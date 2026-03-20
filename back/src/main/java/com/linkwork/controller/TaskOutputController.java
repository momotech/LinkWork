package com.linkwork.controller;

import com.linkwork.service.NfsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 任务产出物文件下载 — 供前端 WebSocket 事件中的 download_url 使用
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/task-outputs")
@RequiredArgsConstructor
public class TaskOutputController {

    private final NfsStorageService nfsStorageService;
    private static final int ZIP_BUFFER_SIZE = 8192;

    /**
     * 任务产出下载（按 objectName 直取）。
     *
     * 示例：
     * GET /api/v1/task-outputs/file?object=system%2F35%2Flogs%2Fuser-id%2FTSK-xxx%2Freport.md
     */
    @GetMapping("/file")
    public ResponseEntity<Resource> downloadTaskOutputFile(
            @RequestParam("object") String objectName,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) throws IOException {

        String normalizedObject = normalizeObjectName(objectName);
        Path filePath = nfsStorageService.getAbsolutePath(normalizedObject).normalize();
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("任务产出物文件未找到: object={}", normalizedObject);
            return ResponseEntity.notFound().build();
        }

        String fileName = filePath.getFileName().toString();
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String contentType = resolveContentType(fileName, filePath);
        String disposition = inline ? "inline" : "attachment";
        InputStream inputStream = Files.newInputStream(filePath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(Files.size(filePath))
                .body(new InputStreamResource(inputStream));
    }

    @PostMapping(value = "/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> downloadTaskOutputArchive(
            @RequestBody ArchiveRequest request) throws IOException {

        List<ArchiveSourceFile> sourceFiles = prepareArchiveSourceFiles(request);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("items 参数不能为空");
        }

        String archiveFileName = normalizeArchiveFileName(request.fileName());
        String encodedFileName = URLEncoder.encode(archiveFileName, StandardCharsets.UTF_8).replace("+", "%20");

        StreamingResponseBody responseBody = outputStream -> {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
                byte[] buffer = new byte[ZIP_BUFFER_SIZE];
                for (ArchiveSourceFile sourceFile : sourceFiles) {
                    zipOutputStream.putNextEntry(new ZipEntry(sourceFile.entryName()));
                    try (InputStream inputStream = Files.newInputStream(sourceFile.path())) {
                        int readSize;
                        while ((readSize = inputStream.read(buffer)) > 0) {
                            zipOutputStream.write(buffer, 0, readSize);
                        }
                    }
                    zipOutputStream.closeEntry();
                }
                zipOutputStream.finish();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(responseBody);
    }

    private List<ArchiveSourceFile> prepareArchiveSourceFiles(ArchiveRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return List.of();
        }

        List<ArchiveSourceFile> sourceFiles = new ArrayList<>();
        Set<String> usedEntryNames = new HashSet<>();

        for (ArchiveItem item : request.items()) {
            String normalizedObject = normalizeObjectName(item == null ? null : item.object());
            Path sourcePath = nfsStorageService.getAbsolutePath(normalizedObject).normalize();
            if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
                throw new IllegalArgumentException("产出物不存在或不可访问: " + normalizedObject);
            }

            String fallbackName = sourcePath.getFileName().toString();
            String normalizedEntryName = normalizeZipEntryName(item == null ? null : item.name(), fallbackName);
            String dedupedEntryName = ensureUniqueZipEntryName(normalizedEntryName, usedEntryNames);
            sourceFiles.add(new ArchiveSourceFile(sourcePath, dedupedEntryName));
        }

        return sourceFiles;
    }

    private String normalizeArchiveFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = "task-artifacts.zip";
        }
        if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("fileName 参数非法");
        }
        if (!normalized.toLowerCase().endsWith(".zip")) {
            normalized = normalized + ".zip";
        }
        return normalized;
    }

    private String normalizeZipEntryName(String entryName, String fallbackName) {
        String normalized = entryName == null ? "" : entryName.trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = fallbackName;
        }
        normalized = normalized.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!StringUtils.hasText(normalized)) {
            normalized = fallbackName;
        }

        String[] rawSegments = normalized.split("/");
        List<String> safeSegments = new ArrayList<>();
        for (String segment : rawSegments) {
            if (!StringUtils.hasText(segment) || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("name 参数非法");
            }
            safeSegments.add(segment);
        }
        if (safeSegments.isEmpty()) {
            return fallbackName;
        }
        return String.join("/", safeSegments);
    }

    private String ensureUniqueZipEntryName(String entryName, Set<String> usedEntryNames) {
        if (!usedEntryNames.contains(entryName)) {
            usedEntryNames.add(entryName);
            return entryName;
        }

        int dotIndex = entryName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? entryName.substring(0, dotIndex) : entryName;
        String extension = dotIndex > 0 ? entryName.substring(dotIndex) : "";
        int index = 1;
        String candidate = baseName + "(" + index + ")" + extension;
        while (usedEntryNames.contains(candidate)) {
            index++;
            candidate = baseName + "(" + index + ")" + extension;
        }
        usedEntryNames.add(candidate);
        return candidate;
    }

    private String normalizeObjectName(String objectName) {
        String normalized = objectName == null ? "" : objectName.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("object 参数不能为空");
        }
        if (normalized.contains("..") || normalized.contains("\\")) {
            throw new IllegalArgumentException("object 参数非法");
        }
        return normalized;
    }

    private String resolveContentType(String fileName, Path filePath) {
        try {
            String detected = Files.probeContentType(filePath);
            if (StringUtils.hasText(detected)) {
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

    private record ArchiveRequest(String fileName, List<ArchiveItem> items) {}
    private record ArchiveItem(String object, String name) {}
    private record ArchiveSourceFile(Path path, String entryName) {}
}

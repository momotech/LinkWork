package com.linkwork.controller.v1;

import com.linkwork.agent.storage.core.StorageClient;
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
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/v1/task-outputs")
@RequiredArgsConstructor
public class TaskOutputV1Controller {

    private final StorageClient storageClient;
    private static final int ZIP_BUFFER_SIZE = 8192;

    @GetMapping("/file")
    public ResponseEntity<Resource> downloadTaskOutputFile(
            @RequestParam("object") String objectName,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) throws IOException {

        String normalizedObject = normalizeObjectName(objectName);
        Path filePath = storageClient.resolvePath(normalizedObject).normalize();
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
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
            @RequestBody ArchiveRequest request) {

        List<ArchiveSourceFile> sourceFiles = prepareArchiveSourceFiles(request);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("items 参数不能为空");
        }

        String archiveFileName = normalizeArchiveFileName(request.fileName());
        String encodedFileName = URLEncoder.encode(archiveFileName, StandardCharsets.UTF_8).replace("+", "%20");

        StreamingResponseBody responseBody = outputStream -> {
            try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
                byte[] buffer = new byte[ZIP_BUFFER_SIZE];
                for (ArchiveSourceFile sf : sourceFiles) {
                    zos.putNextEntry(new ZipEntry(sf.entryName()));
                    try (InputStream is = Files.newInputStream(sf.path())) {
                        int readSize;
                        while ((readSize = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, readSize);
                        }
                    }
                    zos.closeEntry();
                }
                zos.finish();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(responseBody);
    }

    private List<ArchiveSourceFile> prepareArchiveSourceFiles(ArchiveRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) return List.of();
        List<ArchiveSourceFile> result = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (ArchiveItem item : request.items()) {
            String normalized = normalizeObjectName(item == null ? null : item.object());
            Path sourcePath = storageClient.resolvePath(normalized).normalize();
            if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
                throw new IllegalArgumentException("产出物不存在: " + normalized);
            }
            String fallback = sourcePath.getFileName().toString();
            String entryName = normalizeZipEntryName(item == null ? null : item.name(), fallback);
            entryName = ensureUnique(entryName, usedNames);
            result.add(new ArchiveSourceFile(sourcePath, entryName));
        }
        return result;
    }

    private String normalizeArchiveFileName(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (!StringUtils.hasText(n)) n = "task-artifacts.zip";
        if (n.contains("..") || n.contains("/") || n.contains("\\")) throw new IllegalArgumentException("fileName 参数非法");
        if (!n.toLowerCase().endsWith(".zip")) n = n + ".zip";
        return n;
    }

    private String normalizeZipEntryName(String name, String fallback) {
        String n = name == null ? "" : name.trim();
        if (!StringUtils.hasText(n)) n = fallback;
        n = n.replace("\\", "/");
        while (n.startsWith("/")) n = n.substring(1);
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (!StringUtils.hasText(n)) n = fallback;
        if (n.contains("..")) throw new IllegalArgumentException("name 参数非法");
        return n;
    }

    private String ensureUnique(String name, Set<String> used) {
        if (!used.contains(name)) { used.add(name); return name; }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String c = base + "(" + i + ")" + ext;
            if (!used.contains(c)) { used.add(c); return c; }
        }
    }

    private String normalizeObjectName(String objectName) {
        String n = objectName == null ? "" : objectName.trim();
        while (n.startsWith("/")) n = n.substring(1);
        if (!StringUtils.hasText(n)) throw new IllegalArgumentException("object 参数不能为空");
        if (n.contains("..") || n.contains("\\")) throw new IllegalArgumentException("object 参数非法");
        return n;
    }

    private String resolveContentType(String fileName, Path filePath) {
        try {
            String detected = Files.probeContentType(filePath);
            if (StringUtils.hasText(detected)) return detected;
        } catch (IOException ignore) {}
        String ln = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (ln.endsWith(".pdf")) return MediaType.APPLICATION_PDF_VALUE;
        if (ln.endsWith(".md") || ln.endsWith(".txt") || ln.endsWith(".log")) return MediaType.TEXT_PLAIN_VALUE;
        if (ln.endsWith(".json")) return MediaType.APPLICATION_JSON_VALUE;
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private record ArchiveRequest(String fileName, List<ArchiveItem> items) {}
    private record ArchiveItem(String object, String name) {}
    private record ArchiveSourceFile(Path path, String entryName) {}
}

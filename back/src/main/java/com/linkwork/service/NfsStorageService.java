package com.linkwork.service;

import com.linkwork.agent.storage.core.StorageClient;
import com.linkwork.config.NfsStorageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * NFS 文件存储服务
 *
 * 通过 linkwork-storage-starter 的 StorageClient 覆盖替换原有文件系统实现，
 * 对上层保持原方法签名不变。
 */
@Slf4j
@Service
public class NfsStorageService {

    private final NfsStorageConfig config;
    private final StorageClient storageClient;

    public NfsStorageService(NfsStorageConfig config, StorageClient storageClient) {
        this.config = config;
        this.storageClient = storageClient;
    }

    public boolean isConfigured() {
        return storageClient.supportsFileStorageOps() && storageClient.isConfigured();
    }

    /**
     * 获取 objectName 对应的绝对路径
     */
    public Path getAbsolutePath(String objectName) {
        if (storageClient.supportsFileStorageOps()) {
            return storageClient.resolvePath(objectName);
        }
        return config.resolve(objectName);
    }

    /**
     * 上传 MultipartFile 到指定相对路径
     */
    public String uploadFileToPath(MultipartFile file, String objectName) throws IOException {
        ensureConfigured();
        try (InputStream in = file.getInputStream()) {
            String stored = storageClient.uploadToPath(in, objectName, file.getSize());
            log.info("文件上传成功: objectName={}, filename={}, size={}", stored, file.getOriginalFilename(), file.getSize());
            return stored;
        }
    }

    /**
     * 上传文本内容到指定相对路径
     */
    public String uploadTextToPath(String content, String objectName) {
        ensureConfigured();
        String stored = storageClient.uploadText(content, objectName);
        log.info("文本上传成功: objectName={}, size={}", stored, content != null ? content.length() : 0);
        return stored;
    }

    /**
     * 下载文件到临时文件
     */
    public Path downloadToTempFile(String objectName) throws IOException {
        ensureConfigured();
        Path tmp = storageClient.downloadToTempFile(objectName);
        log.info("下载 NFS 文件到临时文件: objectName={}", objectName);
        return tmp;
    }

    /**
     * 复制文件
     */
    public void copyObject(String sourceObjectName, String destObjectName) {
        ensureConfigured();
        storageClient.copyObject(sourceObjectName, destObjectName);
        log.info("复制 NFS 文件: source={}, dest={}", sourceObjectName, destObjectName);
    }

    /**
     * 检查文件是否存在
     */
    public boolean doesObjectExist(String objectName) {
        ensureConfigured();
        return storageClient.objectExists(objectName);
    }

    /**
     * 列出指定前缀（目录）下的所有文件
     */
    public List<String> listObjects(String prefix) {
        if (!isConfigured()) {
            return List.of();
        }
        return storageClient.listObjects(prefix);
    }

    /**
     * 删除文件
     */
    public void deleteFile(String objectName) {
        if (!isConfigured()) {
            return;
        }
        storageClient.deleteObject(objectName);
    }

    /**
     * 上传文本内容到按日期分目录的路径
     */
    public String uploadText(String content, String directory, String filename) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = directory + "/" + datePath + "/" + filename;
        return uploadTextToPath(content, objectName);
    }

    /**
     * 上传 MultipartFile 到按日期分目录的路径
     */
    public String uploadFile(MultipartFile file, String directory) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = directory + "/" + datePath + "/" + UUID.randomUUID() + extension;
        return uploadFileToPath(file, objectName);
    }

    /**
     * 生成后端代理下载 URL
     */
    public String buildDownloadUrl(String fileId) {
        return config.getDownloadBaseUrl() + "/" + fileId + "/download";
    }

    /**
     * 生成任务产出物下载 URL（按 objectName 直连后端代理）。
     */
    public String buildTaskOutputDownloadUrl(String objectName) {
        String encodedObject = URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20");
        return config.getTaskOutputBaseUrl() + "/file?object=" + encodedObject;
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("NFS 存储未配置");
        }
    }
}

package com.linkwork.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "nfs.storage")
public class NfsStorageConfig {

    /** NFS 本地挂载根路径，后续更换 NFS 服务器只需重新 mount + 改此值 */
    private String basePath = "/mnt/oss/robot-agent-files";

    /** 后端文件下载 API 的 URL 前缀 */
    private String downloadBaseUrl = "/api/v1/files";

    /** 任务产出物下载 API 的 URL 前缀 */
    private String taskOutputBaseUrl = "/api/v1/task-outputs";

    @PostConstruct
    public void validate() {
        Path base = Path.of(basePath);
        if (Files.isDirectory(base)) {
            log.info("NFS storage configured: basePath={}", basePath);
        } else {
            log.warn("NFS storage basePath does not exist or is not a directory: {}", basePath);
        }
    }

    public Path resolve(String relativePath) {
        return Path.of(basePath).resolve(relativePath);
    }
}

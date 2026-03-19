package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "nfs.storage")
public class NfsStorageConfig {

    private String basePath = "/mnt/oss/robot-agent-files";
    private String downloadBaseUrl = "/api/v1/files";
    private String taskOutputBaseUrl = "/api/v1/task-outputs";

    public String buildDownloadUrl(String fileId) {
        return downloadBaseUrl + "/" + fileId + "/download";
    }

    public String buildTaskOutputDownloadUrl(String objectName) {
        String encoded = java.net.URLEncoder.encode(objectName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return taskOutputBaseUrl + "/file?object=" + encoded;
    }
}

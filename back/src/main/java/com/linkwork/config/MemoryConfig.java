package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryConfig {

    private boolean enabled = true;
    private Milvus milvus = new Milvus();
    private Embedding embedding = new Embedding();
    private Index index = new Index();
    private String ossMountPath = "/data/oss";

    @Data
    public static class Milvus {
        private String uri = "http://milvus:19530";
        private String token = "";
    }

    @Data
    public static class Embedding {
        private String model = "text-embedding-3-small";
        private int dimension = 1536;
    }

    @Data
    public static class Index {
        private int maxChunkSize = 1500;
        private int overlapLines = 2;
        private String queueKey = "memory:index:jobs";
    }

    public String collectionName(String workstationId, String userId) {
        return "memory_" + sanitize(workstationId) + "_" + sanitize(userId);
    }

    public String userCollectionName(String userId) {
        return "memory_user_" + sanitize(userId);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}

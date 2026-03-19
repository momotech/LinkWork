package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryIndexJob {

    public enum JobType {
        FILE_UPLOAD,
        MEMORY_WRITEBACK,
        SESSION_SUMMARY
    }

    private String jobId;
    private String workstationId;
    private String userId;
    private JobType jobType;
    private String filePath;
    private String content;
    private String source;
    private String fileType;
    private String storageType;
    private String objectName;
    private String collectionName;
}

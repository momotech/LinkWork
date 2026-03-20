package com.linkwork.common;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Getter
public class FileConflictException extends RuntimeException {

    private final String conflictType;
    private final Map<String, Object> existingNode;

    public FileConflictException(String message, String fileId, String name,
                                 String entryType, Long fileSize, LocalDateTime updatedAt) {
        super(message);
        this.conflictType = "NAME_EXISTS";
        this.existingNode = new HashMap<>();
        this.existingNode.put("fileId", fileId);
        this.existingNode.put("name", name);
        this.existingNode.put("entryType", entryType);
        if (fileSize != null) {
            this.existingNode.put("fileSize", fileSize);
        }
        if (updatedAt != null) {
            this.existingNode.put("updatedAt", updatedAt.toString());
        }
    }

    public Map<String, Object> toResponseData() {
        Map<String, Object> data = new HashMap<>();
        data.put("conflictType", conflictType);
        data.put("existingNode", existingNode);
        return data;
    }
}

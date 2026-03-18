package com.linkwork.model.dto;

import com.linkwork.model.enums.ConflictPolicy;
import lombok.Data;

@Data
public class FileTransferRequest {
    private String targetSpaceType;
    private String targetWorkstationId;
    private String targetParentId;
    private String conflictPolicy;
    private String newName;

    public ConflictPolicy resolveConflictPolicy() {
        if (conflictPolicy != null && !conflictPolicy.isBlank()) {
            return ConflictPolicy.fromString(conflictPolicy);
        }
        return ConflictPolicy.REJECT;
    }
}

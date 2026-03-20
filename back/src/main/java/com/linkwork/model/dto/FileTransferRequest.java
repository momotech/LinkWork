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

    /**
     * @deprecated Use {@link #conflictPolicy} = "OVERWRITE" instead.
     */
    @Deprecated
    private Boolean overwrite;

    public ConflictPolicy resolveConflictPolicy() {
        if (conflictPolicy != null && !conflictPolicy.isBlank()) {
            return ConflictPolicy.fromString(conflictPolicy);
        }
        if (Boolean.TRUE.equals(overwrite)) {
            return ConflictPolicy.OVERWRITE;
        }
        return ConflictPolicy.REJECT;
    }
}

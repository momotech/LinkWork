package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileSpaceSyncResponse {
    private String spaceType;
    private String workstationId;
    private int scannedCount;
    private int syncedCount;
    private int skippedCount;
}


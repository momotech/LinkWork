package com.linkwork.model.dto;

public record FileSpaceSyncResponse(
        String spaceType,
        String workstationId,
        int scannedCount,
        int syncedCount,
        int skippedCount
) {}

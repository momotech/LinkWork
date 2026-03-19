package com.linkwork.controller.v1;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.agent.storage.core.StorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageV1Controller {

    private final StorageClient storageClient;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        boolean configured = storageClient.supportsFileStorageOps() && storageClient.isConfigured();
        return ApiResponse.ok(Map.of("configured", configured, "type", "NFS"));
    }
}

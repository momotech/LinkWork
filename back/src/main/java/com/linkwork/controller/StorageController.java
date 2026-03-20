package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.service.NfsStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final NfsStorageService nfsStorageService;

    public StorageController(NfsStorageService nfsStorageService) {
        this.nfsStorageService = nfsStorageService;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", nfsStorageService.isConfigured());
        status.put("type", "NFS");
        return ApiResponse.success(status);
    }
}

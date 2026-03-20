package com.linkwork.controller;

import com.linkwork.service.ModelRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型列表接口（同源调用，后端代理模型网关）
 */
@RestController
@RequiredArgsConstructor
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;

    @GetMapping("/api/v1/models")
    public Map<String, Object> listModels() {
        return modelRegistryService.fetchModels();
    }
}

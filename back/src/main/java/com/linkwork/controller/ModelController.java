package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.service.LiteLlmModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final LiteLlmModelService liteLlmModelService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listModels() {
        return ApiResponse.success(liteLlmModelService.listModels());
    }
}

package com.linkwork.controller.v1;

import com.linkwork.service.ModelRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;

    @GetMapping("/api/v1/models")
    public Map<String, Object> listModels() {
        return modelRegistryService.fetchModels();
    }
}

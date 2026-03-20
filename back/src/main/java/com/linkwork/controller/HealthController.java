package com.linkwork.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    /**
     * 健康检查接口
     * Docker 健康检查 + 外部监控使用
     * 同时映射 /health 和 /api/v1/health
     */
    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

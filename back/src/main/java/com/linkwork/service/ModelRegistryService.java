package com.linkwork.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRegistryService {

    @Value("${llm-gateway.url:http://llm-gateway:8080}")
    private String llmGatewayUrl;

    @Value("${linkwork.model-registry.timeout-ms:5000}")
    private long timeoutMs;

    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    public Map<String, Object> fetchModels() {
        String targetUrl = resolveModelsUrl(llmGatewayUrl);
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(targetUrl, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("model gateway status=" + response.getStatusCode().value());
            }
            String body = response.getBody();
            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("model gateway response is empty");
            }
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Fetch models failed: url={}, err={}", targetUrl, ex.getMessage());
            return Map.of("object", "list", "data", List.of());
        }
    }

    private String resolveModelsUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "http://llm-gateway:8080/v1/models";
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/v1/models") || normalized.endsWith("/models")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            return normalized + "v1/models";
        }
        return normalized + "/v1/models";
    }
}

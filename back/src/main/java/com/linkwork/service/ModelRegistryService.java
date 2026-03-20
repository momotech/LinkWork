package com.linkwork.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * 模型注册表读取服务（后端代理模型网关，避免前端跨域直连）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRegistryService {

    @Value("${robot.model-registry.gateway-url:http://172.18.228.32:4000/v1/models}")
    private String gatewayUrl;

    @Value("${robot.model-registry.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${robot.model-registry.auth-token:}")
    private String authToken;

    @Value("${robot.model-registry.x-litellm-api-key:}")
    private String xLitellmApiKey;

    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    public Map<String, Object> fetchModels() {
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();

        try {
            HttpHeaders headers = new HttpHeaders();
            String resolvedAuthToken = StringUtils.hasText(authToken) ? authToken.trim() : "";
            if (StringUtils.hasText(resolvedAuthToken)) {
                headers.setBearerAuth(resolvedAuthToken);
            }
            String resolvedXKey = StringUtils.hasText(xLitellmApiKey) ? xLitellmApiKey.trim() : resolvedAuthToken;
            if (StringUtils.hasText(resolvedXKey)) {
                headers.set("x-litellm-api-key", resolvedXKey);
            }
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    gatewayUrl,
                    HttpMethod.GET,
                    requestEntity,
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("模型网关返回非 2xx: " + response.getStatusCode().value());
            }

            String body = response.getBody();
            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("模型网关返回空响应");
            }

            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("读取模型列表失败: gatewayUrl={}", gatewayUrl, e);
            throw new IllegalStateException("模型列表加载失败: " + e.getMessage(), e);
        }
    }
}

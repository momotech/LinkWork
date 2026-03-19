package com.linkwork.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiteLlmModelService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${litellm.base-url:http://172.18.228.32:4000}")
    private String liteLlmBaseUrl;

    @Value("${litellm.api-key:}")
    private String liteLlmApiKey;

    public List<Map<String, Object>> listModels() {
        if (!StringUtils.hasText(liteLlmApiKey)) {
            throw new IllegalStateException("LITELLM_API_KEY is required");
        }

        String baseUrl = normalizeBaseUrl(liteLlmBaseUrl);
        String url = baseUrl + "/v1/models";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-litellm-api-key", liteLlmApiKey.trim());
        headers.setBearerAuth(liteLlmApiKey.trim());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            return parseModelList(response.getBody());
        } catch (Exception e) {
            log.error("Failed to fetch model list from LiteLLM: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch model list from LiteLLM", e);
        }
    }

    private List<Map<String, Object>> parseModelList(String responseBody) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!StringUtils.hasText(responseBody)) {
            return result;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return result;
            }

            for (JsonNode item : data) {
                String modelId = item.path("id").asText("").trim();
                if (!StringUtils.hasText(modelId)) {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", modelId);

                JsonNode statusNode = item.get("status");
                if (statusNode != null && !statusNode.isNull()) {
                    entry.put("status", objectMapper.convertValue(statusNode, Object.class));
                }
                result.add(entry);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse LiteLLM model list: {}", responseBody, e);
            throw new RuntimeException("Failed to parse LiteLLM model list", e);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "http://172.18.228.32:4000";
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}

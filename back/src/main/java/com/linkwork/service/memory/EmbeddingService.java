package com.linkwork.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${litellm.base-url:http://172.18.228.32:4000}")
    private String liteLlmBaseUrl;

    @Value("${litellm.api-key:}")
    private String liteLlmApiKey;

    @Value("${litellm.embedding-model:openrouter/openai/text-embedding-3-small}")
    private String liteLlmEmbeddingModel;

    @PostConstruct
    public void validateLiteLlmConfig() {
        if (!StringUtils.hasText(liteLlmApiKey)) {
            throw new IllegalStateException("LITELLM_API_KEY is required when memory embedding is enabled");
        }
    }

    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        String baseUrl = liteLlmBaseUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/v1/embeddings";
        Map<String, Object> body = Map.of(
                "model", liteLlmEmbeddingModel,
                "input", texts
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-litellm-api-key", liteLlmApiKey);
        headers.setBearerAuth(liteLlmApiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return parseEmbeddingResponse(response.getBody());
        } catch (Exception e) {
            log.error("Embedding API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }

    public List<Float> embedSingle(String text) {
        List<List<Float>> results = embed(List.of(text));
        if (results.isEmpty()) throw new RuntimeException("Empty embedding result");
        return results.get(0);
    }

    private List<List<Float>> parseEmbeddingResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            List<List<Float>> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embNode = item.get("embedding");
                List<Float> embedding = new ArrayList<>();
                for (JsonNode val : embNode) {
                    embedding.add(val.floatValue());
                }
                embeddings.add(embedding);
            }
            return embeddings;
        } catch (Exception e) {
            log.error("Failed to parse embedding response: {}", responseBody, e);
            throw new RuntimeException("Failed to parse embedding response", e);
        }
    }
}

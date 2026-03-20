package com.linkwork.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.MemoryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding service that calls the LLM Gateway's OpenAI-compatible embedding endpoint.
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final MemoryConfig memoryConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${schedule.network.llm-gateway-url:http://llm-gateway:8080}")
    private String llmGatewayUrl;

    /**
     * Generate embeddings for a batch of texts.
     * Calls POST {llmGatewayUrl}/v1/embeddings with OpenAI-compatible format.
     */
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        String url = llmGatewayUrl + "/v1/embeddings";
        Map<String, Object> body = Map.of(
                "model", memoryConfig.getEmbedding().getModel(),
                "input", texts
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return parseEmbeddingResponse(response.getBody());
        } catch (Exception e) {
            log.error("Embedding API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }

    /**
     * Generate embedding for a single text.
     */
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

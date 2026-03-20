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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 任务终态对账：从计费网关拉取任务维度 token 使用量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBillingUsageService {

    @Value("${robot.billing.gateway-url-template:http://ai-agent-gateway.momo.com/v1/billing/tasks/{taskId}}")
    private String gatewayUrlTemplate;

    @Value("${robot.billing.timeout-ms:3000}")
    private long timeoutMs;

    @Value("${robot.billing.sync-retries:3}")
    private int syncRetries;

    @Value("${robot.billing.retry-interval-ms:200}")
    private long retryIntervalMs;

    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    public Optional<UsageSnapshot> fetchTaskUsage(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        String url = gatewayUrlTemplate.replace("{taskId}", taskId.trim());
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();

        int maxAttempts = Math.max(1, syncRetries);
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("billing response status=" + response.getStatusCode().value());
                }
                String body = response.getBody();
                if (!StringUtils.hasText(body)) {
                    throw new IllegalStateException("billing response empty");
                }

                Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
                });
                UsageSnapshot snapshot = UsageSnapshot.from(payload);
                if (snapshot.tokenUsed() == null) {
                    throw new IllegalStateException("billing tokenUsed missing");
                }
                return Optional.of(snapshot);
            } catch (Exception ex) {
                lastError = ex;
                if (attempt < maxAttempts && retryIntervalMs > 0) {
                    try {
                        Thread.sleep(retryIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.warn("任务计费同步失败: taskId={}, url={}, attempts={}, error={}",
                taskId, url, maxAttempts, lastError == null ? "unknown" : lastError.getMessage());
        return Optional.empty();
    }

    public record UsageSnapshot(
            Integer tokenUsed,
            Integer inputTokens,
            Integer outputTokens,
            Integer requestCount,
            Long tokenLimit,
            BigDecimal usagePercent
    ) {
        static UsageSnapshot from(Map<String, Object> source) {
            return new UsageSnapshot(
                    parseInteger(source.get("tokenUsed")),
                    parseInteger(source.get("inputTokens")),
                    parseInteger(source.get("outputTokens")),
                    parseInteger(source.get("requestCount")),
                    parseLong(source.get("tokenLimit")),
                    parseBigDecimal(source.get("usagePercent"))
            );
        }

        private static Integer parseInteger(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (Exception ignore) {
                return null;
            }
        }

        private static Long parseLong(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (Exception ignore) {
                return null;
            }
        }

        private static BigDecimal parseBigDecimal(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(String.valueOf(value).trim());
            } catch (Exception ignore) {
                return null;
            }
        }
    }
}

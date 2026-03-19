package com.linkwork.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.model.dto.TaskShareLinkResponse;
import com.linkwork.model.entity.LinkworkTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@Service
public class TaskShareLinkService {

    private final TaskV1Service taskService;
    private final ObjectMapper objectMapper;

    @Value("${linkwork.share.secret:linkwork-share-secret-2026}")
    private String shareSecret;

    @Value("${linkwork.share.base-url:}")
    private String shareBaseUrl;

    @Value("${linkwork.share.default-expire-hours:24}")
    private int defaultExpireHours;

    @Value("${linkwork.share.max-expire-hours:168}")
    private int maxExpireHours;

    public TaskShareLinkService(TaskV1Service taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    public TaskShareLinkResponse createShareLink(String taskNo, String creatorId, Long expireHours) {
        LinkworkTask task = taskService.getTaskByNo(taskNo, creatorId);
        int resolvedHours = resolveExpireHours(expireHours != null ? expireHours.intValue() : null);
        Instant expiresAt = Instant.now().plusSeconds(resolvedHours * 3600L);

        String token = buildToken(task.getTaskNo(), expiresAt.getEpochSecond());
        TaskShareLinkResponse response = new TaskShareLinkResponse();
        response.setTaskNo(task.getTaskNo());
        response.setShareToken(token);
        response.setShareUrl(buildShareUrl(task.getTaskNo(), token));
        response.setExpiredAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return response;
    }

    public void validateShareToken(String taskNo, String token) {
        if (!StringUtils.hasText(taskNo) || !StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Share link parameters missing");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid share link");
        }

        String payloadEncoded = parts[0];
        String expectedSignature = signPayload(payloadEncoded);
        String actualSignature = parts[1];
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                actualSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid share link");
        }

        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(payloadEncoded), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            String tokenTaskNo = String.valueOf(payload.get("taskNo"));
            long exp = ((Number) payload.get("exp")).longValue();
            if (!taskNo.equals(tokenTaskNo)) {
                throw new IllegalArgumentException("Share link does not match task");
            }
            if (exp <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Share link expired");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid share link");
        }
    }

    private int resolveExpireHours(Integer expireHours) {
        int resolved = expireHours == null ? defaultExpireHours : expireHours;
        if (resolved <= 0) resolved = defaultExpireHours;
        if (resolved > maxExpireHours) resolved = maxExpireHours;
        return resolved;
    }

    private String buildToken(String taskNo, long expEpochSecond) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", taskNo);
            payload.put("exp", expEpochSecond);
            payload.put("nonce", UUID.randomUUID().toString().replace("-", ""));
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            return payloadEncoded + "." + signPayload(payloadEncoded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate share link: taskNo=" + taskNo, e);
        }
    }

    private String signPayload(String payloadEncoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(shareSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payloadEncoded.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign share link", e);
        }
    }

    private String buildShareUrl(String taskNo, String token) {
        String base = shareBaseUrl == null ? "" : shareBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String encodedTaskNo = UriUtils.encodePathSegment(taskNo, StandardCharsets.UTF_8);
        String encodedToken = UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);
        return String.format("%s/share/task/%s?token=%s", base, encodedTaskNo, encodedToken);
    }
}

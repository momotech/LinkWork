package com.linkwork.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.model.dto.TaskShareLinkResponse;
import com.linkwork.model.entity.Task;
import lombok.RequiredArgsConstructor;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务临时分享链接服务（签名 token，无状态校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskShareLinkService {

    @Value("${robot.task-share.secret:}")
    private String shareSecret;

    @Value("${robot.task-share.base-url:}")
    private String shareBaseUrl;

    @Value("${robot.task-share.default-expire-hours:24}")
    private int defaultExpireHours;

    @Value("${robot.task-share.max-expire-hours:168}")
    private int maxExpireHours;

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskShareLinkResponse createShareLink(String taskNo, String creatorId, Integer expireHours) {
        Task task = taskService.getTaskByNo(taskNo, creatorId);
        int resolvedExpireHours = resolveExpireHours(expireHours);
        Instant expiresAt = Instant.now().plusSeconds(resolvedExpireHours * 3600L);

        String token = buildToken(task.getTaskNo(), expiresAt.getEpochSecond());
        TaskShareLinkResponse response = new TaskShareLinkResponse();
        response.setTaskId(task.getTaskNo());
        response.setToken(token);
        response.setShareUrl(buildShareUrl(task.getTaskNo(), token));
        response.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return response;
    }

    public void validateShareToken(String taskNo, String token) {
        if (!StringUtils.hasText(taskNo) || !StringUtils.hasText(token)) {
            throw new IllegalArgumentException("分享链接参数缺失");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("分享链接无效");
        }

        String payloadEncoded = parts[0];
        String expectedSignature = signPayload(payloadEncoded);
        String actualSignature = parts[1];
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                actualSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("分享链接无效");
        }

        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(payloadEncoded), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
            });

            String tokenTaskNo = String.valueOf(payload.get("taskNo"));
            long exp = parseLong(payload.get("exp"));
            if (!taskNo.equals(tokenTaskNo)) {
                throw new IllegalArgumentException("分享链接与任务不匹配");
            }
            if (exp <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("分享链接已过期");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("分享链接无效");
        }
    }

    private int resolveExpireHours(Integer expireHours) {
        int resolved = expireHours == null ? defaultExpireHours : expireHours;
        if (resolved <= 0) {
            resolved = defaultExpireHours;
        }
        if (resolved > maxExpireHours) {
            resolved = maxExpireHours;
        }
        return resolved;
    }

    private String buildToken(String taskNo, long expEpochSecond) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", taskNo);
            payload.put("exp", expEpochSecond);
            payload.put("nonce", UUID.randomUUID().toString().replace("-", ""));
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadEncoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            return payloadEncoded + "." + signPayload(payloadEncoded);
        } catch (Exception e) {
            throw new IllegalStateException("生成任务分享链接失败: taskNo=" + taskNo, e);
        }
    }

    private String signPayload(String payloadEncoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            String secret = requireShareSecret();
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payloadEncoded.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("签名分享链接失败", e);
        }
    }

    private String buildShareUrl(String taskNo, String token) {
        String base = shareBaseUrl == null ? "" : shareBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String encodedTaskNo = UriUtils.encodePathSegment(taskNo, StandardCharsets.UTF_8);
        String encodedToken = UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);
        return String.format("%s/share/task/%s?token=%s", base, encodedTaskNo, encodedToken);
    }

    private String requireShareSecret() {
        if (!StringUtils.hasText(shareSecret)) {
            throw new IllegalStateException("robot.task-share.secret 未配置");
        }
        return shareSecret.trim();
    }

    private long parseLong(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("分享链接无效");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}

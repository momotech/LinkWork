package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * zzd 服务身份鉴权
 */
@Slf4j
@Service
public class ZzdServiceAuthService {

    @Value("${robot.zzd.api-server-token:}")
    private String apiServerToken;

    public String extractBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    public boolean validateToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        if (!StringUtils.hasText(apiServerToken)) {
            log.error("ZZD_API_SERVER_TOKEN 未配置，拒绝访问");
            return false;
        }
        return MessageDigest.isEqual(
                apiServerToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }
}

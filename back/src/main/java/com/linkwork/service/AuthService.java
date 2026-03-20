package com.linkwork.service;

import com.linkwork.context.UserInfo;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 认证服务
 * 处理密码验证和 JWT Token 生成/验证
 */
@Slf4j
@Service
public class AuthService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${robot.auth.password:}")
    private String configuredPasswordHash;

    @Value("${robot.auth.jwt-secret:}")
    private String jwtSecret;

    @Value("${robot.auth.jwt-expiration:86400000}")
    private long jwtExpiration; // 默认 24 小时

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("robot.auth.jwt-secret 未配置");
        }

        if (configuredPasswordHash == null || configuredPasswordHash.isBlank()) {
            throw new IllegalStateException("robot.auth.password 未配置（需提供 BCrypt 哈希）");
        }

        // 初始化 JWT 密钥
        secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证密码
     */
    public boolean validatePassword(String rawPassword) {
        return passwordEncoder.matches(rawPassword, configuredPasswordHash);
    }

    /**
     * 生成 JWT Token
     */
    public String generateToken(String subject) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 验证 JWT Token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Token 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Token 获取主题
     */
    public String getSubjectFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * 工具方法：生成密码的 BCrypt 哈希（用于配置新密码）
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 基于用户信息生成 JWT Token
     * JWT payload 承载完整用户信息，后续请求直接从 JWT 解析，不查库
     */
    @SuppressWarnings("unchecked")
    public String generateTokenForUser(UserInfo userInfo) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("name", userInfo.getName());
        if (userInfo.getEmail() != null) {
            claims.put("email", userInfo.getEmail());
        }
        if (userInfo.getWorkId() != null) {
            claims.put("workId", userInfo.getWorkId());
        }
        if (userInfo.getAvatarUrl() != null) {
            claims.put("avatarUrl", userInfo.getAvatarUrl());
        }
        if (userInfo.getPermissions() != null) {
            claims.put("permissions", userInfo.getPermissions());
        }

        return Jwts.builder()
                .subject(userInfo.getUserId())
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 从 JWT Token 解析完整用户信息
     */
    @SuppressWarnings("unchecked")
    public UserInfo getUserInfoFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        List<String> permissions = null;
        Object permObj = claims.get("permissions");
        if (permObj instanceof List) {
            permissions = (List<String>) permObj;
        }

        return UserInfo.builder()
                .userId(claims.getSubject())
                .name(claims.get("name", String.class))
                .email(claims.get("email", String.class))
                .workId(claims.get("workId", String.class))
                .avatarUrl(claims.get("avatarUrl", String.class))
                .permissions(permissions)
                .build();
    }
}

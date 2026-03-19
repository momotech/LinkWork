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

@Slf4j
@Service
public class AuthService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${linkwork.auth.password:}")
    private String configuredPasswordHash;

    @Value("${linkwork.auth.jwt-secret:linkwork-platform-jwt-secret-key-2026-must-be-at-least-256-bits}")
    private String jwtSecret;

    @Value("${linkwork.auth.jwt-expiration:86400000}")
    private long jwtExpiration;

    private SecretKey secretKey;

    private static final String DEFAULT_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBrkHx4ELlYwBJNLPQjqc4QcJ2i";

    @PostConstruct
    public void init() {
        secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        if (configuredPasswordHash == null || configuredPasswordHash.isBlank()) {
            configuredPasswordHash = DEFAULT_PASSWORD_HASH;
            log.info("Using default password (robot2026)");
        }
    }

    public boolean validatePassword(String rawPassword) {
        return passwordEncoder.matches(rawPassword, configuredPasswordHash);
    }

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

    @SuppressWarnings("unchecked")
    public String generateTokenForUser(UserInfo userInfo) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("name", userInfo.getName());
        if (userInfo.getEmail() != null) claims.put("email", userInfo.getEmail());
        if (userInfo.getWorkId() != null) claims.put("workId", userInfo.getWorkId());
        if (userInfo.getAvatarUrl() != null) claims.put("avatarUrl", userInfo.getAvatarUrl());
        if (userInfo.getPermissions() != null) claims.put("permissions", userInfo.getPermissions());

        return Jwts.builder()
                .subject(userInfo.getUserId())
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String getSubjectFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    public UserInfo getUserInfoFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();

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

    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}

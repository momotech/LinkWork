package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.mapper.GitLabAuthMapper;
import com.linkwork.model.entity.LinkworkGitLabAuth;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabAuthService {

    private final GitLabAuthMapper gitLabAuthMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;

    @Value("${linkwork.gitlab.url:https://gitlab.com}")
    private String gitlabUrl;

    @Value("${linkwork.gitlab.client-id:}")
    private String clientId;

    @Value("${linkwork.gitlab.client-secret:}")
    private String clientSecret;

    @Value("${linkwork.gitlab.redirect-uri:}")
    private String defaultRedirectUri;

    private static final String SCOPE_READ = "read_api read_repository";
    private static final String SCOPE_WRITE = "api read_repository write_repository";

    public String getAuthUrl(String redirectUri, String scopeType) {
        String resolvedRedirectUri = StringUtils.hasText(redirectUri) ? redirectUri : defaultRedirectUri;
        String scope = "write".equals(scopeType) ? SCOPE_WRITE : SCOPE_READ;
        return String.format("%s/oauth/authorize?client_id=%s&redirect_uri=%s&response_type=code&scope=%s",
                gitlabUrl, clientId, resolvedRedirectUri, scope);
    }

    public void callback(String userId, String code, String redirectUri, String scopeType) {
        String resolvedRedirectUri = StringUtils.hasText(redirectUri) ? redirectUri : defaultRedirectUri;
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", resolvedRedirectUri);

        try {
            ResponseEntity<String> tokenResp = restTemplate.postForEntity(
                    gitlabUrl + "/oauth/token", form, String.class);
            Map<String, Object> tokenData = objectMapper.readValue(tokenResp.getBody(),
                    new TypeReference<>() {});
            String accessToken = (String) tokenData.get("access_token");
            String refreshToken = (String) tokenData.get("refresh_token");
            Integer expiresIn = (Integer) tokenData.get("expires_in");
            String scope = (String) tokenData.get("scope");

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            ResponseEntity<String> userResp = restTemplate.exchange(
                    gitlabUrl + "/api/v4/user", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            Map<String, Object> userData = objectMapper.readValue(userResp.getBody(),
                    new TypeReference<>() {});

            LinkworkGitLabAuth auth = new LinkworkGitLabAuth();
            auth.setUserId(userId);
            auth.setGitlabId(((Number) userData.get("id")).longValue());
            auth.setUsername((String) userData.get("username"));
            auth.setName((String) userData.get("name"));
            auth.setAvatarUrl((String) userData.get("avatar_url"));
            auth.setAccessToken(accessToken);
            auth.setRefreshToken(refreshToken);
            auth.setScope(scope);
            auth.setExpiresAt(expiresIn != null
                    ? LocalDateTime.now().plusSeconds(expiresIn) : LocalDateTime.now().plusDays(1));

            LinkworkGitLabAuth existing = gitLabAuthMapper.selectOne(
                    new LambdaQueryWrapper<LinkworkGitLabAuth>()
                            .eq(LinkworkGitLabAuth::getUserId, userId)
                            .eq(LinkworkGitLabAuth::getGitlabId, auth.getGitlabId()));
            if (existing != null) {
                auth.setId(existing.getId());
                gitLabAuthMapper.updateById(auth);
            } else {
                gitLabAuthMapper.insert(auth);
            }
            log.info("GitLab auth saved: userId={}, gitlabUser={}", userId, auth.getUsername());
        } catch (Exception e) {
            log.error("GitLab OAuth callback failed: userId={}", userId, e);
            throw new IllegalStateException("GitLab OAuth callback failed: " + e.getMessage());
        }
    }

    public List<LinkworkGitLabAuth> listUsers(String userId) {
        return gitLabAuthMapper.selectList(
                new LambdaQueryWrapper<LinkworkGitLabAuth>()
                        .eq(LinkworkGitLabAuth::getUserId, userId)
                        .orderByDesc(LinkworkGitLabAuth::getUpdatedAt));
    }

    public void deleteUser(String userId, String authId) {
        LinkworkGitLabAuth auth = gitLabAuthMapper.selectById(Long.parseLong(authId));
        if (auth == null || !userId.equals(auth.getUserId())) {
            throw new IllegalArgumentException("GitLab auth not found or not owned by user");
        }
        gitLabAuthMapper.deleteById(auth.getId());
    }

    public LinkworkGitLabAuth getLatestAuth(String userId) {
        return gitLabAuthMapper.selectOne(
                new LambdaQueryWrapper<LinkworkGitLabAuth>()
                        .eq(LinkworkGitLabAuth::getUserId, userId)
                        .orderByDesc(LinkworkGitLabAuth::getUpdatedAt)
                        .last("LIMIT 1"));
    }

    @Data
    public static class CommitIdentity {
        private String username;
        private String email;
    }
}

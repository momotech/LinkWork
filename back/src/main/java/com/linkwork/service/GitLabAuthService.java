package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.mapper.GitLabAuthMapper;
import com.linkwork.model.entity.GitLabAuthEntity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabAuthService {

    private static final String DEFAULT_GITLAB_BASE_URL = "https://git.example.com";

    private final GitLabAuthMapper gitLabAuthMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${robot.gitlab.base-url}")
    private String gitlabBaseUrl;

    @Value("${robot.gitlab.auth-base-url:}")
    private String gitlabAuthBaseUrl;

    @Value("${robot.gitlab.client-id}")
    private String clientId;

    @Value("${robot.gitlab.client-secret}")
    private String clientSecret;

    @Value("${robot.gitlab.redirect-uri}")
    private String defaultRedirectUri;

    public String getAuthUrl(String redirectUri, String scopeType) {
        String normalizedScopeType = normalizeScopeType(scopeType);
        String uri = resolveRedirectUri(redirectUri);
        String scope = resolveScope(normalizedScopeType);
        return String.format("%s/oauth/authorize?client_id=%s&redirect_uri=%s&response_type=code&state=%s&scope=%s",
                resolveOauthBaseUrl(), clientId, uri, normalizedScopeType, scope);
    }

    public void callback(String userId, String code, String redirectUri, String scopeType) {
        String normalizedScopeType = normalizeScopeType(scopeType);
        String uri = resolveRedirectUri(redirectUri);

        // 1. Exchange Token
        String tokenUrl = resolveOauthBaseUrl() + "/oauth/token";
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("code", code);
        params.add("grant_type", "authorization_code");
        params.add("redirect_uri", uri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, new HttpEntity<>(params, headers), Map.class);
        Map body = response.getBody();

        if (body == null || body.get("access_token") == null) {
            throw new RuntimeException("Failed to get access token from GitLab");
        }

        String accessToken = (String) body.get("access_token");
        String refreshToken = (String) body.get("refresh_token");
        Integer expiresIn = (Integer) body.get("expires_in");
        if (expiresIn == null) {
            expiresIn = 7200;
        }

        // 2. Get User Info
        String userUrl = resolveApiBaseUrl() + "/api/v4/user";
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        ResponseEntity<Map> userResp = restTemplate.exchange(userUrl, HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        Map userBody = userResp.getBody();

        if (userBody == null) {
            throw new RuntimeException("Failed to get user info from GitLab");
        }

        Long gitlabId = ((Number) userBody.get("id")).longValue();
        String username = (String) userBody.get("username");
        String name = (String) userBody.get("name");
        String avatarUrl = (String) userBody.get("avatar_url");

        String scope = resolveScope(normalizedScopeType);

        // 3. Save to DB - use custom query that includes soft-deleted records
        GitLabAuthEntity entity = gitLabAuthMapper.selectIncludingDeleted(userId, gitlabId, scope);

        if (entity == null) {
            entity = new GitLabAuthEntity();
            entity.setUserId(userId);
            entity.setGitlabId(gitlabId);
            entity.setCreatedAt(LocalDateTime.now());
        }

        entity.setUsername(username);
        entity.setName(name);
        entity.setAvatarUrl(avatarUrl);
        entity.setAccessToken(encrypt(accessToken));
        entity.setRefreshToken(encrypt(refreshToken));
        entity.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
        entity.setTokenAlias(maskToken(accessToken));
        entity.setScope(scope);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setIsDeleted(false);

        if (entity.getId() == null) {
            gitLabAuthMapper.insert(entity);
        } else {
            gitLabAuthMapper.updateIncludingDeleted(entity);
        }
    }

    public List<GitLabAuthEntity> listUsers(String userId) {
        List<GitLabAuthEntity> authList = gitLabAuthMapper.selectList(new LambdaQueryWrapper<GitLabAuthEntity>()
                .eq(GitLabAuthEntity::getUserId, userId)
                .eq(GitLabAuthEntity::getIsDeleted, false)
                .orderByDesc(GitLabAuthEntity::getCreatedAt));

        // 自动刷新过期的 token，让前端看到最新的过期时间
        LocalDateTime now = LocalDateTime.now();
        for (GitLabAuthEntity auth : authList) {
            if (auth.getExpiresAt() != null && auth.getExpiresAt().isBefore(now)) {
                tryRefreshToken(auth);
            }
        }

        return authList;
    }

    public void deleteUser(String userId, String authId) {
        gitLabAuthMapper.delete(new LambdaQueryWrapper<GitLabAuthEntity>()
                .eq(GitLabAuthEntity::getId, authId)
                .eq(GitLabAuthEntity::getUserId, userId));
    }

    /**
     * 获取用户最新的 GitLab 授权。
     */
    public GitLabAuthEntity getLatestAuth(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        List<GitLabAuthEntity> authList = gitLabAuthMapper.selectList(
                new LambdaQueryWrapper<GitLabAuthEntity>()
                        .eq(GitLabAuthEntity::getUserId, userId)
                        .eq(GitLabAuthEntity::getIsDeleted, false)
                        .orderByDesc(GitLabAuthEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );

        if (authList == null || authList.isEmpty()) {
            return null;
        }
        return authList.get(0);
    }

    /**
     * 根据 authId 获取有效 token，若已过期或将过期则先刷新。
     */
    public ValidToken getValidTokenByAuthId(Long authId, Duration refreshAheadWindow) {
        if (authId == null) {
            return null;
        }

        GitLabAuthEntity auth = gitLabAuthMapper.selectById(authId);
        if (auth == null || Boolean.TRUE.equals(auth.getIsDeleted())) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        Duration window = refreshAheadWindow != null ? refreshAheadWindow : Duration.ofMinutes(5);
        LocalDateTime refreshThreshold = now.plus(window);

        if (auth.getExpiresAt() == null || !auth.getExpiresAt().isAfter(refreshThreshold)) {
            String refreshedToken = tryRefreshToken(auth);
            if (StringUtils.hasText(refreshedToken)) {
                auth = gitLabAuthMapper.selectById(authId);
                if (auth == null || Boolean.TRUE.equals(auth.getIsDeleted())) {
                    return null;
                }
            }
        }

        if (auth.getExpiresAt() == null || !auth.getExpiresAt().isAfter(now)) {
            log.warn("GitLab token expired after refresh attempt: authId={}, tokenAlias={}",
                    authId, auth.getTokenAlias());
            throw new IllegalStateException("GitLab token expired and refresh failed: authId=" + authId);
        }

        String token = decrypt(auth.getAccessToken());
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("GitLab token is blank: authId=" + authId);
        }

        ValidToken validToken = new ValidToken();
        validToken.setToken(token);
        validToken.setTokenAlias(auth.getTokenAlias());
        validToken.setExpiresAt(auth.getExpiresAt());
        validToken.setScope(auth.getScope());
        validToken.setTokenType(resolveTokenType(auth, token));
        return validToken;
    }

    /**
     * 获取用户有效的 GitLab access token
     * 优先返回未过期的 token；如果过期则自动使用 refresh_token 续期
     */
    public String getAccessToken(String userId) {
        GitLabAuthEntity latest = getLatestAuth(userId);
        if (latest == null) {
            log.debug("No GitLab auth found for userId: {}", userId);
            return null;
        }

        ValidToken validToken = getValidTokenByAuthId(latest.getId(), Duration.ofMinutes(5));
        if (validToken == null) {
            log.warn("No valid GitLab token available for userId: {}", userId);
            return null;
        }
        return validToken.getToken();
    }

    /**
     * 通过当前有效 token 查询 GitLab 用户身份，用于 git commit 身份注入。
     */
    public CommitIdentity resolveCommitIdentity(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalArgumentException("accessToken is required");
        }

        String userUrl = resolveApiBaseUrl() + "/api/v4/user";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<Map> response = restTemplate.exchange(
                userUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        Map body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("GitLab user info response is empty");
        }

        String username = body.get("username") == null ? "" : String.valueOf(body.get("username")).trim();
        String email = body.get("email") == null ? "" : String.valueOf(body.get("email")).trim();
        if (!StringUtils.hasText(email)) {
            email = body.get("public_email") == null ? "" : String.valueOf(body.get("public_email")).trim();
        }
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("GitLab user info missing username");
        }
        if (!StringUtils.hasText(email)) {
            throw new IllegalStateException("GitLab user info missing email/public_email");
        }

        CommitIdentity identity = new CommitIdentity();
        identity.setUsername(username);
        identity.setEmail(email);
        return identity;
    }

    /**
     * 根据 scopeType 解析实际的 GitLab scope 字符串
     */
    private String resolveScope(String scopeType) {
        if ("read".equals(scopeType)) {
            return "read_user read_repository";
        }
        // Keep write flow compatible with existing GitLab OAuth app configuration.
        return "read_user api";
    }

    /**
     * 多因子判定 tokenType（oauth / pat）：
     *  1. token 以 glpat- 开头 → PAT（GitLab Personal Access Token 固定前缀）
     *  2. scope 含 api / read_repository 等 OAuth 授权特征 → oauth
     *  3. 存在 refresh_token → oauth
     *  4. 兜底 → oauth（当前 OAuth 授权流是主要入口）
     */
    private String resolveTokenType(GitLabAuthEntity auth, String decryptedToken) {
        if (StringUtils.hasText(decryptedToken) && decryptedToken.startsWith("glpat-")) {
            return "pat";
        }
        String scope = auth.getScope();
        if (StringUtils.hasText(scope) && (scope.contains("api") || scope.contains("read_repository"))) {
            return "oauth";
        }
        if (StringUtils.hasText(decrypt(auth.getRefreshToken()))) {
            return "oauth";
        }
        return "oauth";
    }

    /**
     * 使用 refresh_token 向 GitLab 换取新的 access_token
     */
    protected String tryRefreshToken(GitLabAuthEntity auth) {
        String refreshToken = decrypt(auth.getRefreshToken());
        if (!StringUtils.hasText(refreshToken)) {
            log.warn("No refresh token available for GitLab auth id: {}", auth.getId());
            return null;
        }

        try {
            String tokenUrl = resolveOauthBaseUrl() + "/oauth/token";
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("refresh_token", refreshToken);
            params.add("grant_type", "refresh_token");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, new HttpEntity<>(params, headers), Map.class);
            Map body = response.getBody();

            if (body == null || body.get("access_token") == null) {
                log.warn("GitLab refresh response missing access_token for auth id: {}", auth.getId());
                return null;
            }

            String newAccessToken = (String) body.get("access_token");
            String newRefreshToken = (String) body.get("refresh_token");
            Integer expiresIn = (Integer) body.get("expires_in");
            if (expiresIn == null) {
                expiresIn = 7200;
            }

            auth.setAccessToken(encrypt(newAccessToken));
            if (newRefreshToken != null) {
                auth.setRefreshToken(encrypt(newRefreshToken));
            }
            auth.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            auth.setTokenAlias(maskToken(newAccessToken));
            auth.setUpdatedAt(LocalDateTime.now());
            gitLabAuthMapper.updateById(auth);

            log.info("GitLab token refreshed: userId={}, username={}, tokenAlias={}, expiresAt={}",
                    auth.getUserId(), auth.getUsername(), auth.getTokenAlias(), auth.getExpiresAt());
            return newAccessToken;
        } catch (Exception e) {
            log.warn("Failed to refresh GitLab token for auth id {}: {}", auth.getId(), e.getMessage());
            return null;
        }
    }

    private String resolveOauthBaseUrl() {
        if (StringUtils.hasText(gitlabAuthBaseUrl)) {
            return gitlabAuthBaseUrl;
        }
        if (StringUtils.hasText(gitlabBaseUrl)) {
            return gitlabBaseUrl;
        }
        return DEFAULT_GITLAB_BASE_URL;
    }

    private String resolveApiBaseUrl() {
        if (StringUtils.hasText(gitlabBaseUrl)) {
            return gitlabBaseUrl;
        }
        return DEFAULT_GITLAB_BASE_URL;
    }

    private String resolveRedirectUri(String redirectUri) {
        if (StringUtils.hasText(redirectUri)) {
            return redirectUri;
        }
        return defaultRedirectUri;
    }

    private String normalizeScopeType(String scopeType) {
        if ("read".equals(scopeType)) {
            return "read";
        }
        return "write";
    }

    private String encrypt(String token) {
        // TODO: Implement reversible encryption
        return token;
    }

    private String decrypt(String encryptedToken) {
        // TODO: Implement reversible encryption (matches encrypt)
        return encryptedToken;
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "******";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    @Data
    public static class ValidToken {
        private String token;
        private String tokenAlias;
        private LocalDateTime expiresAt;
        private String scope;
        private String tokenType;
    }

    @Data
    public static class CommitIdentity {
        private String username;
        private String email;
    }
}

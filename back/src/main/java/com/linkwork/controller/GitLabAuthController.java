package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.GitLabAuthEntity;
import com.linkwork.service.GitLabAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/gitlab")
@RequiredArgsConstructor
public class GitLabAuthController {

    private final GitLabAuthService gitLabAuthService;

    @GetMapping("/url")
    public ApiResponse<Map<String, String>> getAuthUrl(
            @RequestParam(required = false) String redirectUri,
            @RequestParam(defaultValue = "write") String scopeType) {
        String url = gitLabAuthService.getAuthUrl(redirectUri, scopeType);
        return ApiResponse.success(Map.of("url", url));
    }

    @PostMapping("/callback")
    public ApiResponse<Void> callback(@RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String code = body.get("code");
        String redirectUri = body.get("redirectUri");
        String scopeType = body.getOrDefault("scopeType", "write");
        gitLabAuthService.callback(userId, code, redirectUri, scopeType);
        return ApiResponse.success(null);
    }

    @GetMapping("/users")
    public ApiResponse<List<GitLabAuthEntity>> listUsers() {
        String userId = UserContext.getCurrentUserId();
        // We mask the tokens in the response
        List<GitLabAuthEntity> list = gitLabAuthService.listUsers(userId);
        list.forEach(item -> {
            item.setAccessToken(null);
            item.setRefreshToken(null);
        });
        return ApiResponse.success(list);
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable String id) {
        String userId = UserContext.getCurrentUserId();
        gitLabAuthService.deleteUser(userId, id);
        return ApiResponse.success(null);
    }
}

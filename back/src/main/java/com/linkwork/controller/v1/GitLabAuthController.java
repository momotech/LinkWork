package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.model.entity.LinkworkGitLabAuth;
import com.linkwork.service.GitLabAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/gitlab")
@RequiredArgsConstructor
public class GitLabAuthController {

    private final GitLabAuthService gitLabAuthService;

    @GetMapping("/url")
    public ResponseEntity<Map<String, Object>> getAuthUrl(
            @RequestParam(required = false) String redirectUri,
            @RequestParam(defaultValue = "write") String scopeType) {
        String url = gitLabAuthService.getAuthUrl(redirectUri, scopeType);
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("url", url)));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(@RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String code = body.get("code");
        String redirectUri = body.get("redirectUri");
        String scopeType = body.getOrDefault("scopeType", "write");
        gitLabAuthService.callback(userId, code, redirectUri, scopeType);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers() {
        String userId = UserContext.getCurrentUserId();
        List<LinkworkGitLabAuth> list = gitLabAuthService.listUsers(userId);
        list.forEach(item -> {
            item.setAccessToken(null);
            item.setRefreshToken(null);
        });
        return ResponseEntity.ok(Map.of("code", 0, "data", list));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String id) {
        String userId = UserContext.getCurrentUserId();
        gitLabAuthService.deleteUser(userId, id);
        return ResponseEntity.ok(Map.of("code", 0, "message", "deleted"));
    }
}

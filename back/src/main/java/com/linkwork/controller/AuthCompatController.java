package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthCompatController {

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        String userId = firstNonBlank(
                UserContext.getCurrentUserId(),
                request.getHeader("X-User-Id"),
                request.getHeader("x-user-id"));
        if (!StringUtils.hasText(userId)) {
            return ApiResponse.error(40100, "未登录");
        }

        String userName = firstNonBlank(
                UserContext.getCurrentUserName(),
                request.getHeader("X-User-Name"),
                request.getHeader("x-user-name"),
                "anonymous");
        String workId = firstNonBlank(
                request.getHeader("X-Work-Id"),
                request.getHeader("x-work-id"),
                userId);
        String email = firstNonBlank(
                request.getHeader("X-User-Email"),
                request.getHeader("x-user-email"),
                userId + "@example.com");
        String avatarUrl = firstNonBlank(
                request.getHeader("X-User-Avatar"),
                request.getHeader("x-user-avatar"),
                "");

        return ApiResponse.success(Map.of(
                "userId", userId,
                "name", userName,
                "email", email,
                "workId", workId,
                "avatarUrl", avatarUrl,
                "permissions", List.of()));
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}

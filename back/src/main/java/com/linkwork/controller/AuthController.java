package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String COOKIE_NAME = "linkwork_token";

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                   HttpServletResponse response) {
        if (!authService.validatePassword(request.getPassword())) {
            return ApiResponse.error(40100, "Password incorrect");
        }

        UserInfo userInfo = UserInfo.builder()
                .userId(request.getUserId() != null ? request.getUserId() : "linkwork-user")
                .name(request.getUserName() != null ? request.getUserName() : "LinkWork User")
                .build();
        String token = authService.generateTokenForUser(userInfo);

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(86400);
        response.addCookie(cookie);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("expiresIn", 86400);
        result.put("userId", userInfo.getUserId());
        result.put("name", userInfo.getName());
        return ApiResponse.success(result);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        UserInfo user = UserContext.get();
        if (user == null) {
            return ApiResponse.error(40100, "Not authenticated");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getUserId());
        data.put("name", user.getName());
        data.put("email", user.getEmail());
        data.put("workId", user.getWorkId());
        data.put("avatarUrl", user.getAvatarUrl());
        data.put("permissions", user.getPermissions());
        return ApiResponse.success(data);
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.error(40101, "No valid token provided");
        }
        String token = authHeader.substring(7);
        if (!authService.validateToken(token)) {
            return ApiResponse.error(40101, "Token invalid or expired");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("subject", authService.getSubjectFromToken(token));
        return ApiResponse.success(result);
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ApiResponse.success(Map.of("message", "Logged out"));
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Password is required")
        private String password;
        private String userId;
        private String userName;
    }
}

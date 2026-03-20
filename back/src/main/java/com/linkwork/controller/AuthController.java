package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.service.AuthService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

/**
 * 认证控制器
 * 处理登录验证和 Token 校验
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    public static final String COOKIE_NAME = "robot_token";

    /**
     * 登录验证
     * POST /api/v1/auth/login
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        log.info("登录请求");

        // 验证密码
        if (!authService.validatePassword(request.getPassword())) {
            log.warn("密码验证失败");
            return ApiResponse.error(40100, "密码错误");
        }

        // 生成 Token
        String token = authService.generateToken("robot-user");

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(86400);
        response.addCookie(cookie);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("expiresIn", 86400); // 24 小时（秒）

        log.info("登录成功");
        return ApiResponse.success(result);
    }

    /**
     * 验证 Token
     * POST /api/v1/auth/verify
     */
    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.error(40101, "未提供有效的 Token");
        }

        String token = authHeader.substring(7);

        if (!authService.validateToken(token)) {
            return ApiResponse.error(40101, "Token 无效或已过期");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("subject", authService.getSubjectFromToken(token));

        return ApiResponse.success(result);
    }

    /**
     * 生成密码哈希（工具端点，生产环境可禁用）
     * GET /api/v1/auth/encode?password=xxx
     */
    @GetMapping("/encode")
    public ApiResponse<Map<String, Object>> encodePassword(@RequestParam String password) {
        String hash = authService.encodePassword(password);
        Map<String, Object> result = new HashMap<>();
        result.put("password", password);
        result.put("hash", hash);
        return ApiResponse.success(result);
    }

    /**
     * 获取当前登录用户信息（从 JWT payload 解析）。
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        UserInfo userInfo = UserContext.get();
        if (userInfo == null) {
            return ApiResponse.error(40100, "未登录");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userInfo.getUserId());
        data.put("name", userInfo.getName());
        data.put("email", userInfo.getEmail());
        data.put("workId", userInfo.getWorkId());
        data.put("avatarUrl", userInfo.getAvatarUrl());
        data.put("permissions", userInfo.getPermissions());
        return ApiResponse.success(data);
    }

    /**
     * 登出：清理 token cookie。
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        Map<String, Object> data = new HashMap<>();
        data.put("logoutUrl", "/login");
        return ApiResponse.success(data);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoginRequest {
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }
}

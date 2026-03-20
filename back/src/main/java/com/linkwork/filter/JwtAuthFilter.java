package com.linkwork.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * 全局 JWT 认证过滤器
 * <p>
 * 从 Cookie 或 Authorization Header 中提取 JWT Token，
 * 解析用户信息存入 UserContext（ThreadLocal）。
 * 未认证的请求返回 401。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthFilter implements Filter {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    private static final String AUTH_COOKIE_NAME = "robot_token";

    /** 不需要认证的路径 */
    private static final Set<String> EXCLUDE_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/verify",
            "/api/v1/auth/encode",
            "/api/v1/models",
            "/health",
            "/api/v1/health"
    );

    /** 不需要认证的路径后缀（内部 Worker 回调） */
    private static final Set<String> EXCLUDE_SUFFIXES = Set.of(
            "/complete",
            "/git-token"
    );

    /** 不需要认证的精确内部路径 */
    private static final Set<String> INTERNAL_PATHS = Set.of(
            "/api/v1/approvals/create"
    );

    /** 不需要认证的路径前缀 */
    private static final Set<String> EXCLUDE_PREFIXES = Set.of(
            "/ws/",
            "/api/v1/ws",
            "/api/internal/"
    );

    private static final String PUBLIC_TASK_MODEL_PREFIX = "/api/v1/public/tasks/";
    private static final String PUBLIC_TASK_MODEL_SUFFIX = "/model";
    private static final String PUBLIC_TASK_SHARE_DETAIL_SUFFIX = "/share-detail";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // 跳过不需要认证的路径
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // 提取 JWT Token
            String token = extractToken(httpRequest);

            if (token != null && authService.validateToken(token)) {
                // 解析用户信息并存入 ThreadLocal
                UserInfo userInfo = authService.getUserInfoFromToken(token);
                UserContext.set(userInfo);
                chain.doFilter(request, response);
            } else {
                // 未认证
                sendUnauthorized(httpResponse, "未登录或 Token 已过期");
            }
        } finally {
            // 清除 ThreadLocal，防止内存泄漏
            UserContext.clear();
        }
    }

    /**
     * 从请求中提取 JWT Token
     * 优先从 Cookie 提取，其次从 Authorization Header 提取
     */
    private String extractToken(HttpServletRequest request) {
        // 1. 从 Cookie 提取
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AUTH_COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }

        // 2. 从 Authorization Header 提取（兼容旧方式）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }

    /**
     * 判断路径是否排除认证
     */
    private boolean isExcluded(String path) {
        if (EXCLUDE_PATHS.contains(path) || INTERNAL_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : EXCLUDE_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        if (path.startsWith(PUBLIC_TASK_MODEL_PREFIX)
                && (path.endsWith(PUBLIC_TASK_MODEL_SUFFIX) || path.endsWith(PUBLIC_TASK_SHARE_DETAIL_SUFFIX))) {
            return true;
        }
        // Worker 回调接口：/api/v1/tasks/{taskNo}/complete 等
        for (String suffix : EXCLUDE_SUFFIXES) {
            if (path.endsWith(suffix) && (path.startsWith("/api/v1/tasks/") || path.startsWith("/api/v1/roles/"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 401 响应
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", 40100,
                "msg", message,
                "data", Map.of()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

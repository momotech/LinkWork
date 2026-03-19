package com.linkwork.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.controller.AuthController;
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

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthFilter implements Filter {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    private static final Set<String> EXCLUDE_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/verify",
            "/api/v1/auth/encode",
            "/api/v1/auth/logout",
            "/api/v1/models",
            "/health",
            "/api/v1/health"
    );

    private static final Set<String> EXCLUDE_SUFFIXES = Set.of(
            "/complete",
            "/git-token"
    );

    private static final Set<String> EXCLUDE_PREFIXES = Set.of(
            "/ws/",
            "/api/v1/ws",
            "/api/internal/",
            "/api/v1/public/"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String token = extractToken(httpRequest);
            if (token != null && authService.validateToken(token)) {
                UserInfo userInfo = authService.getUserInfoFromToken(token);
                UserContext.set(userInfo);
                chain.doFilter(request, response);
            } else {
                sendUnauthorized(httpResponse, "Not authenticated or token expired");
            }
        } finally {
            UserContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthController.COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isExcluded(String path) {
        if (EXCLUDE_PATHS.contains(path)) return true;
        for (String prefix : EXCLUDE_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        for (String suffix : EXCLUDE_SUFFIXES) {
            if (path.endsWith(suffix) && (path.startsWith("/api/v1/tasks/") || path.startsWith("/api/v1/roles/"))) {
                return true;
            }
        }
        return false;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of("code", 40100, "msg", message, "data", Map.of());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

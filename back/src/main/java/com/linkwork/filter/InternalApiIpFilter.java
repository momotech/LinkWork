package com.linkwork.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.common.ClientIpResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内部 API IP 白名单过滤器
 * <p>
 * /api/internal/** 路径仅允许集群内网 IP（10.x、172.16-31.x、192.168.x）和 localhost 访问。
 * 可通过 robot.internal-api.extra-ips 配置额外放行的 IP。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class InternalApiIpFilter implements Filter {

    private static final String INTERNAL_PREFIX = "/api/internal/";

    private final ObjectMapper objectMapper;

    @Value("${robot.internal-api.extra-ips:}")
    private String extraIps;

    private final Set<String> extraIpSet = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized = false;

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    if (StringUtils.hasText(extraIps)) {
                        for (String ip : extraIps.split(",")) {
                            String trimmed = ip.trim();
                            if (!trimmed.isEmpty()) {
                                extraIpSet.add(trimmed);
                            }
                        }
                    }
                    initialized = true;
                }
            }
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        if (!path.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        ensureInitialized();

        String clientIp = ClientIpResolver.resolve(httpRequest);
        if (isAllowed(clientIp)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Internal API access denied: ip={}, path={}", clientIp, path);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("code", 40300, "msg", "Forbidden: IP not in whitelist", "data", Map.of())
            ));
        }
    }

    private boolean isAllowed(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return true;
        }
        if (extraIpSet.contains(ip)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}

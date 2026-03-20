package com.linkwork.filter;

import com.linkwork.common.ClientIpResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 内部 API 调用审计日志
 * <p>
 * 记录所有 /api/internal/** 请求的调用方 IP、Method、Path、响应码。
 */
@Slf4j
@Component
@Order(2)
public class InternalApiAuditFilter implements Filter {

    private static final String INTERNAL_PREFIX = "/api/internal/";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        if (!path.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = ClientIpResolver.resolve(httpRequest);
        String method = httpRequest.getMethod();
        long start = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            int status = ((HttpServletResponse) response).getStatus();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[AUDIT] internal-api ip={} method={} path={} status={} elapsed={}ms",
                    clientIp, method, path, status, elapsed);
        }
    }
}

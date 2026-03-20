package com.linkwork.common;

import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolve client IP from proxy headers and servlet request.
 */
public final class ClientIpResolver {

    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        for (String header : IP_HEADER_CANDIDATES) {
            String value = normalize(request.getHeader(header));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }

        return normalize(request.getRemoteAddr());
    }

    private static String normalize(String rawIp) {
        if (!StringUtils.hasText(rawIp)) {
            return null;
        }

        String first = rawIp.split(",")[0].trim();
        if (!StringUtils.hasText(first) || "unknown".equalsIgnoreCase(first)) {
            return null;
        }

        if ("0:0:0:0:0:0:0:1".equals(first) || "::1".equals(first)) {
            return "127.0.0.1";
        }

        return first.length() > 64 ? first.substring(0, 64) : first;
    }
}

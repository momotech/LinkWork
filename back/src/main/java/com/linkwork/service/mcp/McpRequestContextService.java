package com.linkwork.service.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class McpRequestContextService {

    public String currentUserId() {
        String value = header("X-User-Id");
        return StringUtils.hasText(value) ? value : "anonymous";
    }

    public String currentUserName() {
        String value = header("X-User-Name");
        return StringUtils.hasText(value) ? value : "anonymous";
    }

    public boolean isCurrentUserAdmin() {
        String value = header("X-Admin");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private String header(String name) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        if (request == null) {
            return null;
        }
        return request.getHeader(name);
    }
}

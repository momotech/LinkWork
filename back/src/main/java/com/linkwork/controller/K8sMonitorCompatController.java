package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/k8s-monitor")
public class K8sMonitorCompatController {

    @Value("${linkwork.k8s-monitor.allowed-users:gq.dave_lzw,1025221552,101250,1141132021,889293919,586639869}")
    private String allowedUsersConfig;

    @GetMapping("/access-check")
    public ApiResponse<Boolean> accessCheck(HttpServletRequest request) {
        String userId = firstNonBlank(
                UserContext.getCurrentUserId(),
                request.getHeader("X-User-Id"),
                request.getHeader("x-user-id"));
        String workId = firstNonBlank(
                request.getHeader("X-Work-Id"),
                request.getHeader("x-work-id"));
        return ApiResponse.success(isAllowed(userId, workId));
    }

    private boolean isAllowed(String userId, String workId) {
        Set<String> allowed = new HashSet<>();
        Arrays.stream(allowedUsersConfig.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(allowed::add);
        return (StringUtils.hasText(userId) && allowed.contains(userId.trim()))
                || (StringUtils.hasText(workId) && allowed.contains(workId.trim()));
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

package com.linkwork.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员访问能力判断（统一维护，避免各模块配置漂移）
 */
@Service
public class AdminAccessService {

    @Value("${robot.admin-user-ids:}")
    private String adminUserIdsConfig;

    public boolean isAdmin(String userId) {
        return StringUtils.hasText(userId) && getAdminUserIds().contains(userId.trim());
    }

    private Set<String> getAdminUserIds() {
        if (!StringUtils.hasText(adminUserIdsConfig)) {
            return Set.of();
        }
        return Arrays.stream(adminUserIdsConfig.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }
}

package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.UserSoulResponse;
import com.linkwork.model.dto.UserSoulUpsertRequest;
import com.linkwork.service.UserSoulService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping({"/api/v1/users/me/soul", "/api/v1/user-soul"})
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class UserSoulController {

    private final UserSoulService userSoulService;

    @GetMapping
    public ApiResponse<UserSoulResponse> getCurrentUserSoul() {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        UserSoulResponse response = userSoulService.getCurrentUserSoul(userId);
        return ApiResponse.success(response);
    }

    @PutMapping
    public ApiResponse<UserSoulResponse> upsertCurrentUserSoul(
            @Valid @RequestBody UserSoulUpsertRequest request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        log.info("更新用户 Soul: userId={}, version={}", userId, request.getVersion());
        UserSoulResponse response = userSoulService.upsertCurrentUserSoul(userId, userName, request);
        return ApiResponse.success(response);
    }
}

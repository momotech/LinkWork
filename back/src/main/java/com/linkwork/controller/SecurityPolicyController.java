package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.service.SecurityPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 安全策略控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/security/policies")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class SecurityPolicyController {

    private final SecurityPolicyService policyService;

    /**
     * 获取所有安全策略
     * GET /api/v1/security/policies
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listPolicies() {
        log.info("获取安全策略列表");
        return ApiResponse.success(policyService.listPolicies());
    }

    /**
     * 获取单个策略详情
     * GET /api/v1/security/policies/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getPolicy(@PathVariable Long id) {
        log.info("获取安全策略详情: id={}", id);
        return ApiResponse.success(policyService.getPolicy(id));
    }

    /**
     * 创建自定义策略
     * POST /api/v1/security/policies
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createPolicy(@RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        log.info("创建安全策略: name={}, userId={}", request.get("name"), userId);
        return ApiResponse.success(policyService.createPolicy(request, userId, userName));
    }

    /**
     * 更新策略
     * PUT /api/v1/security/policies/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updatePolicy(
            @PathVariable Long id, @RequestBody Map<String, Object> request) {
        log.info("更新安全策略: id={}", id);
        return ApiResponse.success(policyService.updatePolicy(id, request));
    }

    /**
     * 切换策略启用/禁用
     * POST /api/v1/security/policies/{id}/toggle
     */
    @PostMapping("/{id}/toggle")
    public ApiResponse<Map<String, Object>> togglePolicy(@PathVariable Long id) {
        log.info("切换安全策略: id={}", id);
        return ApiResponse.success(policyService.togglePolicy(id));
    }

    /**
     * 删除策略
     * DELETE /api/v1/security/policies/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long id) {
        log.info("删除安全策略: id={}", id);
        policyService.deletePolicy(id);
        return ApiResponse.success(null);
    }
}

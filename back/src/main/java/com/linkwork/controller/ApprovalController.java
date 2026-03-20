package com.linkwork.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.ApiResponse;
import com.linkwork.common.ClientIpResolver;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.Approval;
import com.linkwork.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 审批控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /**
     * 获取审批列表
     * GET /api/v1/approvals
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listApprovals(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        log.info("获取审批列表: status={}, page={}, pageSize={}, userId={}", status, page, pageSize, userId);

        Page<Approval> approvalPage = approvalService.listApprovals(status, page, pageSize, userId);
        List<Map<String, Object>> items = approvalService.toResponseList(approvalPage.getRecords());

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", approvalPage.getCurrent());
        pagination.put("pageSize", approvalPage.getSize());
        pagination.put("total", approvalPage.getTotal());
        pagination.put("totalPages", approvalPage.getPages());

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("pagination", pagination);

        return ApiResponse.success(result);
    }

    /**
     * 获取审批统计
     * GET /api/v1/approvals/stats
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        log.info("获取审批统计: userId={}", userId);
        return ApiResponse.success(approvalService.getStats(userId));
    }

    /**
     * 提交审批决策
     * POST /api/v1/approvals/{approvalNo}/decision
     */
    @PostMapping("/{approvalNo}/decision")
    public ApiResponse<Map<String, Object>> decide(
            @PathVariable String approvalNo,
            @RequestBody Map<String, String> request,
            HttpServletRequest servletRequest) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        String decision = request.get("decision");
        String comment = request.get("comment");
        String operatorIp = ClientIpResolver.resolve(servletRequest);
        log.info("审批决策: approvalNo={}, decision={}, userId={}, operatorIp={}", approvalNo, decision, userId, operatorIp);

        Approval approval = approvalService.decide(approvalNo, decision, comment, userId, userName, operatorIp);
        return ApiResponse.success(approvalService.toResponse(approval));
    }

    /**
     * 创建审批请求（内部接口，由 Agent/Worker 调用）
     * POST /api/v1/approvals/create
     */
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createApproval(@RequestBody Map<String, String> request) {
        log.info("创建审批请求: taskNo={}, action={}", request.get("taskNo"), request.get("action"));

        Approval approval = approvalService.createApproval(
                request.get("taskNo"),
                request.get("taskTitle"),
                request.get("action"),
                request.get("description"),
                request.get("riskLevel"),
                request.getOrDefault("creatorId", "agent"),
                request.getOrDefault("creatorName", "AI Agent")
        );

        return ApiResponse.success(approvalService.toResponse(approval));
    }
}

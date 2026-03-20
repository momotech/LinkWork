package com.linkwork.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.ApiResponse;
import com.linkwork.common.ClientIpResolver;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.dto.TaskGitTokenResponse;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.dto.TaskShareCreateRequest;
import com.linkwork.model.dto.TaskShareLinkResponse;
import com.linkwork.model.entity.Task;
import com.linkwork.service.TaskGitTokenService;
import com.linkwork.service.TaskShareLinkService;
import com.linkwork.service.TaskService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务控制器
 * 对应 docs/back/api-design.md 3.1 任务执行模块
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskGitTokenService taskGitTokenService;
    private final TaskShareLinkService taskShareLinkService;

    /**
     * 启动 AI 任务
     * POST /api/v1/tasks
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createTask(
            @Valid @RequestBody TaskCreateRequest request,
            HttpServletRequest servletRequest) {

        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        String creatorIp = ClientIpResolver.resolve(servletRequest);
        log.info("创建任务请求: prompt={}, roleId={}, modelId={}, userId={}, creatorIp={}",
                request.getPrompt(), request.getRoleId(), request.getModelId(), userId, creatorIp);

        Task task = taskService.createTask(request, userId, userName, creatorIp);
        TaskResponse taskResponse = taskService.toResponse(task);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskNo());
        result.put("status", task.getStatus().getCode());
        result.put("estimatedOutput", taskResponse.getEstimatedOutput());
        result.put("deliveryMode", taskResponse.getDeliveryMode());
        result.put("branchName", taskResponse.getBranchName());

        return ApiResponse.success(result);
    }

    /**
     * 获取任务详情
     * GET /api/v1/tasks/{taskNo}
     */
    @GetMapping("/{taskNo}")
    public ApiResponse<TaskResponse> getTask(@PathVariable String taskNo) {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        log.info("获取任务详情: taskNo={}, userId={}", taskNo, userId);

        Task task = taskService.getTaskByNo(taskNo, userId);
        TaskResponse response = taskService.toResponse(task);

        return ApiResponse.success(response);
    }

    /**
     * zzd 按任务 ID 获取 Git token（服务到服务）
     * GET /api/v1/tasks/{taskNo}/git-token
     */
    @GetMapping("/{taskNo}/git-token")
    public ResponseEntity<ApiResponse<TaskGitTokenResponse>> getTaskGitToken(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        // NOTE(temporary): API 端联调阶段先跳过 zzd 服务鉴权，优先保障任务链路可用。
        // 后续 API 端启用服务鉴权后，恢复 bearer token 校验逻辑。
        TaskGitTokenResponse response = taskGitTokenService.getTaskGitToken(taskNo);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 获取任务列表
     * GET /api/v1/tasks
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listTasks(
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }

        log.info("获取任务列表: roleId={}, status={}, page={}, pageSize={}, userId={}", roleId, status, page, pageSize, userId);

        Page<Task> taskPage = taskService.listTasks(roleId, status, page, pageSize, userId);
        List<TaskResponse> items = taskService.toResponseList(taskPage.getRecords());

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", taskPage.getCurrent());
        pagination.put("pageSize", taskPage.getSize());
        pagination.put("total", taskPage.getTotal());
        pagination.put("totalPages", taskPage.getPages());

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("pagination", pagination);

        return ApiResponse.success(result);
    }

    /**
     * 任务完成回写（Worker 回调）
     * POST /api/v1/tasks/{taskNo}/complete
     */
    @PostMapping("/{taskNo}/complete")
    public ApiResponse<Map<String, Object>> completeTask(
            @PathVariable String taskNo,
            @Valid @RequestBody TaskCompleteRequest request) {
        log.info("任务完成回写: taskNo={}, status={}, tokensUsed={}, durationMs={}",
                taskNo, request.getStatus(), request.getTokensUsed(), request.getDurationMs());

        Task task = taskService.completeTask(taskNo, request);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskNo());
        result.put("status", task.getStatus().getCode());
        result.put("tokensUsed", task.getTokensUsed());
        result.put("durationMs", task.getDurationMs());

        return ApiResponse.success(result);
    }

    /**
     * 终止任务（主接口）
     * POST /api/v1/tasks/{taskNo}/terminate
     */
    @PostMapping("/{taskNo}/terminate")
    public ApiResponse<Map<String, Object>> terminateTask(
            @PathVariable String taskNo) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        log.info("终止任务: {}, userId={}", taskNo, userId);

        Task task = taskService.abortTask(taskNo, userId, userName);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskNo());
        result.put("status", "terminate_requested");

        return ApiResponse.success(result);
    }

    /**
     * 终止任务（兼容接口）
     * POST /api/v1/tasks/{taskNo}/abort
     */
    @PostMapping("/{taskNo}/abort")
    public ApiResponse<Map<String, Object>> abortTask(
            @PathVariable String taskNo) {
        return terminateTask(taskNo);
    }

    /**
     * 创建任务临时分享链接
     * POST /api/v1/tasks/{taskNo}/share-link
     */
    @PostMapping("/{taskNo}/share-link")
    public ApiResponse<TaskShareLinkResponse> createTaskShareLink(
            @PathVariable String taskNo,
            @RequestBody(required = false) TaskShareCreateRequest request) {
        String userId = UserContext.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new IllegalStateException("用户未登录或登录态失效");
        }
        Integer expireHours = request == null ? null : request.getExpireHours();
        log.info("创建任务分享链接: taskNo={}, userId={}, expireHours={}", taskNo, userId, expireHours);
        TaskShareLinkResponse response = taskShareLinkService.createShareLink(taskNo, userId, expireHours);
        return ApiResponse.success(response);
    }
}

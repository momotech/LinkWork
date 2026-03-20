package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.Task;
import com.linkwork.service.TaskShareLinkService;
import com.linkwork.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公共任务查询接口（免鉴权最小返回）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/tasks")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PublicTaskController {

    private final TaskService taskService;
    private final TaskShareLinkService taskShareLinkService;

    /**
     * 按任务 ID 查询任务使用模型
     * GET /api/v1/public/tasks/{taskNo}/model
     */
    @GetMapping("/{taskNo}/model")
    public ApiResponse<Map<String, Object>> getTaskModel(@PathVariable String taskNo) {
        log.info("公共接口查询任务模型: taskNo={}", taskNo);

        Task task = taskService.getTaskByNo(taskNo);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskNo());
        result.put("modelId", task.getSelectedModel());
        result.put("userId", task.getCreatorId());
        return ApiResponse.success(result);
    }

    /**
     * 访客通过分享 token 查看任务详情
     * GET /api/v1/public/tasks/{taskNo}/share-detail?token=...
     */
    @GetMapping("/{taskNo}/share-detail")
    public ApiResponse<TaskResponse> getSharedTaskDetail(
            @PathVariable String taskNo,
            @RequestParam("token") String token) {
        log.info("公共接口查询任务分享详情: taskNo={}", taskNo);

        taskShareLinkService.validateShareToken(taskNo, token);
        Task task = taskService.getTaskByNo(taskNo);
        return ApiResponse.success(taskService.toShareResponse(task));
    }
}

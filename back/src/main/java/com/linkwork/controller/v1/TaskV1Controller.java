package com.linkwork.controller.v1;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import com.linkwork.service.TaskV1Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class TaskV1Controller {

    private final TaskV1Service taskService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(
            @Valid @RequestBody TaskCreateRequest request,
            HttpServletRequest servletRequest) {
        String userId = UserContext.getCurrentUserId();
        String username = UserContext.getCurrentUserName();
        String ip = servletRequest.getRemoteAddr();

        LinkworkTask task = taskService.createTask(request, userId, username, ip, "MANUAL", null);
        TaskResponse resp = taskService.toResponse(task);

        Map<String, Object> data = new HashMap<>();
        data.put("taskNo", task.getTaskNo());
        data.put("status", task.getStatus());
        data.put("task", resp);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTasks(
            @RequestParam(required = false) Long workstationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        Page<LinkworkTask> result = taskService.listTasks(workstationId, status, page, pageSize, userId);
        List<TaskResponse> items = taskService.toResponseList(result.getRecords());

        Map<String, Object> pagination = Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages());
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("items", items, "pagination", pagination)));
    }

    @GetMapping("/{taskNo}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String taskNo) {
        String userId = UserContext.getCurrentUserId();
        LinkworkTask task = taskService.getTaskByNo(taskNo, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", taskService.toResponse(task)));
    }

    @PostMapping("/{taskNo}/complete")
    public ResponseEntity<Map<String, Object>> completeTask(
            @PathVariable String taskNo,
            @RequestBody TaskCompleteRequest request) {
        LinkworkTask task = taskService.completeTask(taskNo, request);
        return ResponseEntity.ok(Map.of("code", 0, "data", taskService.toResponse(task)));
    }

    @PostMapping("/{taskNo}/abort")
    public ResponseEntity<Map<String, Object>> abortTask(@PathVariable String taskNo) {
        String userId = UserContext.getCurrentUserId();
        String username = UserContext.getCurrentUserName();
        LinkworkTask task = taskService.abortTask(taskNo, userId, username);
        return ResponseEntity.ok(Map.of("code", 0, "data", taskService.toResponse(task)));
    }

    @PutMapping("/{taskNo}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable String taskNo,
            @RequestBody Map<String, String> body) {
        TaskStatus status = TaskStatus.valueOf(body.get("status").toUpperCase());
        LinkworkTask task = taskService.updateStatus(taskNo, status);
        return ResponseEntity.ok(Map.of("code", 0, "data", taskService.toResponse(task)));
    }
}

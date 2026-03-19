package com.linkwork.controller.v1;

import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.service.TaskShareLinkService;
import com.linkwork.service.TaskV1Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/public/tasks")
@RequiredArgsConstructor
public class PublicTaskController {

    private final TaskV1Service taskService;
    private final TaskShareLinkService shareService;

    @GetMapping("/{taskNo}/model")
    public ResponseEntity<Map<String, Object>> getTaskModel(@PathVariable String taskNo) {
        LinkworkTask task = taskService.getTaskByNo(taskNo);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskNo());
        result.put("modelId", task.getSelectedModel());
        result.put("userId", task.getCreatorId());
        return ResponseEntity.ok(Map.of("code", 0, "data", result));
    }

    @GetMapping("/{taskNo}/share-detail")
    public ResponseEntity<Map<String, Object>> getSharedTaskDetail(
            @PathVariable String taskNo,
            @RequestParam("token") String token) {
        shareService.validateShareToken(taskNo, token);
        LinkworkTask task = taskService.getTaskByNo(taskNo);
        TaskResponse response = taskService.toShareResponse(task);
        return ResponseEntity.ok(Map.of("code", 0, "data", response));
    }
}

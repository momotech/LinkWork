package com.linkwork.controller.v1;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.WorkstationEntity;
import com.linkwork.service.WorkstationV1Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/workstations")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class WorkstationV1Controller {

    private final WorkstationV1Service workstationService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "all") String scope,
            @RequestParam(required = false) String status) {
        String userId = UserContext.getCurrentUserId();
        Page<WorkstationEntity> result = workstationService.listWorkstations(page, pageSize, query, category, scope, status, userId);

        List<Long> wsIds = result.getRecords().stream().map(WorkstationEntity::getId).collect(Collectors.toList());
        Map<Long, Long> favCountMap = wsIds.isEmpty() ? Map.of() : workstationService.queryFavoriteCountMap(wsIds);

        List<Map<String, Object>> items = result.getRecords().stream().map(ws -> {
            Map<String, Object> item = toMap(ws);
            item.put("favoriteCount", favCountMap.getOrDefault(ws.getId(), 0L));
            item.put("isFavorite", workstationService.isFavorite(ws.getId(), userId));
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> pagination = Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages());
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("items", items, "pagination", pagination)));
    }

    @GetMapping("/hot")
    public ResponseEntity<Map<String, Object>> hot(@RequestParam(defaultValue = "10") int limit) {
        String userId = UserContext.getCurrentUserId();
        List<WorkstationEntity> list = workstationService.listHotWorkstations(limit, userId);
        List<Map<String, Object>> items = list.stream().map(this::toMap).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "data", items));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody WorkstationEntity ws) {
        String userId = UserContext.getCurrentUserId();
        String username = UserContext.getCurrentUserName();
        WorkstationEntity created = workstationService.createWorkstation(ws, userId, username);
        return ResponseEntity.ok(Map.of("code", 0, "data", toMap(created)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        WorkstationEntity ws = workstationService.getForRead(id, userId);
        Map<String, Object> data = toMap(ws);
        data.put("isFavorite", workstationService.isFavorite(id, userId));
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody WorkstationEntity ws) {
        String userId = UserContext.getCurrentUserId();
        WorkstationEntity updated = workstationService.updateWorkstation(id, ws, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", toMap(updated)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        workstationService.deleteWorkstation(id, userId);
        return ResponseEntity.ok(Map.of("code", 0, "message", "deleted"));
    }

    @PostMapping("/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        String userId = UserContext.getCurrentUserId();
        boolean result = workstationService.toggleFavorite(id, userId, Boolean.TRUE.equals(body.get("favorite")));
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("isFavorite", result)));
    }

    private Map<String, Object> toMap(WorkstationEntity ws) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ws.getId());
        map.put("workstationNo", ws.getWorkstationNo());
        map.put("name", ws.getName());
        map.put("description", ws.getDescription());
        map.put("category", ws.getCategory());
        map.put("icon", ws.getIcon());
        map.put("image", ws.getImage());
        map.put("prompt", ws.getPrompt());
        map.put("status", ws.getStatus());
        map.put("configJson", ws.getConfigJson());
        map.put("isPublic", ws.getIsPublic());
        map.put("maxEmployees", ws.getMaxEmployees());
        map.put("creatorId", ws.getCreatorId());
        map.put("creatorName", ws.getCreatorName());
        map.put("createdAt", ws.getCreatedAt());
        map.put("updatedAt", ws.getUpdatedAt());
        return map;
    }
}

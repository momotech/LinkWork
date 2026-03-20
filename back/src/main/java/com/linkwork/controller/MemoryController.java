package com.linkwork.controller;

import com.linkwork.model.dto.MemoryIndexBatchRequest;
import com.linkwork.model.dto.MemoryIngestRequest;
import com.linkwork.model.dto.MemorySearchRequest;
import com.linkwork.service.memory.MemoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    @PostMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @Valid @RequestBody MemorySearchRequest request) {
        List<Map<String, Object>> results = memoryService.search(
                workstationId, userId, request.getQuery(), request.getTopK());
        return ResponseEntity.ok(results);
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @Valid @RequestBody MemoryIngestRequest request) {
        memoryService.ingest(workstationId, userId, request.getContent(), request.getSource());
        return ResponseEntity.ok(Map.of("status", "queued"));
    }

    @PostMapping("/index/file")
    public ResponseEntity<Map<String, String>> indexFile(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @RequestParam String filePath) {
        memoryService.triggerIndexFile(workstationId, userId, filePath);
        return ResponseEntity.ok(Map.of("status", "queued"));
    }

    @PostMapping("/index/batch")
    public ResponseEntity<Map<String, Object>> indexBatch(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @Valid @RequestBody MemoryIndexBatchRequest request) {
        int count = memoryService.triggerBatchIndex(workstationId, userId, request.getFilePaths());
        return ResponseEntity.ok(Map.of("status", "queued", "fileCount", count));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Map<String, Object>>> recent(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(memoryService.recent(workstationId, userId, limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam String workstationId,
            @RequestParam String userId) {
        return ResponseEntity.ok(memoryService.stats(workstationId, userId));
    }

    @DeleteMapping("/source")
    public ResponseEntity<Map<String, String>> deleteSource(
            @RequestParam String workstationId,
            @RequestParam String userId,
            @RequestParam String source) {
        memoryService.deleteSource(workstationId, userId, source);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}

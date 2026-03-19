package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.model.dto.*;
import com.linkwork.service.CronJobV1Service;
import com.linkwork.service.CronJobExecutor;
import com.linkwork.model.entity.LinkworkCronJob;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cron-jobs")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class CronJobV1Controller {

    private final CronJobV1Service cronJobService;
    private final CronJobExecutor cronJobExecutor;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CronJobCreateRequest request) {
        String userId = UserContext.getCurrentUserId();
        String username = UserContext.getCurrentUserName();
        CronJobResponse resp = cronJobService.create(request, userId, username);
        return ResponseEntity.ok(Map.of("code", 0, "data", resp));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long workstationId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String scheduleType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> data = cronJobService.listMine(userId, workstationId, enabled, scheduleType, keyword, page, pageSize);
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        CronJobResponse resp = cronJobService.getDetail(id, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", resp));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody CronJobUpdateRequest request) {
        String userId = UserContext.getCurrentUserId();
        CronJobResponse resp = cronJobService.update(id, request, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", resp));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id, @Valid @RequestBody CronJobToggleRequest request) {
        String userId = UserContext.getCurrentUserId();
        CronJobResponse resp = cronJobService.toggle(id, request, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", resp));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        cronJobService.delete(id, userId);
        return ResponseEntity.ok(Map.of("code", 0, "message", "deleted"));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<Map<String, Object>> listRuns(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> data = cronJobService.listRuns(id, userId, page, pageSize);
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<Map<String, Object>> trigger(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        LinkworkCronJob job = cronJobService.getOwnedJob(id, userId);
        var run = cronJobExecutor.dispatchManual(job);
        return ResponseEntity.ok(Map.of("code", 0, "data", cronJobService.toRunResponse(run)));
    }

    @PostMapping("/schedule/preview")
    public ResponseEntity<Map<String, Object>> previewSchedule(@Valid @RequestBody CronSchedulePreviewRequest request) {
        List<String> times = cronJobService.previewSchedule(
                request.getCronExpr(), request.getCronExpr(), null, null, request.getTimezone(), request.getCount());
        return ResponseEntity.ok(Map.of("code", 0, "data", times));
    }
}

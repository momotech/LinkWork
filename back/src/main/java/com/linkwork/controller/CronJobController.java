package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.CronJobCreateRequest;
import com.linkwork.model.dto.CronJobResponse;
import com.linkwork.model.dto.CronJobRunResponse;
import com.linkwork.model.dto.CronJobToggleRequest;
import com.linkwork.model.dto.CronJobUpdateRequest;
import com.linkwork.model.dto.CronSchedulePreviewRequest;
import com.linkwork.model.entity.CronJob;
import com.linkwork.model.entity.CronJobRun;
import com.linkwork.service.CronJobExecutor;
import com.linkwork.service.CronJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cron-jobs")
@RequiredArgsConstructor
public class CronJobController {

    private final CronJobService cronJobService;
    private final CronJobExecutor cronJobExecutor;

    @PostMapping
    public ApiResponse<CronJobResponse> create(@Valid @RequestBody CronJobCreateRequest request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        return ApiResponse.success(cronJobService.create(request, userId, userName));
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody CronSchedulePreviewRequest request) {
        return ApiResponse.success(Map.of(
                "nextFireTimes",
                cronJobService.previewSchedule(request.getScheduleType(), request.getCronExpr(), request.getIntervalMs(),
                        request.getRunAt(), request.getTimezone(), request.getLimit())
        ));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> listMine(
            @RequestParam(value = "scope", defaultValue = "mine") String scope,
            @RequestParam(value = "roleId", required = false) Long roleId,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "scheduleType", required = false) String scheduleType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        if (StringUtils.hasText(scope) && !"mine".equalsIgnoreCase(scope.trim())) {
            throw new IllegalArgumentException("MVP 仅支持 scope=mine");
        }
        return ApiResponse.success(cronJobService.listMine(
                UserContext.getCurrentUserId(), roleId, enabled, scheduleType, keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<CronJobResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(cronJobService.getDetail(id, UserContext.getCurrentUserId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<CronJobResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody CronJobUpdateRequest request) {
        return ApiResponse.success(cronJobService.update(id, request, UserContext.getCurrentUserId()));
    }

    @PutMapping("/{id}/toggle")
    public ApiResponse<CronJobResponse> toggle(@PathVariable Long id,
                                               @Valid @RequestBody CronJobToggleRequest request) {
        return ApiResponse.success(cronJobService.toggle(id, request, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{id}/trigger")
    public ApiResponse<CronJobRunResponse> trigger(@PathVariable Long id) {
        CronJob job = cronJobService.getOwnedJob(id, UserContext.getCurrentUserId());
        CronJobRun run = cronJobExecutor.dispatchManual(job);
        return ApiResponse.success(cronJobService.toRunResponse(run));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<Map<String, Object>> listRuns(@PathVariable Long id,
                                                     @RequestParam(value = "page", defaultValue = "1") Integer page,
                                                     @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(cronJobService.listRuns(id, UserContext.getCurrentUserId(), page, pageSize));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        cronJobService.delete(id, UserContext.getCurrentUserId());
        return ApiResponse.success();
    }
}

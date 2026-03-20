package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.model.dto.ReportExportFieldResponse;
import com.linkwork.model.dto.ReportExportRequest;
import com.linkwork.service.ReportExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * 运维报表导出控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ReportExportController {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final ReportExportService reportExportService;

    @Value("${robot.k8s-monitor.allowed-users:}")
    private String allowedUsersConfig;

    @GetMapping("/export/fields")
    public ApiResponse<ReportExportFieldResponse> listExportFields(@RequestParam String type) {
        checkPermission();
        return ApiResponse.success(reportExportService.listFields(type));
    }

    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody ReportExportRequest request) {
        checkPermission();

        String normalizedType = "role".equalsIgnoreCase(request.getType()) ? "role" : "task";
        String fileName = normalizedType + "-report-" + LocalDateTime.now().format(FILE_TIME_FORMATTER) + ".csv";

        StreamingResponseBody responseBody = outputStream -> reportExportService.exportCsv(request, outputStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(responseBody);
    }

    private void checkPermission() {
        UserInfo user = UserContext.get();
        if (user == null) {
            throw new ForbiddenOperationException("未登录");
        }
        if (!isUserAllowed(user)) {
            log.warn("报表导出访问被拒绝: userId={}, workId={}, name={}", user.getUserId(), user.getWorkId(), user.getName());
            throw new ForbiddenOperationException("无权访问报表导出功能");
        }
    }

    private boolean isUserAllowed(UserInfo user) {
        Set<String> allowed = getAllowedUsers();
        if (user.getWorkId() != null && allowed.contains(user.getWorkId().trim())) {
            return true;
        }
        return user.getUserId() != null && allowed.contains(user.getUserId().trim());
    }

    private Set<String> getAllowedUsers() {
        Set<String> set = new HashSet<>();
        for (String id : allowedUsersConfig.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }
}

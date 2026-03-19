package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportExportController {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final ReportExportService reportExportService;

    @Value("${linkwork.k8s-monitor.allowed-users:}")
    private String allowedUsersConfig;

    @GetMapping("/export/fields")
    public ResponseEntity<Map<String, Object>> listExportFields(@RequestParam String type) {
        checkPermission();
        return ResponseEntity.ok(Map.of("code", 0, "data", reportExportService.listFields(type)));
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
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(responseBody);
    }

    private void checkPermission() {
        UserInfo user = UserContext.get();
        if (user == null) throw new SecurityException("Not authenticated");
        if (allowedUsersConfig != null && !allowedUsersConfig.isBlank()) {
            Set<String> allowed = new HashSet<>();
            for (String id : allowedUsersConfig.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) allowed.add(trimmed);
            }
            if (!allowed.isEmpty()) {
                boolean ok = (user.getWorkId() != null && allowed.contains(user.getWorkId().trim()))
                        || (user.getUserId() != null && allowed.contains(user.getUserId().trim()));
                if (!ok) throw new SecurityException("Access denied to report export");
            }
        }
    }
}

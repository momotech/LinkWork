package com.linkwork.controller;

import com.linkwork.agent.sandbox.core.model.SandboxResult;
import com.linkwork.common.api.ApiResponse;
import com.linkwork.model.dto.GeneratedSpec;
import com.linkwork.model.dto.ScaleRequest;
import com.linkwork.model.dto.ScaleResult;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.dto.ServiceStatusResponse;
import com.linkwork.service.SandboxScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleController {

    private final SandboxScheduleService sandboxScheduleService;

    public ScheduleController(SandboxScheduleService sandboxScheduleService) {
        this.sandboxScheduleService = sandboxScheduleService;
    }

    @PostMapping("/build")
    public ResponseEntity<ApiResponse<ServiceBuildResult>> build(@Valid @RequestBody ServiceBuildRequest request) {
        ServiceBuildResult result = sandboxScheduleService.build(request);
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(result.getErrorMessage()));
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<GeneratedSpec>> preview(@Valid @RequestBody ServiceBuildRequest request) {
        return ResponseEntity.ok(ApiResponse.success(sandboxScheduleService.preview(request)));
    }

    @GetMapping("/status/{serviceId}")
    public ResponseEntity<ApiResponse<ServiceStatusResponse>> status(@PathVariable("serviceId") String serviceId,
                                                                     @RequestParam(value = "namespace", required = false) String namespace) {
        return ResponseEntity.ok(ApiResponse.success(sandboxScheduleService.status(serviceId, namespace)));
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> delete(@PathVariable("serviceId") String serviceId,
                                       @RequestParam(value = "namespace", required = false) String namespace) {
        sandboxScheduleService.delete(serviceId, namespace);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{serviceId}/stop")
    public ResponseEntity<ApiResponse<String>> stop(@PathVariable("serviceId") String serviceId,
                                                    @RequestParam(value = "graceful", defaultValue = "true") boolean graceful,
                                                    @RequestParam(value = "namespace", required = false) String namespace) {
        SandboxResult stopResult = sandboxScheduleService.stop(serviceId, graceful, namespace);
        if (stopResult.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("STOPPED(graceful=" + graceful + ")"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(stopResult.getErrorMessage()));
    }

    @PostMapping("/{serviceId}/scale-down")
    public ResponseEntity<ApiResponse<ScaleResult>> scaleDown(@PathVariable("serviceId") String serviceId,
                                                              @RequestBody(required = false) ScaleRequest request) {
        ScaleResult result = sandboxScheduleService.scaleDown(serviceId, request);
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(result.getErrorMessage()));
    }

    @PostMapping("/{serviceId}/scale-up")
    public ResponseEntity<ApiResponse<ScaleResult>> scaleUp(@PathVariable("serviceId") String serviceId,
                                                            @RequestBody(required = false) ScaleRequest request) {
        ScaleResult result = sandboxScheduleService.scaleUp(serviceId, request);
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(result.getErrorMessage()));
    }

    @PostMapping("/{serviceId}/scale")
    public ResponseEntity<ApiResponse<ScaleResult>> scale(@PathVariable("serviceId") String serviceId,
                                                          @RequestBody(required = false) ScaleRequest request) {
        ScaleResult result = sandboxScheduleService.scale(serviceId, request);
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(result.getErrorMessage()));
    }

    @GetMapping("/{serviceId}/scale")
    public ResponseEntity<ApiResponse<ScaleResult>> getScaleStatus(@PathVariable("serviceId") String serviceId) {
        return ResponseEntity.ok(ApiResponse.success(sandboxScheduleService.scaleStatus(serviceId)));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("OK"));
    }
}

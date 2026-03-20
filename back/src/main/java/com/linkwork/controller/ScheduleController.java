package com.linkwork.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.linkwork.common.ApiResponse;
import com.linkwork.model.dto.*;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import com.linkwork.service.K8sOrchestrator;
import com.linkwork.service.ServiceResumeService;
import com.linkwork.service.ServiceScaleService;
import com.linkwork.service.ServiceScheduleService;
import com.linkwork.service.ServiceSnapshotService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 调度控制器
 */
@RestController
@RequestMapping("/api/v1/schedule")
@Validated
@Slf4j
public class ScheduleController {
    
    private final ServiceScheduleService scheduleService;
    private final K8sOrchestrator orchestrator;
    private final ServiceSnapshotService snapshotService;
    private final ServiceResumeService resumeService;
    private final ServiceScaleService scaleService;
    private final ObjectMapper yamlMapper;
    
    public ScheduleController(ServiceScheduleService scheduleService, 
                             K8sOrchestrator orchestrator,
                             ServiceSnapshotService snapshotService,
                             ServiceResumeService resumeService,
                             ServiceScaleService scaleService) {
        this.scheduleService = scheduleService;
        this.orchestrator = orchestrator;
        this.snapshotService = snapshotService;
        this.resumeService = resumeService;
        this.scaleService = scaleService;
        this.yamlMapper = new ObjectMapper(new YAMLFactory()
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }
    
    @PostMapping("/build")
    public ResponseEntity<ApiResponse<ServiceBuildResult>> build(
            @Valid @RequestBody ServiceBuildRequest request) {
        
        log.info("Received build request for service {}", request.getServiceId());
        
        if (request.getDeployMode() == DeployMode.COMPOSE) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("COMPOSE 模式请使用 /compose/generate 接口"));
        }
        
        if (request.getPodMode() == PodMode.SIDECAR && request.getDeployMode() != DeployMode.K8S) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Sidecar 模式仅支持 K8s 部署"));
        }
        
        ServiceBuildResult result = scheduleService.build(request);
        
        if (result.isSuccess()) {
            // 判断状态：BUILDING 返回 202 Accepted，SUCCESS 返回 200 OK
            if ("BUILDING".equals(result.getStatus())) {
                log.info("Build task submitted for service {}, returning 202 Accepted", request.getServiceId());
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(result));
            } else {
                snapshotService.saveSnapshot(request, result);
                return ResponseEntity.ok(ApiResponse.success(result));
            }
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getErrorMessage()));
        }
    }
    
    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<GeneratedSpec>> preview(
            @Valid @RequestBody ServiceBuildRequest request) {
        
        log.info("Received preview request for service {}", request.getServiceId());
        
        GeneratedSpec spec = scheduleService.preview(request);
        return ResponseEntity.ok(ApiResponse.success(spec));
    }
    
    @PostMapping("/preview/yaml")
    public ResponseEntity<String> previewYaml(
            @Valid @RequestBody ServiceBuildRequest request) {
        
        log.info("Received preview YAML request for service {}", request.getServiceId());
        
        try {
            GeneratedSpec spec = scheduleService.preview(request);
            
            StringBuilder yaml = new StringBuilder();
            yaml.append("# ========== PodGroup ==========\n");
            yaml.append(yamlMapper.writeValueAsString(spec.getPodGroupSpec()));
            yaml.append("\n---\n");
            
            for (int i = 0; i < spec.getPodSpecs().size(); i++) {
                yaml.append("# ========== Pod ").append(i).append(" ==========\n");
                yaml.append(yamlMapper.writeValueAsString(spec.getPodSpecs().get(i)));
                if (i < spec.getPodSpecs().size() - 1) {
                    yaml.append("\n---\n");
                }
            }
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml"))
                .body(yaml.toString());
        } catch (Exception e) {
            log.error("Failed to generate YAML: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error: " + e.getMessage());
        }
    }
    
    @PostMapping("/config")
    public ResponseEntity<ApiResponse<MergedConfig>> getMergedConfig(
            @Valid @RequestBody ServiceBuildRequest request) {
        
        log.info("Received config request for service {}", request.getServiceId());
        
        MergedConfig config = scheduleService.getMergedConfig(request);
        return ResponseEntity.ok(ApiResponse.success(config));
    }
    
    @GetMapping("/status/{serviceId}")
    public ResponseEntity<ApiResponse<ServiceStatusResponse>> getStatus(
            @PathVariable String serviceId) {
        
        ServiceStatusResponse status = orchestrator.getServiceStatus(serviceId);
        
        if (status == null || status.getPodGroupStatus() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Service not found: " + serviceId));
        }
        
        return ResponseEntity.ok(ApiResponse.success(status));
    }
    
    @PostMapping("/resume/{serviceId}")
    public ResponseEntity<ApiResponse<ServiceResumeResult>> resume(
            @PathVariable String serviceId) {
        
        log.info("Received resume request for service {}", serviceId);
        
        ServiceResumeResult result = resumeService.resume(serviceId);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else if (result.isRequireFullRequest()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(result.getMessage()));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getMessage()));
        }
    }
    
//    @PostMapping("/{serviceId}/stop")
//    public ResponseEntity<ApiResponse<StopResult>> stop(
//            @PathVariable String serviceId,
//            @RequestParam(defaultValue = "true") boolean graceful) {
//
//        log.info("Received stop request for service {}, graceful={}", serviceId, graceful);
//
//        StopResult result = orchestrator.stopService(serviceId, graceful);
//
//        if (result.isSuccess()) {
//            snapshotService.onServiceShutdown(serviceId);
//            return ResponseEntity.ok(ApiResponse.success(result));
//        } else {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                .body(ApiResponse.error(result.getErrorMessage()));
//        }
//    }
    
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> delete(@PathVariable String serviceId) {
        log.info("Received delete request for service {}", serviceId);
        
        orchestrator.cleanupService(serviceId);
        
        return ResponseEntity.noContent().build();
    }
    
    /**
     * 生成 Compose 构建包（zip 下载）
     *
     * 返回 zip 包含 docker-compose.yaml、Dockerfile、build.sh 等构建所需文件。
     * 用户解压后执行 docker compose up --build -d 即可在本地构建镜像并启动服务。
     */
    @PostMapping("/compose/generate")
    public ResponseEntity<?> generateCompose(
            @Valid @RequestBody ServiceBuildRequest request) {

        log.info("Received compose package request for service {}", request.getServiceId());

        request.setPodMode(PodMode.ALONE);
        request.setDeployMode(DeployMode.COMPOSE);

        ServiceBuildResult result = scheduleService.generateComposePackage(request);

        if (!result.isSuccess()) {
            log.error("Compose package generation failed for service {}: {}",
                request.getServiceId(), result.getErrorMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getErrorMessage()));
        }

        String filename = "ai-worker-" + request.getServiceId() + ".tar.gz";
        log.info("Compose package ready for service {}, filename: {}", request.getServiceId(), filename);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/gzip"))
            .body(new ByteArrayResource(result.getComposeTar()));
    }
    
    @PostMapping("/{serviceId}/scale-down")
    public ResponseEntity<ApiResponse<ScaleResult>> scaleDown(
            @PathVariable String serviceId,
            @RequestBody ScaleRequest request) {
        
        String podName = request != null ? request.getPodName() : null;
        String source = request != null ? request.getSource() : "api";
        
        if (podName == null || podName.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("podName is required for scale-down"));
        }
        
        log.info("Received scale-down request for service {}, podName={}, source={}", 
            serviceId, podName, source);
        
        ScaleResult result = scaleService.scaleDown(serviceId, podName, source);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getErrorMessage()));
        }
    }
    
    @PostMapping("/{serviceId}/scale-up")
    public ResponseEntity<ApiResponse<ScaleResult>> scaleUp(
            @PathVariable String serviceId,
            @RequestBody(required = false) ScaleRequest request) {
        
        Integer targetPodCount = request != null ? request.getTargetPodCount() : null;
        String source = request != null ? request.getSource() : "api";
        
        log.info("Received scale-up request for service {}, targetPodCount={}, source={}", 
            serviceId, targetPodCount, source);
        
        ScaleResult result = scaleService.scaleUp(serviceId, targetPodCount, source);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getErrorMessage()));
        }
    }
    
    @PostMapping("/{serviceId}/scale")
    public ResponseEntity<ApiResponse<ScaleResult>> scale(
            @PathVariable String serviceId,
            @RequestBody ScaleRequest request) {
        
        if (request.getTargetPodCount() == null || request.getTargetPodCount() < 0) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("targetPodCount is required and must be >= 0"));
        }
        
        String source = request.getSource() != null ? request.getSource() : "api";
        
        log.info("Received scale request for service {}, targetPodCount={}, source={}", 
            serviceId, request.getTargetPodCount(), source);
        
        ScaleResult result = scaleService.scale(serviceId, request.getTargetPodCount(), source);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(result.getErrorMessage()));
        }
    }
    
    @GetMapping("/{serviceId}/scale")
    public ResponseEntity<ApiResponse<ScaleResult>> getScaleStatus(
            @PathVariable String serviceId) {
        
        ScaleResult result = scaleService.getScaleStatus(serviceId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("OK"));
    }
}

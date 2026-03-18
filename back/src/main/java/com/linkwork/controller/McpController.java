package com.linkwork.controller;

import com.momo.agent.mcp.core.model.McpDiscoverResponse;
import com.momo.agent.mcp.core.model.McpProbeResponse;
import com.momo.agent.mcp.core.model.McpToolCallResponse;
import com.linkwork.common.api.ApiResponse;
import com.linkwork.model.dto.McpEndpointRequest;
import com.linkwork.model.dto.McpToolCallRequest;
import com.linkwork.service.McpClientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private final McpClientService mcpClientService;

    public McpController(McpClientService mcpClientService) {
        this.mcpClientService = mcpClientService;
    }

    @PostMapping("/discover")
    public ResponseEntity<ApiResponse<McpDiscoverResponse>> discover(@RequestBody McpEndpointRequest request) {
        McpDiscoverResponse response = mcpClientService.discover(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(40000, response.getMessage(), response));
    }

    @PostMapping("/probe")
    public ResponseEntity<ApiResponse<McpProbeResponse>> probe(@RequestBody McpEndpointRequest request) {
        McpProbeResponse response = mcpClientService.probe(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(40000, response.getMessage(), response));
    }

    @PostMapping("/call")
    public ResponseEntity<ApiResponse<McpToolCallResponse>> call(@Valid @RequestBody McpToolCallRequest request) {
        McpToolCallResponse response = mcpClientService.callTool(
            request.getEndpoint(),
            request.getToolName(),
            request.getArguments()
        );
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(40000, response.getMessage(), response));
    }
}

package com.linkwork.controller.v1;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.LinkworkApproval;
import com.linkwork.service.ApprovalV1Service;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
@CrossOrigin(originPatterns = "*")
@RequiredArgsConstructor
public class ApprovalV1Controller {

    private final ApprovalV1Service approvalService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        String userId = UserContext.getCurrentUserId();
        Page<LinkworkApproval> result = approvalService.listApprovals(status, page, pageSize, userId);
        List<Map<String, Object>> items = approvalService.toResponseList(result.getRecords());
        Map<String, Object> pagination = Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages());
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("items", items, "pagination", pagination)));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        String userId = UserContext.getCurrentUserId();
        Map<String, Long> stats = approvalService.getStats(userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", stats));
    }

    @PostMapping("/{approvalNo}/decide")
    public ResponseEntity<Map<String, Object>> decide(
            @PathVariable String approvalNo,
            @RequestBody Map<String, String> body,
            HttpServletRequest servletRequest) {
        String userId = UserContext.getCurrentUserId();
        String username = UserContext.getCurrentUserName();
        String ip = servletRequest.getRemoteAddr();
        String decision = body.get("decision");
        String comment = body.get("comment");
        LinkworkApproval approval = approvalService.decide(approvalNo, decision, comment, userId, username, ip);
        return ResponseEntity.ok(Map.of("code", 0, "data", approvalService.toResponse(approval)));
    }
}

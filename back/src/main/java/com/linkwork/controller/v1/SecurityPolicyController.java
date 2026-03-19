package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.service.SecurityPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/security/policies")
@RequiredArgsConstructor
public class SecurityPolicyController {

    private final SecurityPolicyService policyService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPolicies() {
        return ResponseEntity.ok(Map.of("code", 0, "data", policyService.listPolicies()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPolicy(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("code", 0, "data", policyService.getPolicy(id)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPolicy(@RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        return ResponseEntity.ok(Map.of("code", 0, "data",
                policyService.createPolicy(request, userId, userName)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updatePolicy(@PathVariable Long id,
                                                             @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(Map.of("code", 0, "data", policyService.updatePolicy(id, request)));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> togglePolicy(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("code", 0, "data", policyService.togglePolicy(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePolicy(@PathVariable Long id) {
        policyService.deletePolicy(id);
        return ResponseEntity.ok(Map.of("code", 0, "message", "deleted"));
    }
}

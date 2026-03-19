package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.service.RoleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> listRoles(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int pageSize,
                                                      @RequestParam(required = false) String query,
                                                      @RequestParam(required = false) String category,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "all") String scope,
                                                      HttpServletRequest request) {
        String userId = currentUserId(request);
        return ApiResponse.success(roleService.listRoles(page, pageSize, query, category, scope, status, userId));
    }

    @GetMapping("/hot")
    public ApiResponse<Map<String, Object>> listHotRoles(@RequestParam(defaultValue = "4") int limit,
                                                         HttpServletRequest request) {
        List<Map<String, Object>> items = roleService.listHotRoles(limit, currentUserId(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("limit", Math.max(1, limit));
        return ApiResponse.success(data);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getRole(@PathVariable Long id,
                                                    HttpServletRequest request) {
        return ApiResponse.success(roleService.getRoleForRead(id, currentUserId(request)));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createRole(@RequestBody Map<String, Object> requestBody,
                                                       HttpServletRequest request) {
        return ApiResponse.success(roleService.createRole(requestBody, currentUserId(request), currentUserName(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateRole(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> requestBody,
                                                       HttpServletRequest request) {
        return ApiResponse.success(roleService.updateRole(id, requestBody, currentUserId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<Map<String, Object>> toggleFavorite(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, Object> requestBody) {
        boolean favorite = false;
        if (requestBody != null && requestBody.containsKey("favorite")) {
            Object raw = requestBody.get("favorite");
            if (raw instanceof Boolean bool) {
                favorite = bool;
            } else if (raw != null) {
                String normalized = String.valueOf(raw).trim().toLowerCase();
                favorite = "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
            }
        }
        roleService.toggleFavorite(id, favorite);
        return ApiResponse.success(Map.of("id", String.valueOf(id), "favorite", favorite));
    }

    private String currentUserId(HttpServletRequest request) {
        String value = request.getHeader("X-User-Id");
        return StringUtils.hasText(value) ? value : "anonymous";
    }

    private String currentUserName(HttpServletRequest request) {
        String value = request.getHeader("X-User-Name");
        return StringUtils.hasText(value) ? value : "anonymous";
    }
}

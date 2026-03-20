package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.context.UserContext;
import com.linkwork.service.SkillService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 技能控制器 - Git-based skill management
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/skills")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /**
     * 获取技能列表（分页）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> data = skillService.listSkills(page, pageSize, status, keyword, userId);
        return ApiResponse.success(data);
    }

    /**
     * 获取所有可用的技能（用于角色配置下拉选择）
     */
    @GetMapping("/available")
    public ApiResponse<List<Map<String, Object>>> listAvailable() {
        String userId = UserContext.getCurrentUserId();
        List<Map<String, Object>> data = skillService.listAllAvailable(userId);
        return ApiResponse.success(data);
    }

    /**
     * 从Git仓库同步所有技能
     */
    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> syncFromGit() {
        try {
            int count = skillService.syncAllFromGit();
            return ApiResponse.success(Map.of("synced", count));
        } catch (Exception e) {
            log.error("Failed to sync skills from git", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 获取技能详情（含文件树）
     */
    @GetMapping("/{name}")
    public ApiResponse<Map<String, Object>> getSkillDetail(@PathVariable String name) {
        try {
            String userId = UserContext.getCurrentUserId();
            Map<String, Object> detail = skillService.getSkillDetail(name, userId);
            return ApiResponse.success(detail);
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get skill detail: {}", name, e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 读取技能文件内容
     */
    @GetMapping("/{name}/files/**")
    public ApiResponse<Map<String, Object>> getFile(@PathVariable String name, HttpServletRequest request) {
        try {
            String path = extractFilePath(request, name);
            String userId = UserContext.getCurrentUserId();
            String content = skillService.getFile(name, path, userId);
            // 获取分支最新 commit 用于前端乐观锁
            String commitId = skillService.getLatestCommitId(name, userId);
            return ApiResponse.success(Map.of(
                    "content", content,
                    "path", path,
                    "commitId", commitId != null ? commitId : ""
            ));
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get file: {}/{}", name, extractFilePath(request, name), e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 编辑并提交技能文件
     */
    @PutMapping("/{name}/files/**")
    public ApiResponse<Map<String, Object>> commitFile(@PathVariable String name,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        try {
            String path = extractFilePath(request, name);
            String content = (String) body.get("content");
            String commitMessage = (String) body.get("commitMessage");
            String lastCommitId = (String) body.get("lastCommitId");
            if (!StringUtils.hasText(commitMessage)) {
                throw new IllegalArgumentException("commitMessage 不能为空");
            }
            String userId = UserContext.getCurrentUserId();
            Map<String, Object> result = skillService.commitFile(
                    name, path, content, commitMessage, lastCommitId, userId);
            return ApiResponse.success(result);
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to commit file: {}/{}", name, extractFilePath(request, name), e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 创建新技能
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createSkill(@RequestBody Map<String, Object> request) {
        try {
            String name = (String) request.get("name");
            String description = (String) request.get("description");
            Boolean isPublic = request.get("isPublic") instanceof Boolean
                    ? (Boolean) request.get("isPublic")
                    : null;
            String userId = UserContext.getCurrentUserId();
            String userName = UserContext.getCurrentUserName();
            skillService.createSkill(name, description, isPublic, userId, userName);
            return ApiResponse.success(Map.of("name", name));
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create skill", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 更新技能元数据（description/isPublic）
     */
    @PutMapping("/{name}")
    public ApiResponse<Map<String, Object>> updateSkillMeta(@PathVariable String name,
                                                             @RequestBody Map<String, Object> request) {
        try {
            String userId = UserContext.getCurrentUserId();
            String userName = UserContext.getCurrentUserName();
            skillService.updateSkillMeta(name, request, userId, userName);
            return ApiResponse.success(Map.of("name", name));
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update skill meta: {}", name, e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 删除技能
     */
    @DeleteMapping("/{name}")
    public ApiResponse<Void> deleteSkill(@PathVariable String name) {
        try {
            String userId = UserContext.getCurrentUserId();
            skillService.deleteSkill(name, userId);
            return ApiResponse.success(null);
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete skill: {}", name, e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 获取技能提交历史
     */
    @GetMapping("/{name}/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(@PathVariable String name) {
        try {
            String userId = UserContext.getCurrentUserId();
            List<Map<String, Object>> history = skillService.getHistory(name, userId);
            return ApiResponse.success(history);
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get history: {}", name, e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 回退到指定提交
     */
    @PostMapping("/{name}/revert")
    public ApiResponse<Void> revertToCommit(@PathVariable String name,
                                            @RequestBody Map<String, Object> body) {
        try {
            String commitSha = (String) body.get("commitSha");
            String userId = UserContext.getCurrentUserId();
            skillService.revertToCommit(name, commitSha, userId);
            return ApiResponse.success(null);
        } catch (ResourceNotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ForbiddenOperationException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to revert skill {} to commit", name, e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 从请求URI中提取文件路径
     */
    private String extractFilePath(HttpServletRequest request, String name) {
        String uri = request.getRequestURI();
        String prefix = "/api/v1/skills/" + name + "/files/";
        String encodedPath = uri.substring(uri.indexOf(prefix) + prefix.length());
        String normalized = encodedPath == null ? "" : encodedPath.trim().replace('\\', '/');
        for (int i = 0; i < 3; i++) {
            String decoded = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
            if (decoded.equals(normalized)) {
                break;
            }
            normalized = decoded;
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}

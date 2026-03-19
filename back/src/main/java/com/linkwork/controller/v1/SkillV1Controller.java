package com.linkwork.controller.v1;

import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.common.api.ApiResponse;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.common.exception.ResourceNotFoundException;
import com.linkwork.context.UserContext;
import com.linkwork.service.SkillV1Service;
import com.linkwork.service.skill.SkillConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillV1Controller {
    private static final Logger log = LoggerFactory.getLogger(SkillV1Controller.class);
    private final SkillV1Service service;

    public SkillV1Controller(SkillV1Service service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<?> listSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.listSkills(page, pageSize, status, keyword, userId));
    }

    @GetMapping("/available")
    public ApiResponse<?> listAvailableSkills() {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.listAllAvailable(userId));
    }

    @PostMapping("/sync")
    public ApiResponse<?> syncSkills() {
        int count = service.syncAllFromGit();
        return ApiResponse.ok(Map.of("synced", count));
    }

    @GetMapping("/{name}")
    public ApiResponse<?> getSkillDetail(@PathVariable String name) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.getSkillDetail(name, userId));
    }

    @GetMapping("/{name}/files/**")
    public ApiResponse<?> getFileContent(@PathVariable String name, HttpServletRequest request) {
        String path = extractFilePath(request, name);
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.getFileContent(name, path, userId));
    }

    @PutMapping("/{name}/files/**")
    public ApiResponse<?> commitFile(@PathVariable String name,
                                     HttpServletRequest request,
                                     @RequestBody Map<String, Object> body) {
        String path = extractFilePath(request, name);
        String content = (String) body.get("content");
        String commitMessage = (String) body.get("commitMessage");
        String lastCommitId = (String) body.get("lastCommitId");
        if (commitMessage == null || commitMessage.isBlank()) {
            throw new IllegalArgumentException("commitMessage is required");
        }
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.commitFile(name, path, content, commitMessage, lastCommitId, userId));
    }

    @PostMapping
    public ApiResponse<?> createSkill(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        Boolean isPublic = request.get("isPublic") instanceof Boolean b ? b : null;
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        service.createSkill(name, description, isPublic, userId, userName);
        return ApiResponse.ok(Map.of("name", name));
    }

    @PutMapping("/{name}")
    public ApiResponse<?> updateSkillMeta(@PathVariable String name,
                                          @RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        service.updateSkillMeta(name, request, userId, userName);
        return ApiResponse.ok(Map.of("name", name));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable String name) {
        String userId = UserContext.getCurrentUserId();
        service.deleteSkill(name, userId);
    }

    @GetMapping("/{name}/history")
    public ApiResponse<?> getHistory(@PathVariable String name,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "50") int pageSize) {
        String userId = UserContext.getCurrentUserId();
        return ApiResponse.ok(service.getHistory(name, page, pageSize, userId));
    }

    @PostMapping("/{name}/revert")
    public ApiResponse<?> revert(@PathVariable String name,
                                 @RequestBody Map<String, Object> body) {
        String commitSha = (String) body.get("commitSha");
        String userId = UserContext.getCurrentUserId();
        service.revertToCommit(name, commitSha, userId);
        return ApiResponse.ok(null);
    }

    // ==================== Exception Handlers ====================

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ResourceNotFoundException.class)
    public ApiResponse<?> handleNotFound(ResourceNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(ForbiddenOperationException.class)
    public ApiResponse<?> handleForbidden(ForbiddenOperationException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(SkillConflictException.class)
    public ApiResponse<?> handleSkillConflict(SkillConflictException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({SkillException.class, IllegalArgumentException.class})
    public ApiResponse<?> handleBadRequest(Exception ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception ex) {
        log.error("Unexpected error in SkillV1Controller", ex);
        return ApiResponse.error(ex.getMessage());
    }

    private String extractFilePath(HttpServletRequest request, String name) {
        String uri = request.getRequestURI();
        String marker = "/api/v1/skills/" + name + "/files/";
        int idx = uri.indexOf(marker);
        if (idx < 0) {
            throw new IllegalArgumentException("Invalid file path");
        }
        String encoded = uri.substring(idx + marker.length());
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("File path is required");
        }
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}

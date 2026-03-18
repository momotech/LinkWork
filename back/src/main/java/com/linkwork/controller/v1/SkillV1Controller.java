package com.linkwork.controller.v1;

import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.common.api.ApiResponse;
import com.linkwork.service.SkillV1Service;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillV1Controller {
    private static final Logger log = LoggerFactory.getLogger(SkillV1Controller.class);
    private final SkillV1Service service;

    public SkillV1Controller(SkillV1Service service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<?> listSkills() {
        return ApiResponse.ok(service.listSkills());
    }

    @GetMapping("/available")
    public ApiResponse<?> listAvailableSkills() {
        return ApiResponse.ok(service.listAvailableSkills());
    }

    @PostMapping("/sync")
    public ApiResponse<?> syncSkills() {
        return ApiResponse.ok(service.syncSkills());
    }

    @GetMapping("/{name}")
    public ApiResponse<?> getSkillDetail(@PathVariable String name) {
        return ApiResponse.ok(service.getSkillDetail(name));
    }

    @GetMapping("/{name}/files/**")
    public ApiResponse<?> getFileContent(@PathVariable String name, HttpServletRequest request) {
        String path = extractFilePath(request, name);
        log.info("skill file read request: name={}, path={}", name, path);
        return ApiResponse.ok(service.getFileContent(name, path));
    }

    @PutMapping("/{name}/files/**")
    public ApiResponse<?> saveFile(@PathVariable String name,
                                   HttpServletRequest request,
                                   @RequestBody SkillV1Service.SaveFileRequest body) {
        String path = extractFilePath(request, name);
        return ApiResponse.ok(service.saveFile(name, path, body));
    }

    @PostMapping
    public ApiResponse<?> createSkill(@RequestBody SkillV1Service.CreateSkillRequest request) {
        return ApiResponse.ok(service.createSkill(request));
    }

    @PutMapping("/{name}")
    public ApiResponse<?> updateSkill(@PathVariable String name,
                                      @RequestBody SkillV1Service.UpdateSkillRequest request) {
        return ApiResponse.ok(service.updateSkillMeta(name, request));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable String name) {
        service.deleteSkill(name);
    }

    @GetMapping("/{name}/history")
    public ApiResponse<?> getHistory(@PathVariable String name, Integer page, Integer pageSize) {
        int safePage = page == null ? 1 : page;
        int safePageSize = pageSize == null ? 50 : pageSize;
        return ApiResponse.ok(service.getHistory(name, safePage, safePageSize));
    }

    @PostMapping("/{name}/revert")
    public ApiResponse<?> revert(@PathVariable String name,
                                 @RequestBody SkillV1Service.RevertRequest request) {
        service.revertSkill(name, request);
        return ApiResponse.ok(null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({SkillException.class, IllegalArgumentException.class})
    public ApiResponse<?> handleSkillException(Exception ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception ex) {
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

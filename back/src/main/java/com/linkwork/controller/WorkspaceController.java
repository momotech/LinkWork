package com.linkwork.controller;

import com.linkwork.model.FileNode;
import com.linkwork.service.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspace")
@CrossOrigin(origins = "*") // 允许前端跨域
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/files")
    public List<FileNode> listFiles(@RequestParam String taskId) {
        return workspaceService.listFiles(taskId);
    }
}

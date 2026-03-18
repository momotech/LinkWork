package com.linkwork.controller;

import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import com.linkwork.service.SkillApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillApplicationService skillApplicationService;

    public SkillController(SkillApplicationService skillApplicationService) {
        this.skillApplicationService = skillApplicationService;
    }

    @GetMapping
    public List<SkillInfo> listSkills() {
        return skillApplicationService.listSkills();
    }

    @GetMapping("/{skillName}/tree")
    public List<FileNode> getTree(@PathVariable String skillName) {
        return skillApplicationService.getTree(skillName);
    }

    @GetMapping("/{provider}/{skillName}/tree")
    public List<FileNode> getTreeByProvider(@PathVariable String provider,
                                            @PathVariable String skillName) {
        return skillApplicationService.getTree(provider, skillName);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @org.springframework.web.bind.annotation.ExceptionHandler(SkillException.class)
    public String handleSkillException(SkillException ex) {
        return ex.getMessage();
    }
}

package com.linkwork.service;

import com.linkwork.agent.skill.core.SkillClient;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SkillApplicationService {
    private final SkillClient skillClient;

    public SkillApplicationService(SkillClient skillClient) {
        this.skillClient = skillClient;
    }

    public List<SkillInfo> listSkills() {
        return skillClient.listSkills();
    }

    public List<FileNode> getTree(String skillName) {
        return skillClient.getTree(skillName);
    }

    public List<FileNode> getTree(String provider, String skillName) {
        return skillClient.getTree(provider + ":" + skillName);
    }
}

package com.linkwork.service.skill.provider.routing;

import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.SkillProvider;
import com.linkwork.agent.skill.core.SkillProviderExtendedOps;
import com.linkwork.agent.skill.core.model.CommitInfo;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RoutingSkillProvider implements SkillProvider, SkillProviderExtendedOps {
    private final Map<String, SkillProvider> providers;
    private final String defaultProvider;

    public RoutingSkillProvider(Map<String, SkillProvider> providers, String defaultProvider) {
        this.providers = providers;
        this.defaultProvider = defaultProvider;
    }

    @Override
    public List<SkillInfo> listSkills() {
        List<SkillInfo> merged = new ArrayList<>();
        for (Map.Entry<String, SkillProvider> entry : providers.entrySet()) {
            String providerName = entry.getKey();
            for (SkillInfo info : entry.getValue().listSkills()) {
                merged.add(new SkillInfo(
                        providerName + ":" + info.name(),
                        providerName + ":" + info.path(),
                        info.branch(),
                        info.lastCommitId(),
                        info.updatedAt()
                ));
            }
        }
        return merged;
    }

    @Override
    public List<FileNode> getTree(String skillName) {
        RoutedSkill routed = route(skillName);
        return routed.provider().getTree(routed.rawSkillName());
    }

    @Override
    public String getFile(String skillName, String filePath) {
        RoutedSkill routed = route(skillName);
        return routed.provider().getFile(routed.rawSkillName(), filePath);
    }

    @Override
    public CommitInfo upsertFile(String skillName, String filePath, String content, String commitMessage) {
        RoutedSkill routed = route(skillName);
        return routed.provider().upsertFile(routed.rawSkillName(), filePath, content, commitMessage);
    }

    @Override
    public CommitInfo deleteFile(String skillName, String filePath, String commitMessage) {
        RoutedSkill routed = route(skillName);
        return routed.provider().deleteFile(routed.rawSkillName(), filePath, commitMessage);
    }

    @Override
    public List<CommitInfo> listCommits(String skillName, int page, int pageSize) {
        RoutedSkill routed = route(skillName);
        return routed.provider().listCommits(routed.rawSkillName(), page, pageSize);
    }

    // ==================== Extended Ops ====================

    @Override
    public String getHeadCommitId(String skillName) {
        RoutedSkill routed = route(skillName);
        return requireExtended(routed).getHeadCommitId(routed.rawSkillName());
    }

    @Override
    public String getFileAtCommit(String skillName, String filePath, String commitSha) {
        RoutedSkill routed = route(skillName);
        return requireExtended(routed).getFileAtCommit(routed.rawSkillName(), filePath, commitSha);
    }

    @Override
    public CommitInfo createSkillBranch(String skillName, String fromRef) {
        RoutedSkill routed = route(skillName);
        return requireExtended(routed).createSkillBranch(routed.rawSkillName(), fromRef);
    }

    @Override
    public void deleteSkillBranch(String skillName) {
        RoutedSkill routed = route(skillName);
        requireExtended(routed).deleteSkillBranch(routed.rawSkillName());
    }

    private SkillProviderExtendedOps requireExtended(RoutedSkill routed) {
        if (routed.provider() instanceof SkillProviderExtendedOps ops) {
            return ops;
        }
        throw new SkillException("Provider does not support extended operations");
    }

    private RoutedSkill route(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new SkillException("skillName cannot be blank");
        }
        String providerName = defaultProvider;
        String rawSkillName = skillName;
        int split = skillName.indexOf(':');
        if (split > 0 && split < skillName.length() - 1) {
            providerName = skillName.substring(0, split);
            rawSkillName = skillName.substring(split + 1);
        }
        SkillProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new SkillException("No provider registered for '" + providerName + "'");
        }
        return new RoutedSkill(provider, rawSkillName);
    }

    private record RoutedSkill(SkillProvider provider, String rawSkillName) {
    }
}

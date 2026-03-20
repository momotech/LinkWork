package com.linkwork.service;

import com.linkwork.agent.skill.core.SkillClient;
import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.model.CommitInfo;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import com.linkwork.common.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill Git provider adapter.
 *
 * 使用 linkwork-skill-starter 的 SkillClient 覆盖替换原有直连 GitLab RestTemplate 实现，
 * 对上层 SkillService 继续提供原有 Map/List<Map> 兼容接口。
 */
@Slf4j
@Service
public class SkillGitLabService {

    private final SkillClient skillClient;

    public SkillGitLabService(SkillClient skillClient) {
        this.skillClient = skillClient;
    }

    /**
     * 返回兼容 GitLab branch API 结构。
     */
    public List<Map<String, Object>> listBranches() {
        List<SkillInfo> skills = skillClient.listSkills();
        List<Map<String, Object>> branches = new ArrayList<>();
        for (SkillInfo skill : skills) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", skill.name());
            String commitId = skill.lastCommitId();
            if (StringUtils.hasText(commitId)) {
                branch.put("id", commitId);
                branch.put("commit_id", commitId);
                branch.put("commit", Map.of("id", commitId));
            }
            branches.add(branch);
        }
        return branches;
    }

    /**
     * 返回兼容 GitLab branch detail 结构。
     */
    public Map<String, Object> getBranchInfo(String branchName) {
        String commitId = null;
        try {
            commitId = skillClient.getHeadCommitId(branchName);
        } catch (RuntimeException ex) {
            // 兼容 resolveDefaultBranch: main/master 在目录模型下可能并非真实 skill。
            if (!"main".equals(branchName) && !"master".equals(branchName)) {
                throw new ResourceNotFoundException("Branch not found: " + branchName);
            }
        }

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", branchName);
        if (StringUtils.hasText(commitId)) {
            branch.put("id", commitId);
            branch.put("commit_id", commitId);
            branch.put("commit", Map.of("id", commitId));
        }
        return branch;
    }

    public String getFileContent(String branchName, String filePath) {
        try {
            return skillClient.getFile(branchName, filePath);
        } catch (RuntimeException ex) {
            throw mapNotFound(ex, "File not found: " + filePath + " on branch " + branchName);
        }
    }

    /**
     * 返回兼容 GitLab repository tree 结构。
     */
    public List<Map<String, Object>> getTree(String branchName) {
        List<FileNode> nodes = skillClient.getTree(branchName);
        List<Map<String, Object>> tree = new ArrayList<>();
        for (FileNode node : nodes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.sha());
            item.put("name", node.name());
            item.put("path", node.path());
            item.put("type", node.type() == FileNode.NodeType.DIRECTORY ? "tree" : "blob");
            item.put("mode", node.type() == FileNode.NodeType.DIRECTORY ? "040000" : "100644");
            if (node.size() != null) {
                item.put("size", node.size());
            }
            tree.add(item);
        }
        return tree;
    }

    /**
     * 兼容旧接口：lastCommitId 用于乐观锁校验。
     */
    public Map<String, Object> commitFile(String branchName, String filePath, String content,
                                          String commitMessage, String lastCommitId) {
        if (StringUtils.hasText(lastCommitId)) {
            String current = skillClient.getHeadCommitId(branchName);
            if (StringUtils.hasText(current) && !lastCommitId.equals(current)) {
                throw new IllegalStateException("Commit conflict: expected " + lastCommitId + " but current is " + current);
            }
        }

        CommitInfo commit = skillClient.upsertFile(branchName, filePath, content, commitMessage);
        return commitToMap(commit);
    }

    public Map<String, Object> createFile(String branchName, String filePath, String content, String commitMessage) {
        CommitInfo commit = skillClient.upsertFile(branchName, filePath, content, commitMessage);
        return commitToMap(commit);
    }

    /**
     * 兼容旧分支模型：通过扩展能力创建 skill 工作区。
     */
    public Map<String, Object> createBranch(String branchName, String ref) {
        if (!skillClient.supportsExtendedOps()) {
            return Map.of("name", branchName);
        }
        CommitInfo commit = skillClient.createSkillBranch(branchName, ref);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", branchName);
        if (commit != null && StringUtils.hasText(commit.id())) {
            result.put("id", commit.id());
            result.put("commit", Map.of("id", commit.id()));
        }
        return result;
    }

    public void deleteBranch(String branchName) {
        if (!skillClient.supportsExtendedOps()) {
            return;
        }
        skillClient.deleteSkillBranch(branchName);
    }

    /**
     * 返回兼容 GitLab commit 列表结构。
     */
    public List<Map<String, Object>> getCommitHistory(String branchName) {
        List<CommitInfo> commits = skillClient.listCommits(branchName, 1, 50);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommitInfo commit : commits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", commit.id());
            item.put("short_id", shortSha(commit.id()));
            item.put("title", commit.title());
            item.put("message", commit.message());
            item.put("author_name", commit.authorName());
            item.put("author_email", "");
            item.put("created_at", toIso(commit.authoredAt()));
            item.put("web_url", commit.webUrl());
            result.add(item);
        }
        return result;
    }

    /**
     * 新增：显式 skillName，避免仅靠 commitSha 反查 skill 的不确定性。
     */
    public String getFileAtCommit(String skillName, String commitSha, String filePath) {
        try {
            return skillClient.getFileAtCommit(skillName, filePath, commitSha);
        } catch (RuntimeException ex) {
            throw mapNotFound(ex, "File not found: " + filePath + " at commit " + commitSha);
        }
    }

    /**
     * 兼容旧签名：根据 commitSha 在技能列表中顺序尝试。
     */
    public String getFileAtCommit(String commitSha, String filePath) {
        List<SkillInfo> skills;
        try {
            skills = skillClient.listSkills();
        } catch (RuntimeException ex) {
            skills = Collections.emptyList();
        }

        for (SkillInfo skill : skills) {
            try {
                return skillClient.getFileAtCommit(skill.name(), filePath, commitSha);
            } catch (RuntimeException ignored) {
            }
        }
        throw new ResourceNotFoundException("File not found: " + filePath + " at commit " + commitSha);
    }

    private Map<String, Object> commitToMap(CommitInfo commit) {
        if (commit == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", commit.id());
        mapped.put("commit_id", commit.id());
        mapped.put("short_id", shortSha(commit.id()));
        mapped.put("title", commit.title());
        mapped.put("message", commit.message());
        mapped.put("author_name", commit.authorName());
        mapped.put("author_email", "");
        mapped.put("created_at", toIso(commit.authoredAt()));
        mapped.put("web_url", commit.webUrl());
        mapped.put("commit", Map.of(
                "id", commit.id() == null ? "" : commit.id(),
                "message", commit.message() == null ? "" : commit.message()
        ));
        return mapped;
    }

    private String shortSha(String sha) {
        if (!StringUtils.hasText(sha)) {
            return null;
        }
        return sha.substring(0, Math.min(8, sha.length()));
    }

    private String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private RuntimeException mapNotFound(RuntimeException ex, String message) {
        String lower = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (lower.contains("404") || lower.contains("not found")) {
            return new ResourceNotFoundException(message);
        }
        if (ex instanceof SkillException) {
            return new RuntimeException(ex.getMessage(), ex);
        }
        return ex;
    }
}

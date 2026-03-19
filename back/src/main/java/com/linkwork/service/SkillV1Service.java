package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.agent.skill.core.SkillClient;
import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.model.CommitInfo;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.common.exception.ResourceNotFoundException;
import com.linkwork.mapper.SkillMapper;
import com.linkwork.model.entity.SkillEntity;
import com.linkwork.service.skill.SkillConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SkillV1Service extends ServiceImpl<SkillMapper, SkillEntity> {

    private static final Logger log = LoggerFactory.getLogger(SkillV1Service.class);
    private static final Set<String> SUPPORTED_SKILL_STATUSES = Set.of("draft", "ready", "disabled");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final int IMPLEMENTATION_MAX_LENGTH = 2000;

    private final SkillClient skillClient;
    private final AdminAccessService adminAccessService;

    public SkillV1Service(SkillClient skillClient, AdminAccessService adminAccessService) {
        this.skillClient = skillClient;
        this.adminAccessService = adminAccessService;
    }

    // ==================== Git Sync ====================

    public int syncAllFromGit() {
        List<SkillInfo> gitSkills = skillClient.listSkills();
        int syncedCount = 0;

        Set<String> gitSkillNames = new HashSet<>();

        for (SkillInfo info : gitSkills) {
            String skillName = info.name();
            if (skillName == null || skillName.isBlank()) {
                continue;
            }
            gitSkillNames.add(skillName);

            try {
                String description = "";
                String displayName = skillName;
                String implementation = null;

                try {
                    String fileContent = skillClient.getFile(skillName, "SKILL.md");
                    Map<String, String> frontmatter = parseFrontmatter(fileContent);
                    skillName = frontmatter.getOrDefault("name", skillName);
                    displayName = frontmatter.getOrDefault("displayName", skillName);
                    description = frontmatter.getOrDefault("description", "");
                    implementation = truncateContent(fileContent);
                } catch (Exception e) {
                    log.debug("No SKILL.md for skill {}, using defaults", skillName);
                }

                String latestCommit = info.lastCommitId();
                SkillEntity entity = findByName(skillName);

                if (entity != null) {
                    entity.setName(skillName);
                    entity.setDisplayName(displayName);
                    entity.setDescription(description);
                    entity.setImplementation(implementation);
                    entity.setLatestCommit(latestCommit);
                    entity.setLastSyncedAt(LocalDateTime.now());
                    entity.setStatus("ready");
                    this.updateById(entity);
                } else {
                    entity = new SkillEntity();
                    entity.setSkillNo("SKL-" + System.currentTimeMillis());
                    entity.setName(skillName);
                    entity.setDisplayName(displayName);
                    entity.setDescription(description);
                    entity.setImplementation(implementation);
                    entity.setBranchName(info.branch());
                    entity.setLatestCommit(latestCommit);
                    entity.setLastSyncedAt(LocalDateTime.now());
                    entity.setStatus("ready");
                    entity.setIsPublic(true);
                    this.save(entity);
                }

                syncedCount++;
                log.debug("Synced skill: {}", skillName);
            } catch (Exception e) {
                log.warn("Failed to sync skill {}: {}", skillName, e.getMessage());
            }
        }

        disableOrphanedSkills(gitSkillNames);
        log.info("Synced {} skills from Git ({} total in provider)", syncedCount, gitSkillNames.size());
        return syncedCount;
    }

    public SkillEntity syncSingle(String skillName) {
        String latestCommit = null;
        if (skillClient.supportsExtendedOps()) {
            latestCommit = skillClient.getHeadCommitId(skillName);
        }

        String displayName = skillName;
        String description = "";
        String implementation = null;

        try {
            String fileContent = skillClient.getFile(skillName, "SKILL.md");
            Map<String, String> frontmatter = parseFrontmatter(fileContent);
            displayName = frontmatter.getOrDefault("displayName", skillName);
            description = frontmatter.getOrDefault("description", "");
            implementation = truncateContent(fileContent);
        } catch (Exception e) {
            log.debug("No SKILL.md for skill {}", skillName);
        }

        SkillEntity entity = findByName(skillName);
        if (entity != null) {
            entity.setDisplayName(displayName);
            entity.setDescription(description);
            entity.setImplementation(implementation);
            entity.setLatestCommit(latestCommit);
            entity.setLastSyncedAt(LocalDateTime.now());
            entity.setStatus("ready");
            this.updateById(entity);
        } else {
            entity = new SkillEntity();
            entity.setSkillNo("SKL-" + System.currentTimeMillis());
            entity.setName(skillName);
            entity.setDisplayName(displayName);
            entity.setDescription(description);
            entity.setImplementation(implementation);
            entity.setBranchName(skillName);
            entity.setLatestCommit(latestCommit);
            entity.setLastSyncedAt(LocalDateTime.now());
            entity.setStatus("ready");
            entity.setIsPublic(true);
            this.save(entity);
        }

        log.info("Synced single skill: {}", skillName);
        return entity;
    }

    // ==================== CRUD ====================

    public SkillEntity createSkill(String name, String description, Boolean isPublic,
                                    String userId, String userName) {
        if (userId == null || userId.isBlank()) {
            throw new ForbiddenOperationException("User not authenticated");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name is required");
        }
        if (!name.matches("^[a-zA-Z][a-zA-Z0-9_\\-]*$")) {
            throw new IllegalArgumentException(
                    "Skill name must start with a letter and contain only alphanumeric, hyphen, underscore: " + name);
        }

        String skillDescription = description == null ? "" : description;
        boolean publicVisible = Boolean.TRUE.equals(isPublic);

        String content = "---\n"
                + "name: " + name + "\n"
                + "displayName: " + name + "\n"
                + "description: " + skillDescription + "\n"
                + "---\n\n"
                + "# " + name + "\n\n"
                + skillDescription + "\n";

        CommitInfo commitInfo;
        if (skillClient.supportsExtendedOps()) {
            commitInfo = skillClient.createSkillBranch(name, "main");
            skillClient.upsertFile(name, "SKILL.md", content, "Initialize skill: " + name);
        } else {
            commitInfo = skillClient.upsertFile(name, "SKILL.md", content, "Initialize skill: " + name);
        }

        SkillEntity entity = new SkillEntity();
        entity.setSkillNo("SKL-" + System.currentTimeMillis());
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setDescription(skillDescription);
        entity.setImplementation(truncateContent(content));
        entity.setBranchName(name);
        entity.setLatestCommit(commitInfo != null ? commitInfo.id() : null);
        entity.setLastSyncedAt(LocalDateTime.now());
        entity.setStatus("ready");
        entity.setIsPublic(publicVisible);
        entity.setCreatorId(userId);
        entity.setCreatorName(userName);
        this.save(entity);

        log.info("Created skill: {} by user {}", entity.getSkillNo(), userId);
        return entity;
    }

    public void deleteSkill(String name, String userId) {
        SkillEntity entity = requireSkillForWrite(name, userId);

        try {
            if (skillClient.supportsExtendedOps()) {
                skillClient.deleteSkillBranch(name);
            } else {
                List<FileNode> files = skillClient.getTree(name);
                for (FileNode file : files) {
                    if (file.type() == FileNode.NodeType.FILE) {
                        skillClient.deleteFile(name, file.name(), "delete " + file.name());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete Git workspace for skill {}: {}", name, e.getMessage());
        }

        this.removeById(entity.getId());
        log.info("Deleted skill: {} ({})", entity.getSkillNo(), name);
    }

    public SkillEntity updateSkillMeta(String name, Map<String, Object> request,
                                        String userId, String userName) {
        SkillEntity entity = requireSkillForWrite(name, userId);

        if (request.containsKey("description")) {
            entity.setDescription((String) request.get("description"));
        }
        if (request.containsKey("isPublic")) {
            Object value = request.get("isPublic");
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("isPublic must be a boolean");
            }
            entity.setIsPublic((Boolean) value);
        }
        if (request.containsKey("status")) {
            entity.setStatus(normalizeSkillStatus(request.get("status")));
        }

        entity.setUpdaterId(userId);
        entity.setUpdaterName(userName);
        this.updateById(entity);
        log.info("Updated skill metadata: {} by user {}", name, userId);
        return entity;
    }

    // ==================== Detail & File Operations ====================

    public Map<String, Object> getSkillDetail(String name, String userId) {
        SkillEntity entity = requireSkillForRead(name, userId);
        List<FileNode> fileNodes = skillClient.getTree(name);

        List<Map<String, Object>> files = fileNodes.stream()
                .map(node -> {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("path", normalizeFilePath(name, node.path(), node.name()));
                    file.put("type", node.type() == FileNode.NodeType.DIRECTORY ? "tree" : "blob");
                    return file;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = toResponseMap(entity);
        result.put("files", files);
        return result;
    }

    public Map<String, Object> getFileContent(String name, String path, String userId) {
        requireSkillForRead(name, userId);
        String content = skillClient.getFile(name, path);

        String commitId = null;
        if (skillClient.supportsExtendedOps()) {
            try {
                commitId = skillClient.getHeadCommitId(name);
            } catch (Exception e) {
                log.debug("Failed to get head commit for {}: {}", name, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("path", path);
        result.put("commitId", commitId != null ? commitId : "");
        return result;
    }

    public Map<String, Object> commitFile(String name, String path, String content,
                                           String commitMessage, String lastCommitId, String userId) {
        requireSkillForWrite(name, userId);
        CommitInfo commitInfo = skillClient.upsertFile(name, path, content, commitMessage);

        SkillEntity entity = findByName(name);
        if (entity != null && commitInfo != null && commitInfo.id() != null) {
            entity.setLatestCommit(commitInfo.id());
            entity.setLastSyncedAt(LocalDateTime.now());
            this.updateById(entity);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitId", commitInfo != null ? commitInfo.id() : null);
        return result;
    }

    // ==================== History & Revert ====================

    public List<Map<String, Object>> getHistory(String name, int page, int pageSize, String userId) {
        requireSkillForRead(name, userId);
        List<CommitInfo> commits = skillClient.listCommits(name, page, pageSize);
        return commits.stream().map(commit -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("sha", commit.id());
            mapped.put("message", commit.message());
            mapped.put("authorName", commit.authorName());
            mapped.put("createdAt", commit.authoredAt() != null ? commit.authoredAt().toString() : null);
            return mapped;
        }).collect(Collectors.toList());
    }

    public void revertToCommit(String name, String commitSha, String userId) {
        requireSkillForWrite(name, userId);

        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("commitSha is required");
        }

        if (!skillClient.supportsExtendedOps()) {
            throw new SkillException("Revert is not supported by the current skill provider");
        }

        String oldContent = skillClient.getFileAtCommit(name, "SKILL.md", commitSha);
        String revertMessage = "Revert to " + commitSha.substring(0, Math.min(8, commitSha.length()));
        skillClient.upsertFile(name, "SKILL.md", oldContent, revertMessage);
        syncSingle(name);

        log.info("Reverted skill {} to commit {}", name, commitSha.substring(0, Math.min(8, commitSha.length())));
    }

    // ==================== List ====================

    public Map<String, Object> listSkills(int page, int pageSize, String status,
                                           String keyword, String userId) {
        Page<SkillEntity> pageObj = new Page<>(page, pageSize);

        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityFilter(wrapper, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SkillEntity::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(SkillEntity::getName, keyword)
                    .or().like(SkillEntity::getDisplayName, keyword)
                    .or().like(SkillEntity::getDescription, keyword));
        }
        wrapper.orderByDesc(SkillEntity::getCreatedAt);

        Page<SkillEntity> result = this.page(pageObj, wrapper);

        List<Map<String, Object>> items = result.getRecords().stream()
                .map(this::toResponseMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
                "page", result.getCurrent(),
                "pageSize", result.getSize(),
                "total", result.getTotal(),
                "totalPages", result.getPages()
        ));
        return response;
    }

    public List<Map<String, Object>> listAllAvailable(String userId) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityFilter(wrapper, userId);
        wrapper.eq(SkillEntity::getStatus, "ready");
        wrapper.orderByDesc(SkillEntity::getCreatedAt);

        return this.list(wrapper).stream()
                .map(this::toSimpleMap)
                .collect(Collectors.toList());
    }

    // ==================== Visibility & Permission ====================

    private void applyVisibilityFilter(LambdaQueryWrapper<SkillEntity> wrapper, String userId) {
        if (userId != null && !userId.isBlank()) {
            if (adminAccessService.isAdmin(userId)) {
                return;
            }
            wrapper.and(w -> w.eq(SkillEntity::getCreatorId, userId)
                    .or().eq(SkillEntity::getIsPublic, true));
            return;
        }
        wrapper.eq(SkillEntity::getIsPublic, true);
    }

    private SkillEntity requireSkillForRead(String name, String userId) {
        SkillEntity entity = findByName(name);
        if (entity == null) {
            throw new ResourceNotFoundException("Skill not found: " + name);
        }
        if (!canRead(entity, userId)) {
            throw new ForbiddenOperationException("No permission to access this skill");
        }
        return entity;
    }

    private SkillEntity requireSkillForWrite(String name, String userId) {
        SkillEntity entity = findByName(name);
        if (entity == null) {
            throw new ResourceNotFoundException("Skill not found: " + name);
        }
        if (!canWrite(entity, userId)) {
            throw new ForbiddenOperationException("Only the skill creator or admin can perform this operation");
        }
        return entity;
    }

    private boolean canRead(SkillEntity entity, String userId) {
        return adminAccessService.isAdmin(userId)
                || Boolean.TRUE.equals(entity.getIsPublic())
                || isOwner(entity, userId);
    }

    private boolean canWrite(SkillEntity entity, String userId) {
        return adminAccessService.isAdmin(userId) || isOwner(entity, userId);
    }

    private boolean isOwner(SkillEntity entity, String userId) {
        return userId != null && !userId.isBlank() && userId.equals(entity.getCreatorId());
    }

    // ==================== Helpers ====================

    private SkillEntity findByName(String name) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillEntity::getName, name);
        return this.getOne(wrapper, false);
    }

    private void disableOrphanedSkills(Set<String> activeSkillNames) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(SkillEntity::getStatus, "disabled");

        List<SkillEntity> allSkills = this.list(wrapper);
        for (SkillEntity entity : allSkills) {
            if (entity.getName() != null && !activeSkillNames.contains(entity.getName())) {
                entity.setStatus("disabled");
                this.updateById(entity);
                log.info("Disabled orphaned skill: {}", entity.getName());
            }
        }
    }

    private String normalizeSkillStatus(Object rawStatus) {
        if (rawStatus == null) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = String.valueOf(rawStatus).trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SKILL_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid skill status: " + rawStatus + " (allowed: draft/ready/disabled)");
        }
        return normalized;
    }

    private Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        if (content == null) {
            return result;
        }
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find()) {
            String yaml = matcher.group(1);
            for (String line : yaml.split("\\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.contains(":")) {
                    continue;
                }
                int colonIdx = line.indexOf(':');
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private String truncateContent(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= IMPLEMENTATION_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, IMPLEMENTATION_MAX_LENGTH) + "...";
    }

    private String normalizeFilePath(String skillName, String fullPath, String fallbackName) {
        String path = fullPath == null ? "" : fullPath;
        if (path.isBlank()) {
            return fallbackName;
        }
        path = trimSlashes(path);
        String marker = skillName + "/";
        int idx = path.indexOf(marker);
        if (idx >= 0) {
            String relative = path.substring(idx + marker.length());
            if (!relative.isBlank()) {
                return relative;
            }
        }
        return path;
    }

    private String trimSlashes(String value) {
        String out = value;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private Map<String, Object> toResponseMap(SkillEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId().toString());
        map.put("skillNo", entity.getSkillNo());
        map.put("name", entity.getName());
        map.put("displayName", entity.getDisplayName());
        map.put("description", entity.getDescription());
        map.put("status", entity.getStatus());
        map.put("isPublic", entity.getIsPublic());
        map.put("latestCommit", entity.getLatestCommit());
        map.put("creatorId", entity.getCreatorId());
        map.put("creatorName", entity.getCreatorName());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toSimpleMap(SkillEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId().toString());
        map.put("name", entity.getName());
        map.put("displayName", entity.getDisplayName());
        map.put("description", entity.getDescription());
        map.put("status", entity.getStatus());
        map.put("isPublic", entity.getIsPublic());
        map.put("skillNo", entity.getSkillNo());
        return map;
    }
}

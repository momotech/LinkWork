package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.mapper.SkillMapper;
import com.linkwork.model.entity.SkillEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * 技能 Service - 支持 Git 同步
 */
@Slf4j
@Service
public class SkillService extends ServiceImpl<SkillMapper, SkillEntity> {

    private static final Set<String> SUPPORTED_SKILL_STATUSES = Set.of("draft", "ready", "disabled");
    private final SkillGitLabService skillGitLabService;
    private final AdminAccessService adminAccessService;

    public SkillService(SkillGitLabService skillGitLabService, AdminAccessService adminAccessService) {
        this.skillGitLabService = skillGitLabService;
        this.adminAccessService = adminAccessService;
    }

    // ==================== Git Sync Methods ====================

    /**
     * 从 Git 同步所有技能分支
     *
     * @return 同步的技能数量
     */
    public int syncAllFromGit() {
        List<Map<String, Object>> branches = skillGitLabService.listBranches();
        int syncedCount = 0;

        // Collect all branch names for later disabling orphaned records
        Set<String> gitBranchNames = new HashSet<>();

        for (Map<String, Object> branch : branches) {
            String branchName = (String) branch.get("name");
            if (branchName == null) {
                continue;
            }
            // Skip the default branch (main/master)
            if ("main".equals(branchName) || "master".equals(branchName)) {
                continue;
            }
            gitBranchNames.add(branchName);

            try {
                String fileContent = skillGitLabService.getFileContent(branchName, "SKILL.md");
                Map<String, String> frontmatter = parseFrontmatter(fileContent);

                String skillName = frontmatter.getOrDefault("name", branchName);
                String displayName = frontmatter.getOrDefault("displayName", skillName);
                String description = frontmatter.getOrDefault("description", "");

                // Extract latest commit from branch info
                String latestCommit = extractCommitSha(branch);

                // Find existing entity by branchName
                SkillEntity entity = findByBranchName(branchName);

                if (entity != null) {
                    // Update existing
                    entity.setName(skillName);
                    entity.setDisplayName(displayName);
                    entity.setDescription(description);
                    entity.setImplementation(truncateContent(fileContent));
                    entity.setLatestCommit(latestCommit);
                    entity.setLastSyncedAt(LocalDateTime.now());
                    entity.setStatus("ready");
                    this.updateById(entity);
                } else {
                    // Create new
                    entity = new SkillEntity();
                    entity.setSkillNo("SKL-" + System.currentTimeMillis());
                    entity.setName(skillName);
                    entity.setDisplayName(displayName);
                    entity.setDescription(description);
                    entity.setImplementation(truncateContent(fileContent));
                    entity.setBranchName(branchName);
                    entity.setLatestCommit(latestCommit);
                    entity.setLastSyncedAt(LocalDateTime.now());
                    entity.setStatus("ready");
                    // Git 全量同步导入的历史技能默认公开，避免迁移后不可见
                    entity.setIsPublic(true);
                    this.save(entity);
                }

                syncedCount++;
                log.debug("Synced skill from branch: {}", branchName);
            } catch (Exception e) {
                log.warn("Failed to sync branch {}: {}", branchName, e.getMessage());
            }
        }

        // Disable DB records whose branchName is not in the Git branches list
        disableOrphanedSkills(gitBranchNames);

        log.info("Synced {} skills from Git ({} branches total)", syncedCount, gitBranchNames.size());
        return syncedCount;
    }

    /**
     * 同步单个技能
     */
    public SkillEntity syncSingle(String skillName) {
        Map<String, Object> branchInfo = skillGitLabService.getBranchInfo(skillName);
        String latestCommit = extractCommitSha(branchInfo);

        String fileContent = skillGitLabService.getFileContent(skillName, "SKILL.md");
        Map<String, String> frontmatter = parseFrontmatter(fileContent);

        String name = frontmatter.getOrDefault("name", skillName);
        String displayName = frontmatter.getOrDefault("displayName", name);
        String description = frontmatter.getOrDefault("description", "");

        SkillEntity entity = findByName(skillName);
        if (entity == null) {
            entity = findByBranchName(skillName);
        }

        if (entity != null) {
            entity.setName(name);
            entity.setDisplayName(displayName);
            entity.setDescription(description);
            entity.setImplementation(truncateContent(fileContent));
            entity.setLatestCommit(latestCommit);
            entity.setLastSyncedAt(LocalDateTime.now());
            entity.setStatus("ready");
            this.updateById(entity);
        } else {
            entity = new SkillEntity();
            entity.setSkillNo("SKL-" + System.currentTimeMillis());
            entity.setName(name);
            entity.setDisplayName(displayName);
            entity.setDescription(description);
            entity.setImplementation(truncateContent(fileContent));
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

    // ==================== CRUD Methods ====================

    /**
     * 创建技能（通过 Git 分支）
     */
    public SkillEntity createSkill(String name, String description, Boolean isPublic, String userId, String userName) {
        if (!StringUtils.hasText(userId)) {
            throw new ForbiddenOperationException("用户未登录或登录态失效");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Skill 名称不能为空");
        }
        // Validate name: alphanumeric + hyphens + underscores, must start with letter
        if (!name.matches("^[a-zA-Z][a-zA-Z0-9_\\-]*$")) {
            throw new IllegalArgumentException(
                    "Skill 名称只能包含英文字母、数字、连字符和下划线，且以字母开头: " + name);
        }

        String skillDescription = description == null ? "" : description;
        boolean publicVisible = Boolean.TRUE.equals(isPublic);

        // Create Git branch from main
        // 从默认分支创建（兼容 main 和 master）
        String defaultBranch = resolveDefaultBranch();
        skillGitLabService.createBranch(name, defaultBranch);

        // Generate SKILL.md template
        String content = "---\n"
                + "name: " + name + "\n"
                + "displayName: " + name + "\n"
                + "description: " + skillDescription + "\n"
                + "---\n\n"
                + "# " + name + "\n\n"
                + skillDescription + "\n";

        // Create SKILL.md in the new branch
        Map<String, Object> fileResponse = skillGitLabService.createFile(
                name, "SKILL.md", content, "Initialize skill: " + name);

        // Extract commit SHA from response
        String latestCommit = null;
        if (fileResponse != null) {
            if (fileResponse.containsKey("commit_id")) {
                latestCommit = (String) fileResponse.get("commit_id");
            } else if (fileResponse.containsKey("id")) {
                latestCommit = (String) fileResponse.get("id");
            }
        }

        // Create DB record
        SkillEntity entity = new SkillEntity();
        entity.setSkillNo("SKL-" + System.currentTimeMillis());
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setDescription(skillDescription);
        entity.setImplementation(truncateContent(content));
        entity.setBranchName(name);
        entity.setLatestCommit(latestCommit);
        entity.setLastSyncedAt(LocalDateTime.now());
        entity.setStatus("ready");
        entity.setIsPublic(publicVisible);
        entity.setCreatorId(userId);
        entity.setCreatorName(userName);
        this.save(entity);

        log.info("Created skill: {} (branch: {}) by user {}", entity.getSkillNo(), name, userId);
        return entity;
    }

    /**
     * 删除技能（同时删除 Git 分支）
     */
    public void deleteSkill(String name, String userId) {
        SkillEntity entity = requireSkillForWrite(name, userId);

        // Delete Git branch
        try {
            skillGitLabService.deleteBranch(name);
        } catch (Exception e) {
            log.warn("Failed to delete Git branch for skill {}: {}", name, e.getMessage());
        }

        // Remove DB record
        this.removeById(entity.getId());
        log.info("Deleted skill: {} (branch: {})", entity.getSkillNo(), name);
    }

    /**
     * 更新技能（直接 DB 更新，用于同步期间）
     */
    public SkillEntity updateSkill(Long id, Map<String, Object> request, String userId, String userName) {
        SkillEntity entity = this.getById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("Skill not found: " + id);
        }

        if (request.containsKey("name")) {
            entity.setName((String) request.get("name"));
        }
        if (request.containsKey("displayName")) {
            entity.setDisplayName((String) request.get("displayName"));
        }
        if (request.containsKey("description")) {
            entity.setDescription((String) request.get("description"));
        }
        if (request.containsKey("implementation")) {
            entity.setImplementation((String) request.get("implementation"));
        }
        if (request.containsKey("status")) {
            entity.setStatus((String) request.get("status"));
        }

        entity.setUpdaterId(userId);
        entity.setUpdaterName(userName);

        this.updateById(entity);
        log.info("Updated skill: {} by user {}", entity.getSkillNo(), userId);
        return entity;
    }

    /**
     * 更新技能元数据（描述、公开性）
     */
    public SkillEntity updateSkillMeta(String name, Map<String, Object> request, String userId, String userName) {
        SkillEntity entity = requireSkillForWrite(name, userId);

        if (request.containsKey("description")) {
            entity.setDescription((String) request.get("description"));
        }
        if (request.containsKey("isPublic")) {
            Object value = request.get("isPublic");
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("isPublic 必须为布尔值");
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

    /**
     * 获取技能详情（含文件列表）
     */
    public Map<String, Object> getSkillDetail(String name, String userId) {
        SkillEntity entity = requireSkillForRead(name, userId);

        List<Map<String, Object>> files = skillGitLabService.getTree(name);

        Map<String, Object> result = toResponseMap(entity);
        result.remove("branchName");
        result.remove("lastSyncedAt");
        result.put("files", files);
        return result;
    }

    /**
     * 获取文件内容
     */
    public String getFile(String name, String path, String userId) {
        requireSkillForRead(name, userId);
        return skillGitLabService.getFileContent(name, path);
    }

    /**
     * 获取技能最新 commit（用于前端乐观锁）
     */
    public String getLatestCommitId(String name, String userId) {
        // 优先从 DB 取缓存值
        SkillEntity entity = requireSkillForRead(name, userId);
        if (entity != null && entity.getLatestCommit() != null) {
            return entity.getLatestCommit();
        }

        // fallback: 从 GitLab 获取
        try {
            Map<String, Object> branchInfo = skillGitLabService.getBranchInfo(name);
            return extractCommitSha(branchInfo);
        } catch (Exception e) {
            log.warn("Failed to get latest commit for skill {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 提交文件变更
     */
    public Map<String, Object> commitFile(String name, String path, String content,
                                           String commitMessage, String lastCommitId, String userId) {
        requireSkillForWrite(name, userId);
        Map<String, Object> response = skillGitLabService.commitFile(
                name, path, content, commitMessage, lastCommitId);

        // Update DB record after successful commit
        SkillEntity entity = findByName(name);
        if (entity != null) {
            String newCommit = null;
            if (response != null) {
                if (response.containsKey("id")) {
                    newCommit = (String) response.get("id");
                } else if (response.containsKey("commit_id")) {
                    newCommit = (String) response.get("commit_id");
                }
            }
            if (newCommit != null) {
                entity.setLatestCommit(newCommit);
            }
            entity.setLastSyncedAt(LocalDateTime.now());
            this.updateById(entity);
        }

        return response;
    }

    // ==================== History & Revert ====================

    /**
     * 获取提交历史
     */
    public List<Map<String, Object>> getHistory(String name, String userId) {
        requireSkillForRead(name, userId);
        List<Map<String, Object>> raw = skillGitLabService.getCommitHistory(name);
        return raw.stream().map(commit -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("sha", commit.get("id"));
            mapped.put("shortSha", commit.get("short_id"));
            mapped.put("message", commit.get("message"));
            mapped.put("authorName", commit.get("author_name"));
            mapped.put("authorEmail", commit.get("author_email"));
            mapped.put("createdAt", commit.get("created_at"));
            return mapped;
        }).collect(Collectors.toList());
    }

    /**
     * 回退到指定 commit
     */
    public void revertToCommit(String name, String commitSha, String userId) {
        requireSkillForWrite(name, userId);
        // Get SKILL.md content at the target commit
        String oldContent = skillGitLabService.getFileAtCommit(name, commitSha, "SKILL.md");

        // Get current branch info for lastCommitId
        Map<String, Object> branchInfo = skillGitLabService.getBranchInfo(name);
        String currentCommit = extractCommitSha(branchInfo);

        // Commit the old content as a new commit (revert)
        String revertMessage = "Revert to " + commitSha.substring(0, Math.min(8, commitSha.length()));
        skillGitLabService.commitFile(name, "SKILL.md", oldContent, revertMessage, currentCommit);

        // Sync to update DB
        syncSingle(name);
        log.info("Reverted skill {} to commit {}", name, commitSha.substring(0, Math.min(8, commitSha.length())));
    }

    // ==================== List Methods ====================

    /**
     * 获取技能列表（分页）
     */
    public Map<String, Object> listSkills(int page, int pageSize, String status, String keyword, String userId) {
        Page<SkillEntity> pageObj = new Page<>(page, pageSize);

        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityFilter(wrapper, userId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(SkillEntity::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
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

    /**
     * 获取所有可用的技能（用于下拉选择 - 向后兼容）
     */
    public List<Map<String, Object>> listAllAvailable(String userId) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityFilter(wrapper, userId);
        wrapper.eq(SkillEntity::getStatus, "ready");
        wrapper.orderByDesc(SkillEntity::getCreatedAt);

        return this.list(wrapper).stream()
                .map(this::toSimpleMap)
                .collect(Collectors.toList());
    }

    // ==================== Helper Methods ====================

    /**
     * 解析仓库默认分支（兼容 main / master）
     */
    private String resolveDefaultBranch() {
        try {
            List<Map<String, Object>> branches = skillGitLabService.listBranches();
            // listBranches 过滤了 main/master，所以直接查一下
        } catch (Exception e) {
            // ignore
        }
        // 先尝试 main，失败再用 master
        try {
            skillGitLabService.getBranchInfo("main");
            return "main";
        } catch (Exception e) {
            return "master";
        }
    }

    /**
     * 可见性过滤：自己创建 or 公开
     */
    private void applyVisibilityFilter(LambdaQueryWrapper<SkillEntity> wrapper, String userId) {
        if (StringUtils.hasText(userId)) {
            if (adminAccessService.isAdmin(userId)) {
                return;
            }
            wrapper.and(w -> w.eq(SkillEntity::getCreatorId, userId)
                    .or().eq(SkillEntity::getIsPublic, true));
            return;
        }
        // 未登录仅可见公开资源
        wrapper.eq(SkillEntity::getIsPublic, true);
    }

    private SkillEntity requireSkillForRead(String name, String userId) {
        SkillEntity entity = findByName(name);
        if (entity == null) {
            throw new ResourceNotFoundException("Skill not found: " + name);
        }
        if (!canRead(entity, userId)) {
            throw new ForbiddenOperationException("无权限访问该 Skill");
        }
        return entity;
    }

    private SkillEntity requireSkillForWrite(String name, String userId) {
        SkillEntity entity = findByName(name);
        if (entity == null) {
            throw new ResourceNotFoundException("Skill not found: " + name);
        }
        if (!canWrite(entity, userId)) {
            throw new ForbiddenOperationException("仅 Skill 创建者或管理员可执行该操作");
        }
        return entity;
    }

    private boolean canRead(SkillEntity entity, String userId) {
        return adminAccessService.isAdmin(userId)
                || Boolean.TRUE.equals(entity.getIsPublic())
                || isOwner(entity, userId);
    }

    private boolean isOwner(SkillEntity entity, String userId) {
        return StringUtils.hasText(userId) && userId.equals(entity.getCreatorId());
    }

    private boolean canWrite(SkillEntity entity, String userId) {
        return adminAccessService.isAdmin(userId) || isOwner(entity, userId);
    }

    private String normalizeSkillStatus(Object rawStatus) {
        if (rawStatus == null || !StringUtils.hasText(String.valueOf(rawStatus))) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String normalized = String.valueOf(rawStatus).trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SKILL_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("非法 Skill 状态: " + rawStatus + "，仅支持 draft/ready/disabled");
        }
        return normalized;
    }

    /**
     * 解析 YAML frontmatter（--- 标记之间的内容）
     */
    private Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        if (content == null) {
            return result;
        }

        Pattern pattern = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
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
                // Remove surrounding quotes if present
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

    /**
     * 按 name 查找技能
     */
    private SkillEntity findByName(String name) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillEntity::getName, name);
        return this.getOne(wrapper, false);
    }

    /**
     * 按 branchName 查找技能
     */
    private SkillEntity findByBranchName(String branchName) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillEntity::getBranchName, branchName);
        return this.getOne(wrapper, false);
    }

    /**
     * 从分支信息中提取 commit SHA
     */
    private String extractCommitSha(Map<String, Object> branchInfo) {
        if (branchInfo == null) {
            return null;
        }
        // GitLab branch API returns commit info nested under "commit"
        Object commitObj = branchInfo.get("commit");
        if (commitObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> commitMap = (Map<String, Object>) commitObj;
            return (String) commitMap.get("id");
        }
        // Fallback: direct "id" or "commit_id" field
        if (branchInfo.containsKey("commit_id")) {
            return (String) branchInfo.get("commit_id");
        }
        return (String) branchInfo.get("id");
    }

    /**
     * 截断内容作为预览
     */
    private String truncateContent(String content) {
        if (content == null) {
            return null;
        }
        int maxLength = 2000;
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * 禁用不在 Git 分支列表中的技能
     */
    private void disableOrphanedSkills(Set<String> gitBranchNames) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(SkillEntity::getBranchName);
        wrapper.ne(SkillEntity::getStatus, "disabled");

        List<SkillEntity> allWithBranch = this.list(wrapper);
        for (SkillEntity entity : allWithBranch) {
            if (entity.getBranchName() != null && !gitBranchNames.contains(entity.getBranchName())) {
                entity.setStatus("disabled");
                this.updateById(entity);
                log.info("Disabled orphaned skill: {} (branch: {})", entity.getName(), entity.getBranchName());
            }
        }
    }

    /**
     * 转换为响应 Map（含完整字段）
     */
    private Map<String, Object> toResponseMap(SkillEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("skillNo", entity.getSkillNo());
        map.put("name", entity.getName());
        map.put("displayName", entity.getDisplayName());
        map.put("description", entity.getDescription());
        map.put("implementation", entity.getImplementation());
        map.put("status", entity.getStatus());
        map.put("isPublic", entity.getIsPublic());
        map.put("branchName", entity.getBranchName());
        map.put("latestCommit", entity.getLatestCommit());
        map.put("lastSyncedAt", formatDateTime(entity.getLastSyncedAt()));
        map.put("creatorId", entity.getCreatorId());
        map.put("creatorName", entity.getCreatorName());
        map.put("createdAt", formatDateTime(entity.getCreatedAt()));
        map.put("updatedAt", formatDateTime(entity.getUpdatedAt()));
        return map;
    }

    /**
     * 转换为简单 Map（用于下拉选择）
     */
    private Map<String, Object> toSimpleMap(SkillEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("name", entity.getName());
        map.put("displayName", entity.getDisplayName());
        map.put("description", entity.getDescription());
        map.put("status", entity.getStatus());
        map.put("isPublic", entity.getIsPublic());
        map.put("skillNo", entity.getSkillNo());
        map.put("createdAt", formatDateTime(entity.getCreatedAt()));
        map.put("updatedAt", formatDateTime(entity.getUpdatedAt()));
        return map;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}

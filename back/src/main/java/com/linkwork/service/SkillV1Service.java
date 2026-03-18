package com.linkwork.service;

import com.linkwork.agent.skill.core.SkillClient;
import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.model.CommitInfo;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SkillV1Service {
    private static final String DEFAULT_STATUS = "ready";
    private static final String DEFAULT_CREATOR = "linkwork";

    private final SkillClient skillClient;
    private final Map<String, SkillMeta> metaMap = new ConcurrentHashMap<>();

    public SkillV1Service(SkillClient skillClient) {
        this.skillClient = skillClient;
    }

    public List<SkillDto> listSkills() {
        List<SkillInfo> infos = skillClient.listSkills();
        List<SkillDto> result = new ArrayList<>();
        for (SkillInfo info : infos) {
            SkillMeta meta = metaMap.getOrDefault(info.name(), SkillMeta.EMPTY);
            result.add(toSkillDto(info, meta));
        }
        return result;
    }

    public List<SkillDto> listAvailableSkills() {
        return listSkills();
    }

    public SyncResult syncSkills() {
        List<SkillDto> current = listSkills();
        return new SyncResult(current.size());
    }

    public SkillDetailDto getSkillDetail(String name) {
        SkillInfo info = findSkillInfo(name);
        SkillMeta meta = metaMap.getOrDefault(name, SkillMeta.EMPTY);
        List<FileNode> nodes = skillClient.getTree(name);
        List<SkillFileDto> files = new ArrayList<>();
        for (FileNode node : nodes) {
            files.add(new SkillFileDto(
                    normalizeFilePath(name, node.path(), node.name()),
                    node.type() == FileNode.NodeType.DIRECTORY ? "tree" : "blob"
            ));
        }
        SkillDto dto = toSkillDto(info, meta);
        return new SkillDetailDto(
                dto.id(),
                dto.name(),
                dto.displayName(),
                dto.description(),
                dto.status(),
                dto.isPublic(),
                dto.creatorId(),
                dto.creatorName(),
                dto.branchName(),
                dto.latestCommit(),
                dto.lastSyncedAt(),
                files
        );
    }

    public FileContentDto getFileContent(String name, String path) {
        String content = skillClient.getFile(name, path);
        List<CommitInfo> commits = skillClient.listCommits(name, 1, 1);
        CommitInfo latest = commits.isEmpty() ? null : commits.get(0);
        String commitId = latest == null ? null : latest.id();
        String lastModified = latest == null || latest.authoredAt() == null
                ? Instant.now().toString()
                : latest.authoredAt().toString();
        return new FileContentDto(content, commitId, lastModified);
    }

    public SaveFileResult saveFile(String name, String path, SaveFileRequest request) {
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
                ? "Update " + path
                : request.commitMessage();
        CommitInfo info = skillClient.upsertFile(name, path, request.content(), commitMessage);
        return new SaveFileResult(info.id());
    }

    public CreateSkillResult createSkill(CreateSkillRequest request) {
        String name = safeName(request.name());
        if (request.description() != null || request.isPublic() != null) {
            metaMap.put(name, new SkillMeta(
                    request.description() == null ? "" : request.description(),
                    Boolean.TRUE.equals(request.isPublic())
            ));
        }
        skillClient.upsertFile(
                name,
                "README.md",
                "# " + name + "\n\n" + (request.description() == null ? "" : request.description()),
                "init skill " + name
        );
        SkillInfo info = findSkillInfo(name);
        return new CreateSkillResult(name, name, info.branch());
    }

    public UpdateSkillResult updateSkillMeta(String name, UpdateSkillRequest request) {
        String desc = request.description() == null ? "" : request.description();
        boolean isPublic = request.isPublic() != null && request.isPublic();
        metaMap.put(name, new SkillMeta(desc, isPublic));
        return new UpdateSkillResult(name);
    }

    public void deleteSkill(String name) {
        List<FileNode> files = skillClient.getTree(name);
        for (FileNode file : files) {
            if (file.type() == FileNode.NodeType.FILE) {
                String path = normalizeFilePath(name, file.path(), file.name());
                skillClient.deleteFile(name, path, "delete " + path);
            }
        }
        metaMap.remove(name);
    }

    public List<CommitRecordDto> getHistory(String name, int page, int pageSize) {
        List<CommitInfo> commits = skillClient.listCommits(name, page, pageSize);
        List<CommitRecordDto> result = new ArrayList<>();
        for (CommitInfo commit : commits) {
            result.add(new CommitRecordDto(
                    commit.id(),
                    commit.message(),
                    commit.authorName(),
                    commit.authoredAt() == null ? null : commit.authoredAt().toString()
            ));
        }
        return result;
    }

    public void revertSkill(String name, RevertRequest request) {
        if (request == null || request.commitSha() == null || request.commitSha().isBlank()) {
            throw new SkillException("commitSha is required");
        }
    }

    private SkillInfo findSkillInfo(String name) {
        return skillClient.listSkills().stream()
                .filter(info -> info.name().equals(name))
                .findFirst()
                .orElseGet(() -> new SkillInfo(name, name, "master", null, Instant.now()));
    }

    private SkillDto toSkillDto(SkillInfo info, SkillMeta meta) {
        String updatedAt = info.updatedAt() == null ? Instant.now().toString() : info.updatedAt().toString();
        return new SkillDto(
                info.name(),
                info.name(),
                info.name(),
                meta.description().isBlank() ? "Skill: " + info.name() : meta.description(),
                DEFAULT_STATUS,
                meta.isPublic(),
                DEFAULT_CREATOR,
                DEFAULT_CREATOR,
                info.branch() == null ? "master" : info.branch(),
                info.lastCommitId(),
                updatedAt
        );
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            throw new SkillException("name is required");
        }
        return name.trim();
    }

    private String normalizeFilePath(String skillName, String fullPath, String fallbackName) {
        String path = fullPath == null ? "" : fullPath;
        if (path.isBlank()) {
            return fallbackName;
        }
        path = trimSlashes(path);
        if ("root".equalsIgnoreCase(skillName)) {
            return path;
        }
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

    public record SkillDto(
            String id,
            String name,
            String displayName,
            String description,
            String status,
            boolean isPublic,
            String creatorId,
            String creatorName,
            String branchName,
            String latestCommit,
            String lastSyncedAt
    ) {
    }

    public record SkillFileDto(String path, String type) {
    }

    public record SkillDetailDto(
            String id,
            String name,
            String displayName,
            String description,
            String status,
            boolean isPublic,
            String creatorId,
            String creatorName,
            String branchName,
            String latestCommit,
            String lastSyncedAt,
            List<SkillFileDto> files
    ) {
    }

    public record FileContentDto(String content, String commitId, String lastModified) {
    }

    public record SaveFileRequest(String content, String commitMessage, String lastCommitId) {
    }

    public record SaveFileResult(String commitId) {
    }

    public record CreateSkillRequest(String name, String description, Boolean isPublic) {
    }

    public record CreateSkillResult(String id, String name, String branchName) {
    }

    public record UpdateSkillRequest(String description, Boolean isPublic) {
    }

    public record UpdateSkillResult(String name) {
    }

    public record RevertRequest(String commitSha) {
    }

    public record CommitRecordDto(String sha, String message, String authorName, String createdAt) {
    }

    public record SyncResult(int synced) {
    }

    private record SkillMeta(String description, boolean isPublic) {
        private static final SkillMeta EMPTY = new SkillMeta("", false);
    }
}

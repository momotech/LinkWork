package com.linkwork.service.skill.provider.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.SkillProvider;
import com.linkwork.agent.skill.core.model.CommitInfo;
import com.linkwork.agent.skill.core.model.FileNode;
import com.linkwork.agent.skill.core.model.SkillInfo;
import com.linkwork.config.skill.GitHubProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GitHubSkillProvider implements SkillProvider {
    private final RestClient restClient;
    private final GitHubProperties properties;

    public GitHubSkillProvider(RestClient restClient, GitHubProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public List<SkillInfo> listSkills() {
        List<SkillInfo> result = new ArrayList<>();
        JsonNode nodes = listContents(rawRootPath());
        for (JsonNode node : nodes) {
            if (!"dir".equalsIgnoreCase(node.path("type").asText())) {
                continue;
            }
            String name = node.path("name").asText();
            String path = node.path("path").asText();
            List<CommitInfo> commits = listCommits(name, 1, 1);
            CommitInfo latest = commits.isEmpty() ? null : commits.get(0);
            result.add(new SkillInfo(
                    name,
                    path,
                    properties.getBranch(),
                    latest == null ? null : latest.id(),
                    latest == null ? null : latest.authoredAt()
            ));
        }
        return result;
    }

    @Override
    public List<FileNode> getTree(String skillName) {
        JsonNode nodes = listContents(skillPath(skillName));
        List<FileNode> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            FileNode.NodeType type = "dir".equalsIgnoreCase(node.path("type").asText())
                    ? FileNode.NodeType.DIRECTORY
                    : FileNode.NodeType.FILE;
            Long size = node.path("size").isMissingNode() ? null : node.path("size").asLong();
            result.add(new FileNode(
                    node.path("name").asText(),
                    node.path("path").asText(),
                    type,
                    node.path("sha").asText(),
                    size
            ));
        }
        return result;
    }

    @Override
    public String getFile(String skillName, String filePath) {
        String fullPath = skillPath(skillName, filePath);
        JsonNode file = getFileNode(fullPath);
        String content = file.path("content").asText();
        if (content == null || content.isBlank()) {
            return "";
        }
        byte[] decoded = Base64.getDecoder().decode(content.replace("\n", ""));
        return new String(decoded, StandardCharsets.UTF_8);
    }

    @Override
    public CommitInfo upsertFile(String skillName, String filePath, String content, String commitMessage) {
        String fullPath = skillPath(skillName, filePath);
        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage);
        body.put("branch", properties.getBranch());
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("committer", Map.of("name", "linkwork-bot", "email", "bot@linkwork.local"));
        JsonNode existing = null;
        try {
            existing = getFileNode(fullPath);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
        if (existing != null) {
            body.put("sha", existing.path("sha").asText());
        }
        JsonNode response = restClient.put()
                .uri(fileEndpoint(fullPath))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return mapCommit(response.path("commit"));
    }

    @Override
    public CommitInfo deleteFile(String skillName, String filePath, String commitMessage) {
        String fullPath = skillPath(skillName, filePath);
        JsonNode existing = getFileNode(fullPath);
        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage);
        body.put("branch", properties.getBranch());
        body.put("sha", existing.path("sha").asText());
        body.put("committer", Map.of("name", "linkwork-bot", "email", "bot@linkwork.local"));
        JsonNode response = restClient.method(HttpMethod.DELETE)
                .uri(fileEndpoint(fullPath))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return mapCommit(response.path("commit"));
    }

    @Override
    public List<CommitInfo> listCommits(String skillName, int page, int pageSize) {
        JsonNode nodes = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(repoEndpoint("/commits"))
                        .queryParam("sha", properties.getBranch())
                        .queryParam("path", skillPath(skillName))
                        .queryParam("page", Math.max(1, page))
                        .queryParam("per_page", Math.max(1, pageSize))
                        .build())
                .retrieve()
                .body(JsonNode.class);
        List<CommitInfo> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(mapCommit(node));
        }
        return result;
    }

    private JsonNode listContents(String path) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(contentsEndpoint(path))
                        .queryParam("ref", properties.getBranch())
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode getFileNode(String fullPath) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(fileEndpoint(fullPath))
                        .queryParam("ref", properties.getBranch())
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    private CommitInfo mapCommit(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new CommitInfo(null, null, null, null, null, null);
        }
        String message = node.path("commit").path("message").asText(
                node.path("message").asText(null)
        );
        String title = null;
        if (message != null) {
            int idx = message.indexOf('\n');
            title = idx >= 0 ? message.substring(0, idx) : message;
        }
        String authoredDate = node.path("commit").path("author").path("date").asText(null);
        Instant authoredAt = null;
        if (authoredDate != null && !authoredDate.isBlank()) {
            authoredAt = Instant.parse(authoredDate);
        }
        String authorName = node.path("commit").path("author").path("name").asText(
                node.path("author").path("login").asText(null)
        );
        return new CommitInfo(
                node.path("sha").asText(node.path("id").asText(null)),
                title,
                message,
                authorName,
                authoredAt,
                node.path("html_url").asText(null)
        );
    }

    private String rawRootPath() {
        return trimSlashes(properties.getRootPath());
    }

    private String skillPath(String skillName) {
        requireRepo();
        if (skillName == null || skillName.isBlank()) {
            throw new SkillException("skillName cannot be blank");
        }
        String root = rawRootPath();
        String skill = trimSlashes(skillName);
        return root.isEmpty() ? skill : root + "/" + skill;
    }

    private String skillPath(String skillName, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new SkillException("filePath cannot be blank");
        }
        return skillPath(skillName) + "/" + trimSlashes(filePath);
    }

    private String contentsEndpoint(String path) {
        requireRepo();
        String encoded = UriUtils.encodePath(path == null ? "" : path, StandardCharsets.UTF_8);
        return repoEndpoint("/contents/" + encoded);
    }

    private String fileEndpoint(String fullPath) {
        String encoded = UriUtils.encodePath(fullPath, StandardCharsets.UTF_8);
        return repoEndpoint("/contents/" + encoded);
    }

    private String repoEndpoint(String suffix) {
        requireRepo();
        String owner = UriUtils.encodePathSegment(properties.getOwner(), StandardCharsets.UTF_8);
        String repo = UriUtils.encodePathSegment(properties.getRepo(), StandardCharsets.UTF_8);
        return "/repos/" + owner + "/" + repo + suffix;
    }

    private String trimSlashes(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        String out = text;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private void requireRepo() {
        if (properties.getOwner() == null || properties.getOwner().isBlank()) {
            throw new SkillException("linkwork.skill.github.owner is required");
        }
        if (properties.getRepo() == null || properties.getRepo().isBlank()) {
            throw new SkillException("linkwork.skill.github.repo is required");
        }
    }
}

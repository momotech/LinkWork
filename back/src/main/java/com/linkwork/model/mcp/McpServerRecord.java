package com.linkwork.model.mcp;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@TableName(value = "robot_mcp_server", autoResultMap = true)
public class McpServerRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String mcpNo;
    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String endpoint;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private String visibility;
    private String status;
    private String type;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String url;
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, String> headers = new LinkedHashMap<>();
    private String networkZone;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String healthCheckUrl;
    private Integer healthLatencyMs;
    private String healthMessage;
    private Integer consecutiveFailures;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String version;
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> tags = new ArrayList<>();
    private LocalDateTime lastHealthAt;
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, Object> configJson = new LinkedHashMap<>();
    private String creatorId;
    private String creatorName;
    private String updaterId;
    private String updaterName;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Boolean isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMcpNo() {
        return mcpNo;
    }

    public void setMcpNo(String mcpNo) {
        this.mcpNo = mcpNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public String getNetworkZone() {
        return networkZone;
    }

    public void setNetworkZone(String networkZone) {
        this.networkZone = networkZone;
    }

    public String getHealthCheckUrl() {
        return healthCheckUrl;
    }

    public void setHealthCheckUrl(String healthCheckUrl) {
        this.healthCheckUrl = healthCheckUrl;
    }

    public Integer getHealthLatencyMs() {
        return healthLatencyMs;
    }

    public void setHealthLatencyMs(Integer healthLatencyMs) {
        this.healthLatencyMs = healthLatencyMs;
    }

    public String getHealthMessage() {
        return healthMessage;
    }

    public void setHealthMessage(String healthMessage) {
        this.healthMessage = healthMessage;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public LocalDateTime getLastHealthAt() {
        return lastHealthAt;
    }

    public void setLastHealthAt(LocalDateTime lastHealthAt) {
        this.lastHealthAt = lastHealthAt;
    }

    public Map<String, Object> getConfigJson() {
        return configJson;
    }

    public void setConfigJson(Map<String, Object> configJson) {
        this.configJson = configJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(configJson);
    }

    public String getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(String creatorId) {
        this.creatorId = creatorId;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }

    public String getUpdaterId() {
        return updaterId;
    }

    public void setUpdaterId(String updaterId) {
        this.updaterId = updaterId;
    }

    public String getUpdaterName() {
        return updaterName;
    }

    public void setUpdaterName(String updaterName) {
        this.updaterName = updaterName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted;
    }
}

package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName(value = "linkwork_skill", autoResultMap = true)
public class SkillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillNo;

    private String name;

    private String displayName;

    private String description;

    private String implementation;

    private String status;

    private Boolean isPublic;

    private String branchName;

    private String latestCommit;

    private LocalDateTime lastSyncedAt;

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

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSkillNo() { return skillNo; }
    public void setSkillNo(String skillNo) { this.skillNo = skillNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImplementation() { return implementation; }
    public void setImplementation(String implementation) { this.implementation = implementation; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getLatestCommit() { return latestCommit; }
    public void setLatestCommit(String latestCommit) { this.latestCommit = latestCommit; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getCreatorId() { return creatorId; }
    public void setCreatorId(String creatorId) { this.creatorId = creatorId; }

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }

    public String getUpdaterId() { return updaterId; }
    public void setUpdaterId(String updaterId) { this.updaterId = updaterId; }

    public String getUpdaterName() { return updaterName; }
    public void setUpdaterName(String updaterName) { this.updaterName = updaterName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
}

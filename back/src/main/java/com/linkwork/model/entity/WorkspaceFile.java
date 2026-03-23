package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_file")
public class WorkspaceFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileId;

    private String fileName;

    private Long fileSize;

    private String fileType;

    private String contentType;

    private String spaceType;

    private String workstationId;

    private String userId;

    private String ossPath;

    private String parsedOssPath;

    private String parseStatus;

    private String memoryIndexStatus;

    private String fileHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}

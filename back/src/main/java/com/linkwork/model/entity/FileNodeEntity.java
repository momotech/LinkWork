package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_file_node")
public class FileNodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String nodeId;

    private String parentId;

    private String entryType;

    private String name;

    private String spaceType;

    private String workstationId;

    private String userId;

    private String fileId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}

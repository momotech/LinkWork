package com.linkwork.model.dto;

import lombok.Data;

@Data
public class CreateFolderRequest {
    private String name;
    private String spaceType;
    private String workstationId;
    private String parentId;
}

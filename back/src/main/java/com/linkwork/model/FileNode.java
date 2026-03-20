package com.linkwork.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileNode {
    private String name;
    private String type; // "file" or "directory"
    private String content;
    private String size;
    private List<FileNode> children;
}

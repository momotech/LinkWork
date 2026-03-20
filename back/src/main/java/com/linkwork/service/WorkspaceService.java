package com.linkwork.service;

import com.linkwork.model.FileNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class WorkspaceService {

    public List<FileNode> listFiles(String taskId) {
        // 在真实场景中，这里会读取 /tmp/workspace/{taskId} 目录
        // 现在返回与前端一致的 Mock 数据以供联调
        List<FileNode> files = new ArrayList<>();

        FileNode src = FileNode.builder()
                .name("src")
                .type("directory")
                .children(new ArrayList<>())
                .build();

        src.getChildren().add(FileNode.builder()
                .name("main.py")
                .type("file")
                .content("import os\nimport time\n\ndef main():\n    print(\"Robot Agent starting...\")\n    time.sleep(1)\n    print(\"Initialization complete.\")\n\nif __name__ == \"__main__\":\n    main()")
                .size("1.2KB")
                .build());

        src.getChildren().add(FileNode.builder()
                .name("utils.py")
                .type("file")
                .content("def format_bytes(size):\n    power = 2**10\n    n = 0\n    power_labels = {0 : '', 1: 'K', 2: 'M', 3: 'G'}\n    while size > power:\n        size /= power\n        n += 1\n    return f\"{size:.2f} {power_labels[n]}B\"")
                .size("0.8KB")
                .build());

        files.add(src);
        files.add(FileNode.builder()
                .name("requirements.txt")
                .type("file")
                .content("anthropic>=0.40.0\nrequests>=2.31.0\npydantic>=2.5.0\nhttpx>=0.26.0")
                .size("0.1KB")
                .build());
        
        files.add(FileNode.builder()
                .name("README.md")
                .type("file")
                .content("# Robot Agent Project\n\nThis workspace is served from robot-web-service for task: " + taskId)
                .size("0.5KB")
                .build());

        return files;
    }
}

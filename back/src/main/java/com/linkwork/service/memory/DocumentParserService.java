package com.linkwork.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class DocumentParserService {

    private final Tika tika = new Tika();

    public String parseFile(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown") || fileName.endsWith(".txt")) {
            return Files.readString(filePath);
        }
        try (InputStream is = Files.newInputStream(filePath)) {
            return tika.parseToString(is);
        } catch (TikaException e) {
            throw new IOException("Tika parsing failed for " + filePath, e);
        }
    }

    public String detectFileType(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "markdown";
        if (name.endsWith(".txt")) return "text";
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".docx") || name.endsWith(".doc")) return "word";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "html";
        if (name.endsWith(".pptx") || name.endsWith(".ppt")) return "ppt";
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return "excel";
        return "unknown";
    }

    public boolean isIndexable(String fileType) {
        return switch (fileType) {
            case "markdown", "text", "pdf", "word", "html" -> true;
            default -> false;
        };
    }
}

package com.linkwork.service.memory;

import com.linkwork.config.MemoryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown/text chunking by headings, with SHA-256 content dedup.
 * Ported from memsearch chunker.py.
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class TextChunkerService {

    private final MemoryConfig memoryConfig;
    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    public record Chunk(
            String content,
            String source,
            String heading,
            int headingLevel,
            int startLine,
            int endLine,
            String contentHash
    ) {}

    public List<Chunk> chunkMarkdown(String text, String source) {
        int maxChunkSize = memoryConfig.getIndex().getMaxChunkSize();
        int overlapLines = memoryConfig.getIndex().getOverlapLines();
        return chunkMarkdown(text, source, maxChunkSize, overlapLines);
    }

    public List<Chunk> chunkMarkdown(String text, String source, int maxChunkSize, int overlapLines) {
        String[] lines = text.split("\n", -1);

        List<int[]> headingPositions = new ArrayList<>(); // [lineIdx, level]
        List<String> headingTitles = new ArrayList<>();
        Matcher m = HEADING_RE.matcher(text);
        int lineStart = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher lineMatcher = HEADING_RE.matcher(lines[i]);
            if (lineMatcher.matches()) {
                headingPositions.add(new int[]{i, lineMatcher.group(1).length()});
                headingTitles.add(lineMatcher.group(2).trim());
            }
        }

        // Build sections between headings
        List<int[]> sections = new ArrayList<>(); // [start, end, headingIdx]
        if (headingPositions.isEmpty() || headingPositions.get(0)[0] > 0) {
            int sectionEnd = headingPositions.isEmpty() ? lines.length : headingPositions.get(0)[0];
            sections.add(new int[]{0, sectionEnd, -1}); // preamble
        }
        for (int idx = 0; idx < headingPositions.size(); idx++) {
            int lineIdx = headingPositions.get(idx)[0];
            int nextStart = (idx + 1 < headingPositions.size())
                    ? headingPositions.get(idx + 1)[0]
                    : lines.length;
            sections.add(new int[]{lineIdx, nextStart, idx});
        }

        List<Chunk> chunks = new ArrayList<>();
        for (int[] sec : sections) {
            int start = sec[0], end = sec[1], hIdx = sec[2];
            String heading = hIdx >= 0 ? headingTitles.get(hIdx) : "";
            int level = hIdx >= 0 ? headingPositions.get(hIdx)[1] : 0;

            String sectionText = joinLines(lines, start, end).strip();
            if (sectionText.isEmpty()) continue;

            if (sectionText.length() <= maxChunkSize) {
                chunks.add(new Chunk(sectionText, source, heading, level,
                        start + 1, end, sha256Short(sectionText)));
            } else {
                chunks.addAll(splitLargeSection(lines, start, end, source,
                        heading, level, maxChunkSize, overlapLines));
            }
        }
        return chunks;
    }

    /**
     * Compute composite chunk ID matching memsearch/OpenClaw format.
     */
    public String computeChunkId(String source, int startLine, int endLine, String contentHash, String model) {
        String raw = "markdown:" + source + ":" + startLine + ":" + endLine + ":" + contentHash + ":" + model;
        return sha256Short(raw);
    }

    private List<Chunk> splitLargeSection(String[] lines, int start, int end, String source,
                                          String heading, int headingLevel, int maxSize, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        int currentStart = 0;

        for (int i = start; i < end; i++) {
            currentLines.add(lines[i]);
            String text = String.join("\n", currentLines);
            boolean isParagraphBreak = lines[i].strip().isEmpty() && (i + 1 < end);
            boolean isLastLine = (i == end - 1);

            if ((text.length() >= maxSize && isParagraphBreak) || isLastLine) {
                String content = text.strip();
                if (!content.isEmpty()) {
                    chunks.add(new Chunk(content, source, heading, headingLevel,
                            start + currentStart + 1, start + i + 1, sha256Short(content)));
                }
                int overlapStart = Math.max(0, currentLines.size() - overlap);
                currentLines = isLastLine ? new ArrayList<>()
                        : new ArrayList<>(currentLines.subList(overlapStart, currentLines.size()));
                currentStart = i + 1 - currentLines.size() - start;
            }
        }
        return chunks;
    }

    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String sha256Short(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

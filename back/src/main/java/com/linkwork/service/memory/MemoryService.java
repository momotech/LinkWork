package com.linkwork.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.MemoryConfig;
import com.linkwork.model.dto.MemoryIndexJob;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.model.dto.MemoryIndexJob.JobType;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.service.NfsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryConfig memoryConfig;
    private final MilvusStoreService milvusStore;
    private final TextChunkerService chunker;
    private final DocumentParserService documentParser;
    private final EmbeddingService embeddingService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private NfsStorageService nfsStorageService;

    @Autowired(required = false)
    private WorkspaceFileMapper workspaceFileMapper;

    public List<Map<String, Object>> search(String workstationId, String userId, String query, int topK) {
        String collection = memoryConfig.collectionName(workstationId, userId);
        List<Float> embedding = embeddingService.embedSingle(query);
        return milvusStore.search(collection, embedding, query, topK);
    }

    public void ingest(String workstationId, String userId, String content, String source) {
        MemoryIndexJob job = MemoryIndexJob.builder()
                .jobId(UUID.randomUUID().toString())
                .workstationId(workstationId)
                .userId(userId)
                .jobType(JobType.SESSION_SUMMARY)
                .content(content)
                .source(source.isEmpty() ? "session-summary/" + java.time.LocalDate.now() : source)
                .build();
        enqueueJob(job);
    }

    public void triggerIndexFile(String workstationId, String userId, String filePath) {
        Path path = Path.of(filePath);
        String fileType = documentParser.detectFileType(path);
        MemoryIndexJob job = MemoryIndexJob.builder()
                .jobId(UUID.randomUUID().toString())
                .workstationId(workstationId)
                .userId(userId)
                .jobType(JobType.FILE_UPLOAD)
                .filePath(filePath)
                .source(filePath)
                .fileType(fileType)
                .build();
        enqueueJob(job);
    }

    public int triggerBatchIndex(String workstationId, String userId, List<String> filePaths) {
        int count = 0;
        for (String fp : filePaths) {
            Path path = Path.of(fp);
            String fileType = documentParser.detectFileType(path);
            if (!documentParser.isIndexable(fileType)) {
                log.debug("Skipping non-indexable file: {} (type={})", fp, fileType);
                continue;
            }
            MemoryIndexJob job = MemoryIndexJob.builder()
                    .jobId(UUID.randomUUID().toString())
                    .workstationId(workstationId)
                    .userId(userId)
                    .jobType(JobType.MEMORY_WRITEBACK)
                    .filePath(fp)
                    .source(fp)
                    .fileType(fileType)
                    .build();
            enqueueJob(job);
            count++;
        }
        log.info("Enqueued batch index: {} files for ws={}, user={}", count, workstationId, userId);
        return count;
    }

    public List<Map<String, Object>> recent(String workstationId, String userId, int limit) {
        String collection = memoryConfig.collectionName(workstationId, userId);
        return milvusStore.recent(collection, limit);
    }

    public Map<String, Object> stats(String workstationId, String userId) {
        String collection = memoryConfig.collectionName(workstationId, userId);
        long count = milvusStore.count(collection);
        return Map.of(
                "collection", collection,
                "chunkCount", count,
                "workstationId", workstationId,
                "userId", userId
        );
    }

    public void deleteSource(String workstationId, String userId, String source) {
        String collection = memoryConfig.collectionName(workstationId, userId);
        milvusStore.deleteBySource(collection, source);
    }

    /**
     * Process an index job synchronously (called by MemoryIndexConsumer).
     * Incremental: only new/changed chunks upserted, stale chunks deleted.
     */
    public void processIndexJob(MemoryIndexJob job) {
        String collection = job.getCollectionName() != null && !job.getCollectionName().isBlank()
                ? job.getCollectionName()
                : memoryConfig.collectionName(job.getWorkstationId(), job.getUserId());
        String model = memoryConfig.getEmbedding().getModel();

        String text;
        String source = job.getSource();

        if (job.getJobType() == JobType.SESSION_SUMMARY) {
            text = job.getContent();
        } else if (("NFS".equals(job.getStorageType()) || "OSS".equals(job.getStorageType())) && job.getObjectName() != null) {
            if (nfsStorageService == null) {
                log.warn("Skip NFS memory index job because NfsStorageService is unavailable: jobId={}", job.getJobId());
                return;
            }
            Path temp = null;
            try {
                temp = nfsStorageService.downloadToTempFile(job.getObjectName());
                text = documentParser.parseFile(temp);
                source = job.getObjectName();
            } catch (Exception e) {
                log.error("Failed to parse NFS object {}: {}", job.getObjectName(), e.getMessage(), e);
                return;
            } finally {
                if (temp != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(temp);
                    } catch (Exception e) {
                        log.warn("Failed to cleanup temp file {}: {}", temp, e.getMessage());
                    }
                }
            }
        } else {
            try {
                text = documentParser.parseFile(Path.of(job.getFilePath()));
            } catch (Exception e) {
                log.error("Failed to parse file {}: {}", job.getFilePath(), e.getMessage(), e);
                return;
            }
        }

        List<TextChunkerService.Chunk> chunks = chunker.chunkMarkdown(text, source);
        if (chunks.isEmpty()) {
            milvusStore.deleteBySource(collection, source);
            log.info("No chunks from source {}, removed stale data", source);
            return;
        }

        Map<String, TextChunkerService.Chunk> newChunkMap = new LinkedHashMap<>();
        for (TextChunkerService.Chunk c : chunks) {
            String id = chunker.computeChunkId(c.source(), c.startLine(), c.endLine(), c.contentHash(), model);
            newChunkMap.put(id, c);
        }

        Set<String> existingHashes = milvusStore.hashesBySource(collection, source);

        Set<String> staleHashes = new HashSet<>(existingHashes);
        staleHashes.removeAll(newChunkMap.keySet());
        if (!staleHashes.isEmpty()) {
            milvusStore.deleteByHashes(collection, new ArrayList<>(staleHashes));
            log.info("Removed {} stale chunks from source {}", staleHashes.size(), source);
        }

        List<String> newIds = new ArrayList<>();
        List<TextChunkerService.Chunk> newChunks = new ArrayList<>();
        for (Map.Entry<String, TextChunkerService.Chunk> entry : newChunkMap.entrySet()) {
            if (!existingHashes.contains(entry.getKey())) {
                newIds.add(entry.getKey());
                newChunks.add(entry.getValue());
            }
        }

        if (newChunks.isEmpty()) {
            log.debug("All chunks already indexed for source {}", source);
            return;
        }

        List<String> contents = newChunks.stream().map(TextChunkerService.Chunk::content).toList();
        List<List<Float>> embeddings = embeddingService.embed(contents);
        long now = System.currentTimeMillis();

        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < newChunks.size(); i++) {
            TextChunkerService.Chunk c = newChunks.get(i);
            Map<String, Object> rec = new HashMap<>();
            rec.put("chunk_hash", newIds.get(i));
            rec.put("embedding", embeddings.get(i));
            rec.put("content", c.content());
            rec.put("source", c.source());
            rec.put("heading", c.heading());
            rec.put("heading_level", (long) c.headingLevel());
            rec.put("start_line", (long) c.startLine());
            rec.put("end_line", (long) c.endLine());
            rec.put("file_type", job.getFileType() != null ? job.getFileType() : "");
            rec.put("indexed_at", now);
            records.add(rec);
        }

        int upserted = milvusStore.upsert(collection, records);
        log.info("Indexed {} new chunks from source {} into {}", upserted, source, collection);

        if (workspaceFileMapper != null && job.getObjectName() != null
                && ("NFS".equals(job.getStorageType()) || "OSS".equals(job.getStorageType()))) {
            WorkspaceFile file = workspaceFileMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkspaceFile>()
                    .and(w -> w.eq(WorkspaceFile::getOssPath, job.getObjectName())
                            .or().eq(WorkspaceFile::getParsedOssPath, job.getObjectName()))
                    .isNull(WorkspaceFile::getDeletedAt)
                    .last("limit 1"));
            if (file != null) {
                file.setMemoryIndexStatus("INDEXED");
                file.setUpdatedAt(java.time.LocalDateTime.now());
                workspaceFileMapper.updateById(file);
            }
        }
    }

    private void enqueueJob(MemoryIndexJob job) {
        try {
            String json = objectMapper.writeValueAsString(job);
            redisTemplate.opsForList().leftPush(memoryConfig.getIndex().getQueueKey(), json);
        } catch (Exception e) {
            log.error("Failed to enqueue index job: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue memory index job", e);
        }
    }
}

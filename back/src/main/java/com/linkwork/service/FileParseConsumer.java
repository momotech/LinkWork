package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.service.memory.DocumentParserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileParseConsumer {

    private static final String FILE_PARSE_QUEUE_KEY = "file:parse:jobs";
    private static final int POLL_INTERVAL_MS = 1000;

    private final StringRedisTemplate redisTemplate;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final NfsStorageService nfsStorageService;
    private final FileService fileService;

    @Autowired(required = false)
    private DocumentParserService documentParserService;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executorService;

    @PostConstruct
    public void start() {
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-parse-consumer");
            t.setDaemon(true);
            return t;
        });
        executorService.submit(this::consumeLoop);
        log.info("FileParseConsumer started, queue={}", FILE_PARSE_QUEUE_KEY);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("FileParseConsumer stopped");
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                String fileId = redisTemplate.opsForList().rightPop(FILE_PARSE_QUEUE_KEY);
                if (fileId == null) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                processFile(fileId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("消费文件解析任务失败", e);
                try {
                    Thread.sleep(POLL_INTERVAL_MS * 2L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processFile(String fileId) {
        WorkspaceFile file = workspaceFileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getFileId, fileId)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("limit 1"));
        if (file == null) {
            log.warn("文件解析任务找不到文件记录: fileId={}", fileId);
            return;
        }
        if (documentParserService == null) {
            log.warn("DocumentParserService 未启用，跳过解析: fileId={}", fileId);
            file.setParseStatus("FAILED");
            file.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(file);
            return;
        }

        Path tempPath = null;
        try {
            tempPath = nfsStorageService.downloadToTempFile(file.getOssPath());
            String text = documentParserService.parseFile(tempPath);
            String parsedOssPath = fileService.buildParsedPath(file.getOssPath());
            nfsStorageService.uploadTextToPath(text, parsedOssPath);

            file.setParsedOssPath(parsedOssPath);
            file.setParseStatus("PARSED");
            file.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(file);

            fileService.triggerMemoryIndex(file);
        } catch (Exception e) {
            log.error("文件解析失败: fileId={}", fileId, e);
            file.setParseStatus("FAILED");
            file.setUpdatedAt(LocalDateTime.now());
            workspaceFileMapper.updateById(file);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception e) {
                    log.warn("删除临时文件失败: {}", tempPath, e);
                }
            }
        }
    }
}

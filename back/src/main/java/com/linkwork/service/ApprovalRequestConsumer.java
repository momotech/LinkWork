package com.linkwork.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.model.entity.Approval;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审批请求消费者
 * <p>
 * 监听 momo-worker 写入的审批请求队列 (Redis List)，
 * 自动创建审批记录到数据库。
 * <p>
 * 队列 Key: approval:{workstationId}
 * 消息格式:
 * <pre>
 * {
 *   "request_id": "req-uuid",
 *   "task_id": "test-task-001",
 *   "tool_name": "Bash",
 *   "command": "sudo whoami",
 *   "risk_level": "high",
 *   "timestamp": "2026-02-10T10:00:00Z"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalRequestConsumer {

    private final StringRedisTemplate redisTemplate;
    private final DispatchConfig dispatchConfig;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "approval-consumer");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        running.set(true);
        executor.submit(this::consumeLoop);
        log.info("审批请求消费者已启动，监听队列模式: {}", dispatchConfig.getApprovalRequestKeyPattern());
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        executor.shutdownNow();
        log.info("审批请求消费者已停止");
    }

    private void consumeLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                boolean consumed = false;
                for (String queueKey : resolveApprovalRequestQueues()) {
                    String message = redisTemplate.opsForList().rightPop(queueKey);
                    if (message == null) {
                        continue;
                    }
                    processApprovalRequest(message);
                    consumed = true;
                    break;
                }
                if (!consumed) {
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (running.get()) {
                    log.error("消费审批请求异常，5秒后重试", e);
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processApprovalRequest(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            String requestId = json.has("request_id") ? json.get("request_id").asText() : null;
            String taskId = json.has("task_id") ? json.get("task_id").asText() : null;
            String command = json.has("command") ? json.get("command").asText() : "";
            String reason = json.has("reason") ? json.get("reason").asText() : "";

            // tool_name 优先，否则从 command_type 推导
            String toolName = json.has("tool_name") ? json.get("tool_name").asText() : null;
            if (toolName == null || toolName.isEmpty()) {
                String commandType = json.has("command_type") ? json.get("command_type").asText() : "";
                toolName = mapCommandTypeToToolName(commandType);
            }

            // risk_level: 兼容整数 (momo-worker) 和字符串两种格式
            String riskLevel = mapRiskLevel(json.get("risk_level"));

            if (requestId == null) {
                log.warn("审批请求缺少 request_id，跳过: {}", message);
                return;
            }

            log.info("收到审批请求: requestId={}, taskId={}, toolName={}, command={}, riskLevel={}",
                    requestId, taskId, toolName, command, riskLevel);

            // 构建操作描述
            String action = String.format("%s: %s", toolName, command);
            String description = (reason != null && !reason.isEmpty())
                    ? reason
                    : String.format("momo-worker 请求审批: %s", action);

            // 创建审批记录（通过 ApprovalService）
            Approval approval = approvalService.createApproval(
                    taskId,           // taskNo
                    "任务 " + taskId, // taskTitle
                    action,           // action
                    description,      // description
                    riskLevel,        // riskLevel
                    "momo-worker",    // creatorId
                    "AI 执行器"       // creatorName
            );

            // 保存 requestId 到审批记录（用于后续回写响应）
            approval.setRequestId(requestId);
            approvalService.updateRequestId(approval.getApprovalNo(), requestId);

            log.info("审批记录已创建: approvalNo={}, requestId={}", approval.getApprovalNo(), requestId);

            // 向任务日志 Stream 写入 USER_CONFIRM_REQUEST 事件，驱动 WebSocket 通知前端
            publishApprovalEvent(taskId, approval, requestId, command, riskLevel, reason);

        } catch (Exception e) {
            log.error("处理审批请求失败: {}", message, e);
        }
    }

    /**
     * risk_level 映射：兼容整数 (momo-worker) 和字符串格式
     */
    private String mapRiskLevel(JsonNode node) {
        if (node == null || node.isNull()) return "medium";
        if (node.isTextual()) {
            String text = node.asText().toLowerCase();
            if (text.matches("low|medium|high|critical")) return text;
        }
        if (node.isNumber()) {
            return switch (node.asInt()) {
                case 1 -> "low";
                case 2 -> "medium";
                case 3 -> "high";
                case 4 -> "critical";
                default -> "high"; // 0 或未知 → high（策略触发的审批默认高风险）
            };
        }
        return "medium";
    }

    /**
     * command_type → tool_name 映射
     */
    private String mapCommandTypeToToolName(String commandType) {
        if (commandType == null || commandType.isEmpty()) return "Shell";
        return switch (commandType.toLowerCase()) {
            case "shell", "bash" -> "Bash";
            case "python" -> "Python";
            default -> commandType;
        };
    }

    /**
     * 向任务日志 Stream 写入 USER_CONFIRM_REQUEST 事件
     * 让 TaskWebSocketHandler 能推送到前端
     */
    private void publishApprovalEvent(String taskId, Approval approval,
                                      String requestId, String command,
                                      String riskLevel, String reason) {
        try {
            Long roleId = resolveRoleIdByTaskNo(taskId);
            String streamKey = dispatchConfig.getLogStreamKey(roleId, taskId);

            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("approval_no", approval.getApprovalNo());
            eventData.put("request_id", requestId);
            eventData.put("task_id", taskId);
            eventData.put("command", command);
            eventData.put("risk_level", riskLevel);
            eventData.put("reason", reason);
            eventData.put("expired_at", approval.getExpiredAt() != null
                    ? approval.getExpiredAt().toString() : "");

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event_type", "USER_CONFIRM_REQUEST");
            fields.put("timestamp", Instant.now().toString());
            fields.put("session_id", "backend");
            fields.put("data", objectMapper.writeValueAsString(eventData));

            redisTemplate.opsForStream().add(
                    StreamRecords.string(fields).withStreamKey(streamKey));

            log.info("USER_CONFIRM_REQUEST 事件已写入 Stream: key={}, approvalNo={}",
                    streamKey, approval.getApprovalNo());

        } catch (Exception e) {
            log.error("写入 USER_CONFIRM_REQUEST 事件失败（不影响审批创建）: {}", e.getMessage());
        }
    }

    private List<String> resolveApprovalRequestQueues() {
        Set<String> keySet = redisTemplate.keys(dispatchConfig.getApprovalRequestKeyPattern());
        if (keySet == null || keySet.isEmpty()) {
            return List.of(dispatchConfig.getApprovalRequestKey());
        }
        List<String> queues = new ArrayList<>();
        for (String key : keySet) {
            if (key == null || key.contains(":response:")) {
                continue;
            }
            queues.add(key);
        }
        if (queues.isEmpty()) {
            queues.add(dispatchConfig.getApprovalRequestKey());
        }
        Collections.sort(queues);
        return queues;
    }

    private Long resolveRoleIdByTaskNo(String taskNo) {
        if (taskNo == null || taskNo.isEmpty()) {
            return null;
        }
        try {
            return taskService.getTaskByNo(taskNo).getRoleId();
        } catch (Exception e) {
            log.debug("审批请求链路解析任务 roleId 失败，回退默认 workstation: taskNo={}", taskNo);
            return null;
        }
    }
}

package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.common.SnowflakeIdGenerator;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.ApprovalMapper;
import com.linkwork.model.entity.Approval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.redis.connection.stream.StreamRecords;

/**
 * 审批服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalMapper approvalMapper;
    private final StringRedisTemplate redisTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final DispatchConfig dispatchConfig;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;

    /**
     * 获取审批列表（分页）
     */
    public Page<Approval> listApprovals(String status, Integer page, Integer pageSize) {
        return listApprovals(status, page, pageSize, null);
    }

    /**
     * 获取审批列表（分页，按创建人隔离）
     */
    public Page<Approval> listApprovals(String status, Integer page, Integer pageSize, String creatorId) {
        LambdaQueryWrapper<Approval> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) {
            wrapper.eq(Approval::getCreatorId, creatorId);
        }
        if (StringUtils.hasText(status) && !"all".equalsIgnoreCase(status)) {
            wrapper.eq(Approval::getStatus, status);
        }
        wrapper.orderByDesc(Approval::getCreatedAt);
        return approvalMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 获取审批统计
     */
    public Map<String, Long> getStats() {
        return getStats(null);
    }

    /**
     * 获取审批统计（按创建人隔离）
     */
    public Map<String, Long> getStats(String creatorId) {
        Map<String, Long> stats = new LinkedHashMap<>();

        LambdaQueryWrapper<Approval> pendingWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) {
            pendingWrapper.eq(Approval::getCreatorId, creatorId);
        }
        pendingWrapper.eq(Approval::getStatus, "pending");
        stats.put("pending", approvalMapper.selectCount(pendingWrapper));

        LambdaQueryWrapper<Approval> approvedWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) {
            approvedWrapper.eq(Approval::getCreatorId, creatorId);
        }
        approvedWrapper.eq(Approval::getStatus, "approved");
        stats.put("approved", approvalMapper.selectCount(approvedWrapper));

        LambdaQueryWrapper<Approval> rejectedWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) {
            rejectedWrapper.eq(Approval::getCreatorId, creatorId);
        }
        rejectedWrapper.eq(Approval::getStatus, "rejected");
        stats.put("rejected", approvalMapper.selectCount(rejectedWrapper));

        LambdaQueryWrapper<Approval> totalWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) {
            totalWrapper.eq(Approval::getCreatorId, creatorId);
        }
        stats.put("total", approvalMapper.selectCount(totalWrapper));
        return stats;
    }

    /**
     * 提交审批决策
     */
    @Transactional
    public Approval decide(String approvalNo, String decision, String comment,
                           String operatorId, String operatorName, String operatorIp) {
        LambdaQueryWrapper<Approval> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Approval::getApprovalNo, approvalNo);
        Approval approval = approvalMapper.selectOne(wrapper);

        if (approval == null) {
            throw new IllegalArgumentException("审批记录不存在: " + approvalNo);
        }
        if (!"pending".equals(approval.getStatus())) {
            throw new IllegalArgumentException("审批已处理，当前状态: " + approval.getStatus());
        }
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new IllegalArgumentException("无效的决策: " + decision + "，允许值: approved/rejected");
        }

        approval.setStatus(decision);
        approval.setDecision(decision);
        approval.setComment(comment);
        approval.setOperatorId(operatorId);
        approval.setOperatorName(operatorName);
        approval.setOperatorIp(operatorIp);
        approval.setDecidedAt(LocalDateTime.now());
        approval.setUpdatedAt(LocalDateTime.now());
        approvalMapper.updateById(approval);

        // 通知 momo-worker：通过 Redis String 回写审批结果
        // key: approval:{workstationId}:response:{requestId}  (String, TTL 120s)
        String requestId = approval.getRequestId();
        if (StringUtils.hasText(requestId)) {
            try {
                Long roleId = resolveRoleIdByTaskNo(approval.getTaskNo());
                String responseKey = dispatchConfig.getApprovalResponseKey(roleId, requestId);
                Map<String, String> response = new LinkedHashMap<>();
                response.put("request_id", requestId);
                response.put("status", decision);
                if ("approved".equals(decision)) {
                    response.put("approved_by", operatorName != null ? operatorName : "system");
                } else {
                    response.put("rejected_by", operatorName != null ? operatorName : "system");
                }
                response.put("comment", comment != null ? comment : "");
                response.put("responded_at", Instant.now().toString());
                String responseJson = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set(responseKey, responseJson, Duration.ofSeconds(120));
                log.info("审批结果已回写 Redis (String, TTL=120s): key={}, decision={}", responseKey, decision);
            } catch (Exception e) {
                log.error("审批结果回写 Redis 失败: requestId={}", requestId, e);
            }
        }

        // 向任务日志 Stream 写入 USER_CONFIRM_RESOLVED 事件，通知前端审批已处理
        publishResolvedEvent(approval);

        // 兼容旧模式：通过 Redis Pub/Sub 发送审批结果
        if ("approved".equals(decision) && StringUtils.hasText(approval.getTaskNo())) {
            String channel = "approval:" + approval.getTaskNo();
            redisTemplate.convertAndSend(channel, "approved:" + approvalNo);
            log.info("审批通过，已通知 Agent (Pub/Sub): taskNo={}, approvalNo={}", approval.getTaskNo(), approvalNo);
        }

        log.info("审批决策完成: approvalNo={}, decision={}, operator={}, operatorIp={}",
                approvalNo, decision, operatorName, operatorIp);
        return approval;
    }

    /**
     * 创建审批请求（由 Agent/Worker 触发）
     */
    @Transactional
    public Approval createApproval(String taskNo, String taskTitle, String action,
                                    String description, String riskLevel,
                                    String creatorId, String creatorName) {
        String approvalNo = "AUTH-" + idGenerator.nextId();

        Approval approval = new Approval();
        approval.setApprovalNo(approvalNo);
        approval.setTaskNo(taskNo);
        approval.setTaskTitle(taskTitle);
        approval.setAction(action);
        approval.setDescription(description);
        approval.setRiskLevel(riskLevel != null ? riskLevel : "medium");
        approval.setStatus("pending");
        approval.setCreatorId(creatorId);
        approval.setCreatorName(creatorName);
        // 默认 30 分钟过期
        approval.setExpiredAt(LocalDateTime.now().plusMinutes(30));
        approval.setCreatedAt(LocalDateTime.now());
        approval.setUpdatedAt(LocalDateTime.now());
        approval.setIsDeleted(0);
        approvalMapper.insert(approval);

        log.info("审批请求创建: approvalNo={}, taskNo={}, action={}, riskLevel={}",
                approvalNo, taskNo, action, riskLevel);

        return approval;
    }

    /**
     * 更新审批记录的 requestId（momo-worker 审批请求 ID）
     */
    public void updateRequestId(String approvalNo, String requestId) {
        LambdaQueryWrapper<Approval> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Approval::getApprovalNo, approvalNo);
        Approval approval = approvalMapper.selectOne(wrapper);
        if (approval != null) {
            approval.setRequestId(requestId);
            approvalMapper.updateById(approval);
        }
    }

    /**
     * 转换为响应格式
     */
    public Map<String, Object> toResponse(Approval approval) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", approval.getApprovalNo());
        map.put("taskNo", approval.getTaskNo());
        map.put("taskTitle", approval.getTaskTitle());
        map.put("action", approval.getAction());
        map.put("description", approval.getDescription());
        map.put("riskLevel", approval.getRiskLevel());
        map.put("status", approval.getStatus());
        map.put("decision", approval.getDecision());
        map.put("comment", approval.getComment());
        map.put("operatorName", approval.getOperatorName());
        map.put("expiredAt", approval.getExpiredAt());
        map.put("decidedAt", approval.getDecidedAt());
        map.put("creatorName", approval.getCreatorName());
        map.put("createdAt", approval.getCreatedAt());
        return map;
    }

    public List<Map<String, Object>> toResponseList(List<Approval> approvals) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Approval approval : approvals) {
            list.add(toResponse(approval));
        }
        return list;
    }

    /**
     * 向任务日志 Stream 写入 USER_CONFIRM_RESOLVED 事件
     * 让 WebSocket 能推送审批结果到前端任务执行面板
     */
    private void publishResolvedEvent(Approval approval) {
        String taskNo = approval.getTaskNo();
        if (taskNo == null || taskNo.isEmpty()) return;

        try {
            Long roleId = resolveRoleIdByTaskNo(taskNo);
            String streamKey = dispatchConfig.getLogStreamKey(roleId, taskNo);

            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("approval_no", approval.getApprovalNo());
            eventData.put("request_id", approval.getRequestId());
            eventData.put("task_id", taskNo);
            eventData.put("decision", approval.getDecision());
            eventData.put("operator", approval.getOperatorName());
            eventData.put("comment", approval.getComment());

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event_type", "USER_CONFIRM_RESOLVED");
            fields.put("timestamp", Instant.now().toString());
            fields.put("session_id", "backend");
            fields.put("data", objectMapper.writeValueAsString(eventData));

            redisTemplate.opsForStream().add(
                    StreamRecords.string(fields).withStreamKey(streamKey));

            log.info("USER_CONFIRM_RESOLVED 事件已写入 Stream: key={}, decision={}",
                    streamKey, approval.getDecision());
        } catch (Exception e) {
            log.error("写入 USER_CONFIRM_RESOLVED 事件失败: {}", e.getMessage());
        }
    }

    private Long resolveRoleIdByTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            return null;
        }
        try {
            return taskService.getTaskByNo(taskNo).getRoleId();
        } catch (Exception e) {
            log.debug("审批链路解析任务 roleId 失败，回退默认 workstation: taskNo={}", taskNo);
            return null;
        }
    }
}

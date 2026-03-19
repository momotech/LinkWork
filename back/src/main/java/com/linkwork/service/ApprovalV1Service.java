package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.common.SnowflakeIdGenerator;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.ApprovalMapper;
import com.linkwork.model.entity.LinkworkApproval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalV1Service {

    private final ApprovalMapper approvalMapper;
    private final StringRedisTemplate redisTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final DispatchConfig dispatchConfig;
    private final ObjectMapper objectMapper;
    private final TaskV1Service taskService;

    public Page<LinkworkApproval> listApprovals(String status, Integer page, Integer pageSize, String creatorId) {
        LambdaQueryWrapper<LinkworkApproval> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) w.eq(LinkworkApproval::getCreatorId, creatorId);
        if (StringUtils.hasText(status) && !"all".equalsIgnoreCase(status)) {
            w.eq(LinkworkApproval::getStatus, status);
        }
        w.orderByDesc(LinkworkApproval::getCreatedAt);
        return approvalMapper.selectPage(new Page<>(page, pageSize), w);
    }

    public Map<String, Long> getStats(String creatorId) {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (String s : List.of("pending", "approved", "rejected")) {
            LambdaQueryWrapper<LinkworkApproval> w = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(creatorId)) w.eq(LinkworkApproval::getCreatorId, creatorId);
            w.eq(LinkworkApproval::getStatus, s);
            stats.put(s, approvalMapper.selectCount(w));
        }
        LambdaQueryWrapper<LinkworkApproval> totalW = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId)) totalW.eq(LinkworkApproval::getCreatorId, creatorId);
        stats.put("total", approvalMapper.selectCount(totalW));
        return stats;
    }

    @Transactional
    public LinkworkApproval decide(String approvalNo, String decision, String comment,
                                    String operatorId, String operatorName, String operatorIp) {
        LambdaQueryWrapper<LinkworkApproval> w = new LambdaQueryWrapper<>();
        w.eq(LinkworkApproval::getApprovalNo, approvalNo);
        LinkworkApproval approval = approvalMapper.selectOne(w);
        if (approval == null) throw new IllegalArgumentException("Approval not found: " + approvalNo);
        if (!"pending".equals(approval.getStatus())) {
            throw new IllegalArgumentException("Approval already processed: " + approval.getStatus());
        }
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new IllegalArgumentException("Invalid decision: " + decision);
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

        if (StringUtils.hasText(approval.getRequestId())) {
            try {
                Long wsId = resolveWorkstationIdByTaskNo(approval.getTaskNo());
                String responseKey = dispatchConfig.getApprovalResponseKey(wsId, approval.getRequestId());
                Map<String, String> resp = new LinkedHashMap<>();
                resp.put("request_id", approval.getRequestId());
                resp.put("status", decision);
                resp.put("operator", operatorName != null ? operatorName : "system");
                resp.put("comment", comment != null ? comment : "");
                resp.put("responded_at", Instant.now().toString());
                redisTemplate.opsForValue().set(responseKey, objectMapper.writeValueAsString(resp), Duration.ofSeconds(120));
            } catch (Exception e) {
                log.error("Failed to write approval response to Redis: requestId={}", approval.getRequestId(), e);
            }
        }

        publishResolvedEvent(approval);

        if ("approved".equals(decision) && StringUtils.hasText(approval.getTaskNo())) {
            redisTemplate.convertAndSend("approval:" + approval.getTaskNo(), "approved:" + approvalNo);
        }
        log.info("Approval decided: approvalNo={}, decision={}, operator={}", approvalNo, decision, operatorName);
        return approval;
    }

    @Transactional
    public LinkworkApproval createApproval(String taskNo, String taskTitle, String action,
                                            String description, String riskLevel,
                                            String creatorId, String creatorName) {
        String approvalNo = idGenerator.nextApprovalNo();
        LinkworkApproval approval = new LinkworkApproval();
        approval.setApprovalNo(approvalNo);
        approval.setTaskNo(taskNo);
        approval.setTaskTitle(taskTitle);
        approval.setAction(action);
        approval.setDescription(description);
        approval.setRiskLevel(riskLevel != null ? riskLevel : "medium");
        approval.setStatus("pending");
        approval.setCreatorId(creatorId);
        approval.setCreatorName(creatorName);
        approval.setExpiredAt(LocalDateTime.now().plusMinutes(30));
        approval.setCreatedAt(LocalDateTime.now());
        approval.setUpdatedAt(LocalDateTime.now());
        approval.setIsDeleted(0);
        approvalMapper.insert(approval);
        log.info("Approval created: approvalNo={}, taskNo={}, action={}", approvalNo, taskNo, action);
        return approval;
    }

    public void updateRequestId(String approvalNo, String requestId) {
        LambdaQueryWrapper<LinkworkApproval> w = new LambdaQueryWrapper<>();
        w.eq(LinkworkApproval::getApprovalNo, approvalNo);
        LinkworkApproval approval = approvalMapper.selectOne(w);
        if (approval != null) {
            approval.setRequestId(requestId);
            approvalMapper.updateById(approval);
        }
    }

    public Map<String, Object> toResponse(LinkworkApproval a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getApprovalNo());
        map.put("taskNo", a.getTaskNo());
        map.put("taskTitle", a.getTaskTitle());
        map.put("action", a.getAction());
        map.put("description", a.getDescription());
        map.put("riskLevel", a.getRiskLevel());
        map.put("status", a.getStatus());
        map.put("decision", a.getDecision());
        map.put("comment", a.getComment());
        map.put("operatorName", a.getOperatorName());
        map.put("expiredAt", a.getExpiredAt());
        map.put("decidedAt", a.getDecidedAt());
        map.put("creatorName", a.getCreatorName());
        map.put("createdAt", a.getCreatedAt());
        return map;
    }

    public List<Map<String, Object>> toResponseList(List<LinkworkApproval> list) {
        return list.stream().map(this::toResponse).toList();
    }

    private void publishResolvedEvent(LinkworkApproval approval) {
        String taskNo = approval.getTaskNo();
        if (!StringUtils.hasText(taskNo)) return;
        try {
            Long wsId = resolveWorkstationIdByTaskNo(taskNo);
            String streamKey = dispatchConfig.getLogStreamKey(wsId, taskNo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approval_no", approval.getApprovalNo());
            data.put("request_id", approval.getRequestId());
            data.put("task_id", taskNo);
            data.put("decision", approval.getDecision());
            data.put("operator", approval.getOperatorName());
            data.put("comment", approval.getComment());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event_type", "USER_CONFIRM_RESOLVED");
            fields.put("timestamp", Instant.now().toString());
            fields.put("session_id", "backend");
            fields.put("data", objectMapper.writeValueAsString(data));
            redisTemplate.opsForStream().add(StreamRecords.string(fields).withStreamKey(streamKey));
        } catch (Exception e) {
            log.error("Failed to publish approval resolved event: {}", e.getMessage());
        }
    }

    private Long resolveWorkstationIdByTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) return null;
        try {
            return taskService.getTaskByNo(taskNo).getWorkstationId();
        } catch (Exception e) {
            return null;
        }
    }
}

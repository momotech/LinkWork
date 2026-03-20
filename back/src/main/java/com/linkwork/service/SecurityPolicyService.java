package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.mapper.SecurityPolicyMapper;
import com.linkwork.model.entity.SecurityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 安全策略服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityPolicyService {

    private final SecurityPolicyMapper policyMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取所有安全策略
     */
    public List<Map<String, Object>> listPolicies() {
        LambdaQueryWrapper<SecurityPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SecurityPolicy::getType)  // system 排前面
               .orderByDesc(SecurityPolicy::getCreatedAt);
        List<SecurityPolicy> policies = policyMapper.selectList(wrapper);
        return policies.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 获取单个策略
     */
    public Map<String, Object> getPolicy(Long id) {
        SecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        return toResponse(policy);
    }

    /**
     * 创建自定义策略
     */
    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> request, String creatorId, String creatorName) {
        SecurityPolicy policy = new SecurityPolicy();
        policy.setName((String) request.get("name"));
        policy.setDescription((String) request.get("description"));
        policy.setType("custom");
        policy.setEnabled(true);
        policy.setCreatorId(creatorId);
        policy.setCreatorName(creatorName);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setIsDeleted(0);

        // 序列化规则
        Object rules = request.get("rules");
        if (rules != null) {
            try {
                policy.setRulesJson(objectMapper.writeValueAsString(rules));
            } catch (JsonProcessingException e) {
                log.error("序列化策略规则失败", e);
            }
        } else {
            policy.setRulesJson("[]");
        }

        policyMapper.insert(policy);
        log.info("安全策略创建成功: id={}, name={}", policy.getId(), policy.getName());
        return toResponse(policy);
    }

    /**
     * 更新策略
     */
    @Transactional
    public Map<String, Object> updatePolicy(Long id, Map<String, Object> request) {
        SecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        if ("system".equals(policy.getType())) {
            throw new IllegalArgumentException("系统策略不可编辑");
        }

        if (request.containsKey("name")) {
            policy.setName((String) request.get("name"));
        }
        if (request.containsKey("description")) {
            policy.setDescription((String) request.get("description"));
        }
        if (request.containsKey("enabled")) {
            policy.setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("rules")) {
            try {
                policy.setRulesJson(objectMapper.writeValueAsString(request.get("rules")));
            } catch (JsonProcessingException e) {
                log.error("序列化策略规则失败", e);
            }
        }

        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        log.info("安全策略更新成功: id={}, name={}", policy.getId(), policy.getName());
        return toResponse(policy);
    }

    /**
     * 切换策略启用/禁用
     */
    @Transactional
    public Map<String, Object> togglePolicy(Long id) {
        SecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        if ("system".equals(policy.getType()) && Boolean.TRUE.equals(policy.getEnabled())) {
            throw new IllegalArgumentException("系统策略不能禁用");
        }

        policy.setEnabled(!policy.getEnabled());
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        log.info("安全策略状态切换: id={}, enabled={}", policy.getId(), policy.getEnabled());
        return toResponse(policy);
    }

    /**
     * 删除策略
     */
    @Transactional
    public void deletePolicy(Long id) {
        SecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        if ("system".equals(policy.getType())) {
            throw new IllegalArgumentException("系统策略不可删除");
        }
        policyMapper.deleteById(id);
        log.info("安全策略删除: id={}, name={}", id, policy.getName());
    }

    /**
     * 转换为响应格式
     */
    private Map<String, Object> toResponse(SecurityPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", policy.getId());
        map.put("name", policy.getName());
        map.put("description", policy.getDescription());
        map.put("type", policy.getType());
        map.put("enabled", policy.getEnabled());
        map.put("creatorName", policy.getCreatorName());
        map.put("createdAt", policy.getCreatedAt());
        map.put("updatedAt", policy.getUpdatedAt());

        // 解析规则 JSON
        if (policy.getRulesJson() != null) {
            try {
                List<Map<String, Object>> rules = objectMapper.readValue(
                    policy.getRulesJson(), new TypeReference<List<Map<String, Object>>>() {});
                map.put("rules", rules);
            } catch (JsonProcessingException e) {
                log.error("解析策略规则失败: id={}", policy.getId(), e);
                map.put("rules", List.of());
            }
        } else {
            map.put("rules", List.of());
        }

        return map;
    }
}

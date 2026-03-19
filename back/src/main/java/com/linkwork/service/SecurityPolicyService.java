package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.mapper.SecurityPolicyMapper;
import com.linkwork.model.entity.LinkworkSecurityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityPolicyService {

    private final SecurityPolicyMapper policyMapper;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> listPolicies() {
        LambdaQueryWrapper<LinkworkSecurityPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(LinkworkSecurityPolicy::getType)
                .orderByDesc(LinkworkSecurityPolicy::getCreatedAt);
        return policyMapper.selectList(wrapper).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public Map<String, Object> getPolicy(Long id) {
        LinkworkSecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new IllegalArgumentException("Policy not found: " + id);
        return toResponse(policy);
    }

    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> request, String creatorId, String creatorName) {
        LinkworkSecurityPolicy policy = new LinkworkSecurityPolicy();
        policy.setName((String) request.get("name"));
        policy.setDescription((String) request.get("description"));
        policy.setType("custom");
        policy.setEnabled(true);
        policy.setCreatorId(creatorId);
        policy.setCreatorName(creatorName);
        policy.setIsDeleted(0);
        Object rules = request.get("rules");
        if (rules != null) {
            try { policy.setRulesJson(objectMapper.writeValueAsString(rules)); }
            catch (JsonProcessingException e) { log.error("Failed to serialize policy rules", e); }
        } else {
            policy.setRulesJson("[]");
        }
        policyMapper.insert(policy);
        return toResponse(policy);
    }

    @Transactional
    public Map<String, Object> updatePolicy(Long id, Map<String, Object> request) {
        LinkworkSecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new IllegalArgumentException("Policy not found: " + id);
        if ("system".equals(policy.getType())) throw new IllegalArgumentException("System policy cannot be edited");
        if (request.containsKey("name")) policy.setName((String) request.get("name"));
        if (request.containsKey("description")) policy.setDescription((String) request.get("description"));
        if (request.containsKey("enabled")) policy.setEnabled((Boolean) request.get("enabled"));
        if (request.containsKey("rules")) {
            try { policy.setRulesJson(objectMapper.writeValueAsString(request.get("rules"))); }
            catch (JsonProcessingException e) { log.error("Failed to serialize policy rules", e); }
        }
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        return toResponse(policy);
    }

    @Transactional
    public Map<String, Object> togglePolicy(Long id) {
        LinkworkSecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new IllegalArgumentException("Policy not found: " + id);
        if ("system".equals(policy.getType()) && Boolean.TRUE.equals(policy.getEnabled())) {
            throw new IllegalArgumentException("System policy cannot be disabled");
        }
        policy.setEnabled(!policy.getEnabled());
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        return toResponse(policy);
    }

    @Transactional
    public void deletePolicy(Long id) {
        LinkworkSecurityPolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new IllegalArgumentException("Policy not found: " + id);
        if ("system".equals(policy.getType())) throw new IllegalArgumentException("System policy cannot be deleted");
        policyMapper.deleteById(id);
    }

    private Map<String, Object> toResponse(LinkworkSecurityPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", policy.getId());
        map.put("name", policy.getName());
        map.put("description", policy.getDescription());
        map.put("type", policy.getType());
        map.put("enabled", policy.getEnabled());
        map.put("creatorName", policy.getCreatorName());
        map.put("createdAt", policy.getCreatedAt());
        map.put("updatedAt", policy.getUpdatedAt());
        if (policy.getRulesJson() != null) {
            try {
                map.put("rules", objectMapper.readValue(policy.getRulesJson(),
                        new TypeReference<List<Map<String, Object>>>() {}));
            } catch (JsonProcessingException e) {
                map.put("rules", List.of());
            }
        } else {
            map.put("rules", List.of());
        }
        return map;
    }
}

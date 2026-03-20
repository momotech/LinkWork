package com.linkwork.service;

import com.linkwork.config.EnvConfig;
import com.linkwork.model.entity.BuildRecordEntity;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.enums.PodMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 运行模式解析服务
 */
@Service
@RequiredArgsConstructor
public class RuntimeModeService {

    private final BuildRecordService buildRecordService;
    private final RoleService roleService;
    private final EnvConfig envConfig;

    /**
     * 按岗位 ID 解析运行模式视图。
     */
    public RuntimeSnapshot resolveForRole(Long roleId) {
        if (roleId == null) {
            return resolveDefault();
        }

        RoleEntity role = roleService.getById(roleId);
        if (role == null) {
            return resolveDefault();
        }
        return resolveForRole(role);
    }

    /**
     * 按岗位实体解析运行模式视图。
     */
    public RuntimeSnapshot resolveForRole(RoleEntity role) {
        if (role == null || role.getId() == null) {
            throw new IllegalArgumentException("岗位信息不完整，无法解析运行模式");
        }

        String context = "roleId=" + role.getId();
        RoleEntity.RoleConfig roleConfig = role.getConfigJson();

        PodMode configPodMode = null;
        String configRunnerImage = null;
        if (roleConfig != null) {
            configPodMode = parsePodMode(roleConfig.getRuntimeMode(), context + ", configJson.runtimeMode");
            configRunnerImage = asText(roleConfig.getRunnerImage());
        }

        BuildRecordEntity latestRecord = buildRecordService.getLatestByRoleId(role.getId());
        PodMode snapshotPodMode = null;
        String snapshotRunnerImage = null;
        if (latestRecord != null && latestRecord.getConfigSnapshot() != null) {
            Map<String, Object> snapshot = latestRecord.getConfigSnapshot();
            Object runtimeRaw = snapshot.get("runtimeMode") != null
                    ? snapshot.get("runtimeMode")
                    : snapshot.get("podMode");
            snapshotPodMode = parsePodMode(runtimeRaw, context + ", buildNo=" + latestRecord.getBuildNo());

            snapshotRunnerImage = asText(snapshot.get("runnerImage"));
            if (!StringUtils.hasText(snapshotRunnerImage)) {
                snapshotRunnerImage = asText(snapshot.get("runnerBaseImage"));
            }
        }

        PodMode podMode = configPodMode != null ? configPodMode : snapshotPodMode;
        if (podMode == null) {
            podMode = resolveDefaultPodMode(context);
        }

        String runnerImage = StringUtils.hasText(configRunnerImage) ? configRunnerImage : snapshotRunnerImage;
        if (podMode == PodMode.SIDECAR && !StringUtils.hasText(runnerImage)) {
            runnerImage = resolveDefaultRunnerImage(role);
        }

        return resolveFromPodMode(podMode, runnerImage, context);
    }

    /**
     * 使用系统默认配置解析运行模式（用于历史数据兼容）。
     */
    public RuntimeSnapshot resolveDefault() {
        PodMode podMode = resolveDefaultPodMode("default");
        String runnerImage = podMode == PodMode.SIDECAR ? resolveDefaultRunnerImage() : null;
        return resolveFromPodMode(podMode, runnerImage, "default");
    }

    /**
     * 按原始模式值解析运行模式视图。
     */
    public RuntimeSnapshot resolveFromRaw(String runtimeMode, String runnerImage, String context) {
        PodMode podMode = parsePodMode(runtimeMode, context);
        if (podMode == null) {
            throw new IllegalArgumentException("运行模式缺失: " + context);
        }
        return resolveFromPodMode(podMode, runnerImage, context);
    }

    /**
     * 按 PodMode 解析运行模式视图。
     */
    public RuntimeSnapshot resolveFromPodMode(PodMode podMode, String runnerImage, String context) {
        if (podMode == null) {
            throw new IllegalArgumentException("运行模式缺失: " + context);
        }

        if (podMode == PodMode.SIDECAR) {
            if (!StringUtils.hasText(runnerImage)) {
                throw new IllegalStateException("SIDECAR 模式缺少 runnerImage: " + context);
            }
            return new RuntimeSnapshot(PodMode.SIDECAR.name(), "ssh", runnerImage);
        }

        return new RuntimeSnapshot(PodMode.ALONE.name(), "local", null);
    }

    private PodMode resolveDefaultPodMode(String context) {
        if (envConfig.getPodModeRules() == null || envConfig.getPodModeRules().getDefaultMode() == null) {
            throw new IllegalStateException("未配置默认运行模式: " + context);
        }
        return envConfig.getPodModeRules().getDefaultMode();
    }

    private String resolveDefaultRunnerImage(RoleEntity role) {
        String defaultRunnerImage = resolveDefaultRunnerImage();
        if (StringUtils.hasText(defaultRunnerImage)) {
            return defaultRunnerImage;
        }
        if (role != null && StringUtils.hasText(role.getImage())) {
            return role.getImage();
        }
        return null;
    }

    private String resolveDefaultRunnerImage() {
        if (envConfig.getImages() != null && StringUtils.hasText(envConfig.getImages().getRunner())) {
            return envConfig.getImages().getRunner();
        }
        return null;
    }

    private PodMode parsePodMode(Object rawMode, String context) {
        if (rawMode == null) {
            return null;
        }

        if (rawMode instanceof PodMode podMode) {
            return podMode;
        }

        String mode = String.valueOf(rawMode).trim();
        if (!StringUtils.hasText(mode)) {
            return null;
        }

        try {
            return PodMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("非法运行模式: " + mode + ", " + context);
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    @Data
    @AllArgsConstructor
    public static class RuntimeSnapshot {
        private String runtimeMode;
        private String zzMode;
        private String runnerImage;
    }
}

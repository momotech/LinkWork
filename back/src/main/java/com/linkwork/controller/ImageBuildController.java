package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.service.ImageBuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 镜像构建 Controller
 *
 * 提供构建相关的配置查询接口
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/build", "/api/v1/image-build"})
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ImageBuildController {

    private final ImageBuildService imageBuildService;

    /**
     * 获取可选的构建基础镜像列表
     *
     * @return 基础镜像列表
     */
    @GetMapping("/base-images")
    public ApiResponse<List<BaseImageInfo>> listBaseImages() {
        List<BaseImageInfo> images = new ArrayList<>();
        images.add(new BaseImageInfo(
                "10.30.107.146/robot/rockylinux9-agent:v1.3",
                "Rocky Linux 9 Agent v1.3",
                "开发机默认基础镜像（优先使用本地缓存）",
                true
        ));
        images.add(new BaseImageInfo(
                "rockylinux/rockylinux:9.6",
                "Rocky Linux 9.6",
                "公共回退基础镜像（仅当 v1.3 不可用时使用）",
                false
        ));
        images.add(new BaseImageInfo(
                "10.30.107.146/robot/rockylinux9-agent@sha256:b49d75f52f6b3c55bbf90427f0df0e97bc8e3f3e03727721cafc2c9d775b8975",
                "Rocky Linux 9 Agent",
                "内网固定 digest 基础镜像（需内网仓库可达）",
                false
        ));
        return ApiResponse.success(images);
    }

    /**
     * 手动触发一次本地镜像运维动作：
     * - 清理过期 service-*-agent 本地镜像
     * - 可选对 Kind 节点执行 crictl prune
     */
    @PostMapping("/ops/local-image-maintenance")
    public ApiResponse<Map<String, Object>> runLocalImageMaintenance() {
        return ApiResponse.success(imageBuildService.runLocalImageMaintenance("manual"));
    }

    /**
     * 基础镜像信息 DTO
     *
     * @param id          镜像标识（唯一）
     * @param name        镜像显示名称
     * @param description 镜像描述
     * @param isDefault   是否为默认选项
     */
    public record BaseImageInfo(
            String id,
            String name,
            String description,
            boolean isDefault
    ) {}
}

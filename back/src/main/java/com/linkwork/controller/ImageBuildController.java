package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像构建 Controller
 *
 * 提供构建相关的配置查询接口
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/build", "/api/v1/image-build"})
@CrossOrigin(origins = "*")
public class ImageBuildController {

    /**
     * 获取可选的构建基础镜像列表
     *
     * @return 基础镜像列表
     */
    @GetMapping("/base-images")
    public ApiResponse<List<BaseImageInfo>> listBaseImages() {
        List<BaseImageInfo> images = new ArrayList<>();
        images.add(new BaseImageInfo(
                "10.30.107.146/robot/rockylinux9-agent@sha256:b49d75f52f6b3c55bbf90427f0df0e97bc8e3f3e03727721cafc2c9d775b8975",
                "Rocky Linux 9 Agent",
                "基于 Rocky Linux 9 的 Agent 基础镜像，固定 digest 保证构建环境一致性",
                true
        ));
        return ApiResponse.success(images);
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

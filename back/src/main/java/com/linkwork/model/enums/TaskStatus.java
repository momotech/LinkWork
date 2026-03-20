package com.linkwork.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 任务状态枚举
 */
@Getter
public enum TaskStatus {
    PENDING("pending", "等待执行"),
    RUNNING("running", "执行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "执行失败"),
    ABORTED("aborted", "已终止"),
    PENDING_AUTH("pending_auth", "等待人工授权");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    TaskStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}

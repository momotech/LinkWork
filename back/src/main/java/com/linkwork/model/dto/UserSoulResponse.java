package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserSoulResponse {

    private String content;

    private String presetId;

    private Long version;

    private LocalDateTime updatedAt;
}

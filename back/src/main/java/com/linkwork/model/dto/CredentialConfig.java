package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 凭证配置
 */
@Data
@Builder
public class CredentialConfig {
    private String credentialId;
    private String key;
    private String type;
    private String mountPath;
    private String envName;
}

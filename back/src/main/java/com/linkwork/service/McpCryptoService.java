package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * AES-256-GCM 加解密服务。
 * <p>
 * 存储格式: base64(nonce[12] + ciphertext + tag[16])
 * <p>
 * 密钥来源优先级:
 * 1. 环境变量 MCP_ENCRYPTION_KEY
 * 2. 配置 robot.mcp.encryption-key
 * <p>
 * 密钥必须为 32 字节（hex 64 字符 或 base64 44 字符）。
 * 当密钥未配置时，加解密方法退化为明文透传（兼容开发环境）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final ObjectMapper objectMapper;

    @Value("${robot.mcp.encryption-key:}")
    private String configKey;

    private SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void init() {
        String envKey = System.getenv("MCP_ENCRYPTION_KEY");
        String rawKey = StringUtils.hasText(envKey) ? envKey : configKey;

        if (!StringUtils.hasText(rawKey)) {
            log.warn("MCP_ENCRYPTION_KEY not configured — encryption is DISABLED, data stored as plaintext");
            return;
        }

        byte[] keyBytes = decodeKey(rawKey.trim());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "MCP_ENCRYPTION_KEY must be 32 bytes (got " + keyBytes.length + "). "
                    + "Use 64 hex chars or 44 base64 chars.");
        }
        secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("MCP encryption initialized (AES-256-GCM)");
    }

    public boolean isEnabled() {
        return secretKey != null;
    }

    /**
     * 加密字符串，返回 base64 编码的 nonce+ciphertext+tag
     */
    public String encrypt(String plaintext) {
        if (!isEnabled() || !StringUtils.hasText(plaintext)) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(nonce.length + ciphertext.length);
            buffer.put(nonce);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    /**
     * 解密 base64 编码的密文
     */
    public String decrypt(String cipherBase64) {
        if (!isEnabled() || !StringUtils.hasText(cipherBase64)) {
            return cipherBase64;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherBase64);
            if (decoded.length < GCM_NONCE_LENGTH) {
                log.warn("Ciphertext too short, returning as-is (possibly plaintext)");
                return cipherBase64;
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.debug("Not base64, treating as plaintext: {}", e.getMessage());
            return cipherBase64;
        } catch (Exception e) {
            log.warn("AES-GCM decryption failed, returning as-is (possibly plaintext data): {}", e.getMessage());
            return cipherBase64;
        }
    }

    /**
     * 加密 Map<String, String> 为加密后的 JSON 字符串
     */
    public String encryptMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(map);
            return encrypt(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize map for encryption", e);
        }
    }

    /**
     * 解密 JSON 字符串为 Map<String, String>
     */
    public Map<String, String> decryptMap(String encrypted) {
        if (!StringUtils.hasText(encrypted)) {
            return null;
        }
        String json = decrypt(encrypted);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse decrypted map, returning null: {}", e.getMessage());
            return null;
        }
    }

    private byte[] decodeKey(String raw) {
        if (raw.matches("[0-9a-fA-F]+") && raw.length() == 64) {
            return hexToBytes(raw);
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}

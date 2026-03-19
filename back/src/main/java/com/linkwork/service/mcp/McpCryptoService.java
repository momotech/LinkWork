package com.linkwork.service.mcp;

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

@Service
public class McpCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey secretKey;

    public McpCryptoService(@Value("${linkwork.agent.mcp.security.encryption-key:}") String configKey) {
        String envKey = System.getenv("MCP_ENCRYPTION_KEY");
        String rawKey = StringUtils.hasText(envKey) ? envKey : configKey;
        if (!StringUtils.hasText(rawKey)) {
            return;
        }
        byte[] keyBytes = decodeKey(rawKey.trim());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "MCP_ENCRYPTION_KEY must be 32 bytes (got " + keyBytes.length + "). Use 64 hex chars or 44 base64 chars."
            );
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public boolean isEnabled() {
        return secretKey != null;
    }

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

    public String decrypt(String cipherBase64) {
        if (!isEnabled() || !StringUtils.hasText(cipherBase64)) {
            return cipherBase64;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherBase64);
            if (decoded.length < GCM_NONCE_LENGTH) {
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
        } catch (Exception ex) {
            return cipherBase64;
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

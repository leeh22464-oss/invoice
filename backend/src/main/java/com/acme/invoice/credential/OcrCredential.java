package com.acme.invoice.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ocr_credentials")
public class OcrCredential {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "api_key_iv", nullable = false)
    private byte[] apiKeyIv;

    @Column(name = "api_key_ciphertext", nullable = false)
    private byte[] apiKeyCiphertext;

    @Column(name = "secret_key_iv", nullable = false)
    private byte[] secretKeyIv;

    @Column(name = "secret_key_ciphertext", nullable = false)
    private byte[] secretKeyCiphertext;

    @Column(name = "api_key_mask", nullable = false, length = 32)
    private String apiKeyMask;

    @Column(name = "validation_status", nullable = false, length = 20)
    private String validationStatus;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OcrCredential() {
    }

    public OcrCredential(
            UUID id,
            UUID userId,
            byte[] apiKeyIv,
            byte[] apiKeyCiphertext,
            byte[] secretKeyIv,
            byte[] secretKeyCiphertext,
            String apiKeyMask,
            String validationStatus,
            Instant lastValidatedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.apiKeyIv = apiKeyIv;
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.secretKeyIv = secretKeyIv;
        this.secretKeyCiphertext = secretKeyCiphertext;
        this.apiKeyMask = apiKeyMask;
        this.validationStatus = validationStatus;
        this.lastValidatedAt = lastValidatedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void replace(
            EncryptedSecret apiKey,
            EncryptedSecret secretKey,
            String mask,
            Instant validatedAt) {
        this.apiKeyIv = apiKey.iv();
        this.apiKeyCiphertext = apiKey.ciphertext();
        this.secretKeyIv = secretKey.iv();
        this.secretKeyCiphertext = secretKey.ciphertext();
        this.apiKeyMask = mask;
        this.validationStatus = "VALID";
        this.lastValidatedAt = validatedAt;
        this.updatedAt = validatedAt;
    }

    public void recordValidation(boolean valid, Instant validatedAt) {
        this.validationStatus = valid ? "VALID" : "INVALID";
        this.lastValidatedAt = validatedAt;
        this.updatedAt = validatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getApiKeyIv() {
        return apiKeyIv;
    }

    public byte[] getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    public byte[] getSecretKeyIv() {
        return secretKeyIv;
    }

    public byte[] getSecretKeyCiphertext() {
        return secretKeyCiphertext;
    }

    public String getApiKeyMask() {
        return apiKeyMask;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.acme.invoice.credential;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("!smoke")
public class OcrCredentialPersistence {

    private final OcrCredentialRepository repository;
    private final JdbcTemplate jdbc;

    public OcrCredentialPersistence(OcrCredentialRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<OcrCredential> findByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    @Transactional
    public OcrCredential upsert(
            UUID userId,
            EncryptedSecret apiKey,
            EncryptedSecret secretKey,
            String apiKeyMask,
            Instant validatedAt) {
        jdbc.update("""
                        insert into ocr_credentials(
                            id, user_id, api_key_iv, api_key_ciphertext,
                            secret_key_iv, secret_key_ciphertext, api_key_mask,
                            validation_status, last_validated_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'VALID', ?, ?, ?)
                        on conflict (user_id) do update set
                            api_key_iv = excluded.api_key_iv,
                            api_key_ciphertext = excluded.api_key_ciphertext,
                            secret_key_iv = excluded.secret_key_iv,
                            secret_key_ciphertext = excluded.secret_key_ciphertext,
                            api_key_mask = excluded.api_key_mask,
                            validation_status = excluded.validation_status,
                            last_validated_at = excluded.last_validated_at,
                            updated_at = excluded.updated_at
                        """,
                UUID.randomUUID(),
                userId,
                apiKey.iv(),
                apiKey.ciphertext(),
                secretKey.iv(),
                secretKey.ciphertext(),
                apiKeyMask,
                Timestamp.from(validatedAt),
                Timestamp.from(validatedAt),
                Timestamp.from(validatedAt));
        return repository.findByUserId(userId).orElseThrow();
    }

    @Transactional
    public OcrCredential recordValidation(UUID userId, boolean valid, Instant validatedAt) {
        jdbc.update("""
                        update ocr_credentials
                        set validation_status = ?, last_validated_at = ?, updated_at = ?
                        where user_id = ?
                        """,
                valid ? "VALID" : "INVALID",
                Timestamp.from(validatedAt),
                Timestamp.from(validatedAt),
                userId);
        return repository.findByUserId(userId).orElseThrow();
    }

    @Transactional
    public void deleteByUserId(UUID userId) {
        repository.deleteByUserId(userId);
    }
}

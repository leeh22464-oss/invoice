package com.acme.invoice.ocr;

import com.acme.invoice.credential.OcrCredentialService;
import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
class OcrProcessingServiceTransactionTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("ocr-tx-test-").toString();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "0123456789abcdef0123456789abcdef");
        registry.add("app.jwt.secure-cookie", () -> "false");
        registry.add("app.credential.master-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.storage.root", () -> STORAGE_ROOT);
    }

    @Autowired
    OcrProcessingService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OcrCredentialService credentialService;

    @MockitoBean
    FileStorage storage;

    @MockitoBean
    OcrProvider ocrProvider;

    @BeforeEach
    void clean() {
        jdbc.update("delete from normalized_invoices");
        jdbc.update("delete from ocr_results");
        jdbc.update("delete from invoice_files");
        jdbc.update("delete from import_batches");
        jdbc.update("delete from users");
    }

    @Test
    void processFileFailurePersistsFailedWithoutChangingSibling() {
        UUID userId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID failingId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "ocr-tx@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                now);
        jdbc.update("""
                        insert into import_batches(
                            id, user_id, batch_number, status, total_files, created_at, updated_at)
                        values (?, ?, 'B-OCR-TX', 'QUEUED', 2, ?, ?)
                        """,
                batchId, userId, now, now);
        insertFile(failingId, userId, batchId, "fail.png", "key-fail", now);
        insertFile(siblingId, userId, batchId, "ok.png", "key-ok", now);

        when(credentialService.decryptForUser(userId))
                .thenReturn(new PaddleCredential("api-key", "secret-key"));
        when(storage.open(eq(userId), any()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ocrProvider.recognize(any(), any()))
                .thenThrow(new PaddleServiceUnavailableException());

        assertThatThrownBy(() -> service.processFile(userId, failingId))
                .isInstanceOf(PaddleServiceUnavailableException.class);

        assertThat(fileStatus(failingId)).isEqualTo("FAILED");
        assertThat(fileStatus(siblingId)).isEqualTo("QUEUED");
    }

    private void insertFile(
            UUID id,
            UUID userId,
            UUID batchId,
            String name,
            String storageKey,
            OffsetDateTime now) {
        jdbc.update("""
                        insert into invoice_files(
                            id, user_id, batch_id, original_name, media_type, size, sha256,
                            storage_key, status, created_at, updated_at)
                        values (?, ?, ?, ?, 'image/png', 3, ?, ?, 'QUEUED', ?, ?)
                        """,
                id,
                userId,
                batchId,
                name,
                "ab",
                storageKey,
                now,
                now);
    }

    private String fileStatus(UUID id) {
        return jdbc.queryForObject(
                "select status from invoice_files where id = ?",
                String.class,
                id);
    }
}

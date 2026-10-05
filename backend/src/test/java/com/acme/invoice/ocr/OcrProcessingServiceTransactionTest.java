package com.acme.invoice.ocr;

import com.acme.invoice.credential.OcrCredentialService;
import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.review.InvoiceReviewService;
import com.acme.invoice.review.InvoiceView;
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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
    InvoiceReviewService reviewService;

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
        jdbc.update("delete from erp_push_receipts");
        jdbc.update("delete from audit_events");
        jdbc.update("delete from duplicate_matches");
        jdbc.update("delete from verification_issues");
        jdbc.update("delete from invoices");
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
        assertThat(batchStatus(batchId)).isEqualTo("PROCESSING");
    }

    @Test
    void successfulOcrRunsVerificationAndDeduplicationBeforeReview() {
        UUID userId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "ocr-pass@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                now);
        jdbc.update("""
                        insert into import_batches(
                            id, user_id, batch_number, status, total_files, created_at, updated_at)
                        values (?, ?, 'B-OCR-PASS', 'QUEUED', 1, ?, ?)
                        """,
                batchId, userId, now, now);
        insertFile(fileId, userId, batchId, "ok.png", "key-ok-pass", now);

        when(credentialService.decryptForUser(userId))
                .thenReturn(new PaddleCredential("api-key", "secret-key"));
        when(storage.open(eq(userId), any()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ocrProvider.recognize(any(), any())).thenReturn(new OcrDocument(
                "req-pass",
                "RAW_OCR_SECRET_JSON",
                List.of(
                        new OcrField("InvoiceType", "增值税专用发票", new BigDecimal("0.99")),
                        new OcrField("InvoiceCode", "033001900111", new BigDecimal("0.99")),
                        new OcrField("InvoiceNum", "00123456", new BigDecimal("0.99")),
                        new OcrField("InvoiceDate", "2024-01-15", new BigDecimal("0.99")),
                        new OcrField("TotalAmount", "100.00", new BigDecimal("0.99")),
                        new OcrField("TotalTax", "13.00", new BigDecimal("0.99")),
                        new OcrField("AmountInFiguers", "113.00", new BigDecimal("0.99")))));

        service.processFile(userId, fileId);

        assertThat(fileStatus(fileId)).isEqualTo("PASSED");
        String invoiceStatus = jdbc.queryForObject(
                "select status from invoices where user_id = ? and invoice_file_id = ?",
                String.class,
                userId,
                fileId);
        assertThat(invoiceStatus).isEqualTo("PASSED");
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
        String rawJson = jdbc.queryForObject(
                "select raw_json from ocr_results where invoice_file_id = ?",
                String.class,
                fileId);
        assertThat(rawJson).isEqualTo("RAW_OCR_SECRET_JSON");
        String fieldsJson = jdbc.queryForObject(
                "select fields_json from invoices where invoice_file_id = ?",
                String.class,
                fileId);
        assertThat(fieldsJson).doesNotContain("RAW_OCR_SECRET_JSON");
    }

    @Test
    void retryReplacesExistingOcrResultAndUpdatesSameInvoice() {
        UUID userId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "ocr-retry@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                now);
        jdbc.update("""
                        insert into import_batches(
                            id, user_id, batch_number, status, total_files, created_at, updated_at)
                        values (?, ?, 'B-OCR-RETRY', 'QUEUED', 1, ?, ?)
                        """,
                batchId, userId, now, now);
        insertFile(fileId, userId, batchId, "retry.png", "key-retry", now);

        when(credentialService.decryptForUser(userId))
                .thenReturn(new PaddleCredential("api-key", "secret-key"));
        when(storage.open(eq(userId), any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ocrProvider.recognize(any(), any()))
                .thenReturn(vatDocument("00123456", "RAW_OCR_FIRST"))
                .thenReturn(vatDocument("00999999", "RAW_OCR_RETRY"));

        service.processFile(userId, fileId);
        UUID invoiceId = jdbc.queryForObject(
                "select id from invoices where user_id = ? and invoice_file_id = ?",
                UUID.class,
                userId,
                fileId);

        InvoiceView retried = reviewService.retry(userId, invoiceId);

        assertThat(retried.id()).isEqualTo(invoiceId);
        assertThat(retried.invoiceNumber()).isEqualTo("00999999");
        assertThat(count("ocr_results", fileId)).isEqualTo(1L);
        assertThat(count("normalized_invoices", fileId)).isEqualTo(1L);
        assertThat(count("invoices", fileId)).isEqualTo(1L);
        assertThat(fileStatus(fileId)).isNotEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select raw_json from ocr_results where invoice_file_id = ?",
                String.class,
                fileId)).isEqualTo("RAW_OCR_RETRY");
        assertThat(jdbc.queryForObject(
                "select invoice_number from normalized_invoices where invoice_file_id = ?",
                String.class,
                fileId)).isEqualTo("00999999");
    }

    @Test
    void unrecognizedInvoiceTypeRequiresReviewInsteadOfFailingTheFile() {
        UUID userId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "ocr-unknown@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                now);
        jdbc.update("""
                        insert into import_batches(
                            id, user_id, batch_number, status, total_files, created_at, updated_at)
                        values (?, ?, 'B-OCR-UNKNOWN', 'QUEUED', 1, ?, ?)
                        """,
                batchId, userId, now, now);
        insertFile(fileId, userId, batchId, "unknown.png", "key-unknown", now);

        when(credentialService.decryptForUser(userId))
                .thenReturn(new PaddleCredential("api-key", "secret-key"));
        when(storage.open(eq(userId), any()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ocrProvider.recognize(any(), any())).thenReturn(new OcrDocument(
                "req-unknown",
                "{}",
                List.of(new OcrField("type", "toll_invoice", new BigDecimal("0.90")))));

        service.processFile(userId, fileId);

        assertThat(fileStatus(fileId)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject(
                "select invoice_type from invoices where invoice_file_id = ?",
                String.class,
                fileId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject(
                "select status from invoices where invoice_file_id = ?",
                String.class,
                fileId)).isEqualTo("REVIEW_REQUIRED");
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
    }

    @Test
    void retryFailureLeavesPreviousSuccessfulFileAndInvoiceIntact() {
        UUID userId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "ocr-retry-fail@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                now);
        jdbc.update("""
                        insert into import_batches(
                            id, user_id, batch_number, status, total_files, created_at, updated_at)
                        values (?, ?, 'B-OCR-RETRY-FAIL', 'QUEUED', 1, ?, ?)
                        """,
                batchId, userId, now, now);
        insertFile(fileId, userId, batchId, "retry-fail.png", "key-retry-fail", now);

        when(credentialService.decryptForUser(userId))
                .thenReturn(new PaddleCredential("api-key", "secret-key"));
        when(storage.open(eq(userId), any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(ocrProvider.recognize(any(), any()))
                .thenReturn(vatDocument("00123456", "RAW_OCR_FIRST"))
                .thenThrow(new PaddleServiceUnavailableException());

        service.processFile(userId, fileId);
        UUID invoiceId = jdbc.queryForObject(
                "select id from invoices where user_id = ? and invoice_file_id = ?",
                UUID.class,
                userId,
                fileId);

        assertThatThrownBy(() -> reviewService.retry(userId, invoiceId))
                .isInstanceOf(PaddleServiceUnavailableException.class);

        assertThat(fileStatus(fileId)).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject(
                "select invoice_number from invoices where id = ?",
                String.class,
                invoiceId)).isEqualTo("00123456");
        assertThat(count("invoices", fileId)).isEqualTo(1L);
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
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

    private static OcrDocument vatDocument(String invoiceNumber, String rawJson) {
        return new OcrDocument(
                "req-" + invoiceNumber,
                rawJson,
                List.of(
                        new OcrField("InvoiceType", "增值税专用发票", new BigDecimal("0.99")),
                        new OcrField("InvoiceCode", "033001900111", new BigDecimal("0.99")),
                        new OcrField("InvoiceNum", invoiceNumber, new BigDecimal("0.99")),
                        new OcrField("InvoiceDate", "2024-01-15", new BigDecimal("0.99")),
                        new OcrField("TotalAmount", "100.00", new BigDecimal("0.99")),
                        new OcrField("TotalTax", "13.00", new BigDecimal("0.99")),
                        new OcrField("AmountInFiguers", "113.00", new BigDecimal("0.99"))));
    }

    private Long count(String table, UUID fileId) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where invoice_file_id = ?",
                Long.class,
                fileId);
    }

    private String fileStatus(UUID id) {
        return jdbc.queryForObject(
                "select status from invoice_files where id = ?",
                String.class,
                id);
    }

    private String batchStatus(UUID id) {
        return jdbc.queryForObject(
                "select status from import_batches where id = ?",
                String.class,
                id);
    }
}

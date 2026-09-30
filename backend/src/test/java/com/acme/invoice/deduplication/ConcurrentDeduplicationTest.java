package com.acme.invoice.deduplication;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentDeduplicationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
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
        registry.add("app.verification.confidence-threshold", () -> "0.80");
        registry.add("app.verification.max-age-years", () -> "10");
    }

    @Autowired
    DeduplicationService deduplicationService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
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
    void concurrentInsertsKeepOneEligibleAndOneBlockedWithSingleActiveKey() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "concurrent@example.com",
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                OffsetDateTime.now(ZoneOffset.UTC));

        Invoice first = candidate(userId, "00123456");
        Invoice second = candidate(userId, "00123456");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DeduplicationResult> left = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return deduplicationService.evaluate(userId, first);
            });
            Future<DeduplicationResult> right = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return deduplicationService.evaluate(userId, second);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            DeduplicationResult leftResult = left.get(15, TimeUnit.SECONDS);
            DeduplicationResult rightResult = right.get(15, TimeUnit.SECONDS);

            List<String> statuses = jdbc.queryForList(
                    "select status from invoices where user_id = ?", String.class, userId);
            Long activeKeys = jdbc.queryForObject("""
                            select count(*) from invoices
                            where user_id = ?
                              and exact_duplicate_key is not null
                              and status in ('PASSED', 'REVIEW_REQUIRED')
                            """,
                    Long.class,
                    userId);

            assertThat(statuses).hasSize(2);
            assertThat(statuses).contains(InvoiceStatus.BLOCKED_DUPLICATE.name());
            assertThat(statuses).anyMatch(status ->
                    InvoiceStatus.PASSED.name().equals(status)
                            || InvoiceStatus.REVIEW_REQUIRED.name().equals(status));
            assertThat(activeKeys).isEqualTo(1L);
            assertThat(leftResult.exactDuplicate() ^ rightResult.exactDuplicate()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Invoice candidate(UUID userId, String number) {
        return Invoice.fromNormalized(
                UUID.randomUUID(),
                userId,
                new NormalizedInvoice(
                        InvoiceType.VAT_SPECIAL,
                        "033001900111",
                        number,
                        LocalDate.of(2024, 1, 15),
                        new BigDecimal("100.00"),
                        new BigDecimal("13.00"),
                        new BigDecimal("113.00"),
                        Map.of()),
                Instant.now());
    }
}

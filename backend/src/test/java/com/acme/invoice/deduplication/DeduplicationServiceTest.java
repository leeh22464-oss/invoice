package com.acme.invoice.deduplication;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeduplicationServiceTest {

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
    ExactDuplicateKeyFactory exactDuplicateKeyFactory;

    @Autowired
    SuspectedDuplicateScorer suspectedDuplicateScorer;

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
    void vatExactKeyUsesTypeCodeAndNumber() {
        NormalizedInvoice invoice = normalized(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                Map.of());

        Optional<String> key = exactDuplicateKeyFactory.create(invoice);

        assertThat(key).contains("VAT_SPECIAL|033001900111|00123456");
    }

    @Test
    void railwayExactKeyUsesElectronicTicketNumber() {
        NormalizedInvoice invoice = normalized(
                InvoiceType.RAILWAY,
                null,
                "00001234",
                LocalDate.of(2024, 4, 10),
                new BigDecimal("156.50"),
                Map.of("ticket_num", new NormalizedField("A001234", new BigDecimal("0.96"))));

        Optional<String> key = exactDuplicateKeyFactory.create(invoice);

        assertThat(key).contains("RAILWAY|A001234");
    }

    @Test
    void flightExactKeyUsesElectronicTicketNumber() {
        NormalizedInvoice invoice = normalized(
                InvoiceType.FLIGHT,
                null,
                "00005555",
                LocalDate.of(2024, 5, 12),
                new BigDecimal("1380.00"),
                Map.of("ticket_number", new NormalizedField("9991234567890", new BigDecimal("0.95"))));

        Optional<String> key = exactDuplicateKeyFactory.create(invoice);

        assertThat(key).contains("FLIGHT|9991234567890");
    }

    @Test
    void recordsWithoutOfficialUniqueNumberNeverBecomeDefiniteDuplicates() {
        NormalizedInvoice taxi = normalized(
                InvoiceType.TAXI,
                "111001234567",
                "00001234",
                LocalDate.of(2024, 6, 1),
                new BigDecimal("38.00"),
                Map.of());
        NormalizedInvoice railwayWithoutTicket = normalized(
                InvoiceType.RAILWAY,
                null,
                null,
                LocalDate.of(2024, 4, 10),
                new BigDecimal("156.50"),
                Map.of());

        assertThat(exactDuplicateKeyFactory.create(taxi)).isEmpty();
        assertThat(exactDuplicateKeyFactory.create(railwayWithoutTicket)).isEmpty();
    }

    @Test
    void evaluateDetectsExactVatDuplicateScopedByUser() {
        UUID userId = seedUser("dedup@example.com");
        UUID otherUserId = seedUser("other@example.com");
        Invoice existing = seedInvoice(userId, InvoiceType.VAT_SPECIAL, "033001900111", "00123456",
                "VAT_SPECIAL|033001900111|00123456", InvoiceStatus.PASSED);
        seedInvoice(otherUserId, InvoiceType.VAT_SPECIAL, "033001900111", "00123456",
                "VAT_SPECIAL|033001900111|00123456", InvoiceStatus.PASSED);

        Invoice candidate = newInvoice(userId, InvoiceType.VAT_SPECIAL, "033001900111", "00123456",
                LocalDate.of(2024, 1, 15), new BigDecimal("100.00"), new BigDecimal("13.00"),
                new BigDecimal("113.00"), Map.of());

        DeduplicationResult result = deduplicationService.evaluate(userId, candidate);

        assertThat(result.exactDuplicate()).isTrue();
        assertThat(result.matchedInvoiceId()).isEqualTo(existing.getId());
        assertThat(candidate.getStatus()).isEqualTo(InvoiceStatus.BLOCKED_DUPLICATE);
    }

    @Test
    void suspectedMatchReturnsExplainableMatchedFieldsWithoutBlocking() {
        NormalizedInvoice left = normalized(
                InvoiceType.VAT_ORDINARY,
                "044001800222",
                "00098765",
                LocalDate.of(2024, 2, 20),
                new BigDecimal("206.00"),
                Map.of("PurchaserName", new NormalizedField("Acme", new BigDecimal("0.9"))));
        NormalizedInvoice right = normalized(
                InvoiceType.VAT_ORDINARY,
                "044001800223",
                "00098766",
                LocalDate.of(2024, 2, 20),
                new BigDecimal("206.00"),
                Map.of("PurchaserName", new NormalizedField("Acme", new BigDecimal("0.9"))));

        DuplicateScore score = suspectedDuplicateScorer.score(left, right);

        assertThat(score.matchedFields()).contains("invoiceDate", "total", "PurchaserName");
        assertThat(score.score()).isGreaterThan(BigDecimal.ZERO);

        UUID userId = seedUser("suspect@example.com");
        seedInvoice(
                userId,
                InvoiceType.VAT_ORDINARY,
                "044001800222",
                "00098765",
                LocalDate.of(2024, 2, 20),
                new BigDecimal("200.00"),
                new BigDecimal("6.00"),
                new BigDecimal("206.00"),
                "VAT_ORDINARY|044001800222|00098765",
                InvoiceStatus.PASSED);
        Invoice candidate = newInvoice(userId, InvoiceType.VAT_ORDINARY, "044001800223", "00098766",
                LocalDate.of(2024, 2, 20), new BigDecimal("200.00"), new BigDecimal("6.00"),
                new BigDecimal("206.00"),
                Map.of("PurchaserName", new NormalizedField("Acme", new BigDecimal("0.9"))));

        DeduplicationResult result = deduplicationService.evaluate(userId, candidate);

        assertThat(result.exactDuplicate()).isFalse();
        assertThat(result.suspectedMatch()).isNotNull();
        assertThat(result.suspectedMatch().matchedFields())
                .contains("invoiceDate", "total");
        assertThat(candidate.getStatus()).isNotEqualTo(InvoiceStatus.BLOCKED_DUPLICATE);
    }

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                        insert into users(id, email, password_hash, status, created_at)
                        values (?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                email,
                "$2a$10$01234567890123456789012345678901234567890123456789012",
                OffsetDateTime.now(ZoneOffset.UTC));
        return userId;
    }

    private Invoice seedInvoice(
            UUID userId,
            InvoiceType type,
            String code,
            String number,
            String exactKey,
            InvoiceStatus status) {
        return seedInvoice(
                userId,
                type,
                code,
                number,
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                exactKey,
                status);
    }

    private Invoice seedInvoice(
            UUID userId,
            InvoiceType type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            String exactKey,
            InvoiceStatus status) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                        insert into invoices(
                            id, user_id, invoice_type, invoice_code, invoice_number, invoice_date,
                            amount, tax, total, status, exact_duplicate_key, fields_json, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?)
                        """,
                id,
                userId,
                type.name(),
                code,
                number,
                date,
                amount,
                tax,
                total,
                status.name(),
                exactKey,
                now,
                now);
        Instant instant = now.toInstant();
        return Invoice.rehydrate(
                id,
                userId,
                type,
                code,
                number,
                date,
                amount,
                tax,
                total,
                status,
                exactKey,
                Map.of(),
                instant,
                instant);
    }

    private static Invoice newInvoice(
            UUID userId,
            InvoiceType type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            Map<String, NormalizedField> fields) {
        return Invoice.fromNormalized(
                UUID.randomUUID(),
                userId,
                new NormalizedInvoice(type, code, number, date, amount, tax, total, fields),
                Instant.now());
    }

    private static NormalizedInvoice normalized(
            InvoiceType type,
            String code,
            String number,
            LocalDate date,
            BigDecimal total,
            Map<String, NormalizedField> fields) {
        return new NormalizedInvoice(type, code, number, date, null, null, total, fields);
    }
}

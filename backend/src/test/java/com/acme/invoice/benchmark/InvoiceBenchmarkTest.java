package com.acme.invoice.benchmark;

import com.acme.invoice.deduplication.DeduplicationResult;
import com.acme.invoice.deduplication.DeduplicationService;
import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.BaiduPaddleOcrProvider;
import com.acme.invoice.ocr.InvoiceNormalizer;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedInvoice;
import com.acme.invoice.verification.VerificationEngine;
import com.acme.invoice.verification.VerificationIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InvoiceBenchmarkTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Path FIXTURES = Path.of("..", "benchmark", "fixtures", "expected-invoices.json");
    private static final List<String> EXPECTED_FIELDS = List.of(
            "type", "invoiceCode", "invoiceNumber", "invoiceDate", "amount", "tax", "total");

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
    InvoiceNormalizer normalizer;

    @Autowired
    VerificationEngine verificationEngine;

    @Autowired
    DeduplicationService deduplicationService;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BaiduPaddleOcrProvider parser = new BaiduPaddleOcrProvider(
            null, null, "unused", file -> new byte[0], Duration.ZERO, ignored -> {
            });

    @BeforeEach
    void clean() {
        jdbc.update("delete from duplicate_matches");
        jdbc.update("delete from verification_issues");
        jdbc.update("delete from invoices");
        jdbc.update("delete from users");
    }

    @Test
    void syntheticFixturesMeetAcceptanceTargets() throws Exception {
        JsonNode cases = mapper.readTree(Files.readString(FIXTURES)).get("cases");
        UUID userId = seedUser("benchmark@example.com");

        Map<String, UUID> invoiceIds = new HashMap<>();
        Set<InvoiceType> coveredTypes = EnumSet.noneOf(InvoiceType.class);
        int expectedFields = 0;
        int correctFields = 0;
        int labeledAnomalies = 0;
        int detectedAnomalies = 0;
        int exactDuplicates = 0;
        int blockedDuplicates = 0;
        List<String> misses = new ArrayList<>();

        for (JsonNode testCase : cases) {
            String id = testCase.get("id").asText();
            NormalizedInvoice normalized = normalizer.normalize(
                    parser.parseResponse(mapper.writeValueAsString(testCase.get("ocr"))));
            coveredTypes.add(normalized.type());

            JsonNode expected = testCase.get("expected");
            for (String field : EXPECTED_FIELDS) {
                expectedFields++;
                if (matches(field, expected.get(field), normalized)) {
                    correctFields++;
                } else {
                    misses.add(id + "." + field);
                }
            }

            List<VerificationIssue> issues = verificationEngine.verify(normalized);
            Invoice invoice = Invoice.fromNormalized(UUID.randomUUID(), userId, normalized, Instant.now());
            DeduplicationResult result = deduplicationService.evaluate(userId, invoice);
            invoiceIds.put(id, invoice.getId());

            for (JsonNode label : testCase.path("anomalies")) {
                labeledAnomalies++;
                String code = label.asText();
                boolean detected = "SUSPECTED_DUPLICATE".equals(code)
                        ? !result.exactDuplicate()
                                && result.suspectedMatch() != null
                                && statusOf(invoice.getId()) == InvoiceStatus.REVIEW_REQUIRED
                        : issues.stream().anyMatch(issue -> code.equals(issue.code()));
                if (detected) {
                    detectedAnomalies++;
                } else {
                    misses.add(id + " anomaly " + code);
                }
            }

            if (testCase.hasNonNull("duplicateOf")) {
                exactDuplicates++;
                UUID original = invoiceIds.get(testCase.get("duplicateOf").asText());
                if (result.exactDuplicate()
                        && Objects.equals(result.matchedInvoiceId(), original)
                        && statusOf(invoice.getId()) == InvoiceStatus.BLOCKED_DUPLICATE) {
                    blockedDuplicates++;
                } else {
                    misses.add(id + " duplicate");
                }
            }
        }

        BigDecimal fieldAccuracy = ratio(correctFields, expectedFields);
        BigDecimal anomalyRecall = ratio(detectedAnomalies, labeledAnomalies);
        BigDecimal duplicateInterception = ratio(blockedDuplicates, exactDuplicates);

        assertThat(coveredTypes).as("covered invoice types").hasSize(8);
        assertThat(labeledAnomalies).isGreaterThanOrEqualTo(8);
        assertThat(exactDuplicates).isGreaterThanOrEqualTo(5);
        assertThat(fieldAccuracy).as("field accuracy, misses %s", misses)
                .isGreaterThanOrEqualTo(new BigDecimal("0.98"));
        assertThat(anomalyRecall).as("anomaly recall, misses %s", misses)
                .isGreaterThanOrEqualTo(new BigDecimal("0.95"));
        assertThat(duplicateInterception).as("duplicate interception, misses %s", misses)
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    private static boolean matches(String field, JsonNode expected, NormalizedInvoice actual) {
        String want = expected == null || expected.isNull() ? null : expected.asText();
        return switch (field) {
            case "type" -> Objects.equals(want, actual.type().name());
            case "invoiceCode" -> Objects.equals(want, actual.invoiceCode());
            case "invoiceNumber" -> Objects.equals(want, actual.invoiceNumber());
            case "invoiceDate" -> Objects.equals(
                    want == null ? null : LocalDate.parse(want), actual.invoiceDate());
            case "amount" -> sameMoney(want, actual.amount());
            case "tax" -> sameMoney(want, actual.tax());
            case "total" -> sameMoney(want, actual.total());
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static boolean sameMoney(String want, BigDecimal actual) {
        if (want == null || actual == null) {
            return want == null && actual == null;
        }
        return new BigDecimal(want).compareTo(actual) == 0;
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL64);
    }

    private InvoiceStatus statusOf(UUID invoiceId) {
        return InvoiceStatus.valueOf(jdbc.queryForObject(
                "select status from invoices where id = ?", String.class, invoiceId));
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
}

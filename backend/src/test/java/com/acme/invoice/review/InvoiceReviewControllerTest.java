package com.acme.invoice.review;

import com.acme.invoice.deduplication.DeduplicationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvoiceReviewControllerTest {

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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @MockitoSpyBean
    DeduplicationService deduplicationService;

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
    void manualCorrectionAuditsOldAndNewValuesAndRerunsRules() throws Exception {
        Cookie token = register("review-owner@example.com");
        UUID userId = userId("review-owner@example.com");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "BAD",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "REVIEW_REQUIRED",
                null);

        mvc.perform(patch("/api/invoices/{id}", invoiceId)
                        .with(csrf())
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"invoiceNumber\":\"00123456\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("00123456"))
                .andExpect(jsonPath("$.status").value("PASSED"));

        List<Map<String, Object>> audits = jdbc.queryForList(
                """
                select field_name, old_value, new_value from audit_events
                where user_id = ? and invoice_id = ?
                order by created_at
                """,
                userId,
                invoiceId);
        assertThat(audits).isNotEmpty();
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("field_name")).isEqualTo("invoiceNumber");
            assertThat(row.get("old_value")).isEqualTo("BAD");
            assertThat(row.get("new_value")).isEqualTo("00123456");
        });
    }

    @Test
    void evaluationFailureAfterCorrectionDemotesStalePassedStatus() throws Exception {
        Cookie token = register("review-compensate@example.com");
        UUID userId = userId("review-compensate@example.com");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00000001",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "PASSED",
                "VAT_SPECIAL|033001900111|00000001");
        doThrow(new IllegalStateException("verification failed"))
                .when(deduplicationService)
                .evaluate(any(), any());
        try {
            assertThatThrownBy(() -> mvc.perform(patch("/api/invoices/{id}", invoiceId)
                            .with(csrf())
                            .cookie(token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"invoiceNumber\":\"00123456\"}}")))
                    .isInstanceOf(ServletException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("verification failed");
        } finally {
            reset(deduplicationService);
        }

        assertThat(jdbc.queryForObject(
                "select invoice_number from invoices where id = ?",
                String.class,
                invoiceId)).isEqualTo("00123456");
        assertThat(jdbc.queryForObject(
                "select status from invoices where id = ?",
                String.class,
                invoiceId)).isEqualTo("REVIEW_REQUIRED");
        Long audits = jdbc.queryForObject(
                "select count(*) from audit_events where user_id = ? and invoice_id = ? and field_name = ?",
                Long.class,
                userId,
                invoiceId,
                "invoiceNumber");
        assertThat(audits).isEqualTo(1L);
    }

    @Test
    void evaluationFailureAfterCorrectionKeepsBlockedDuplicate() throws Exception {
        Cookie token = register("review-blocked@example.com");
        UUID userId = userId("review-blocked@example.com");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00000001",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "BLOCKED_DUPLICATE",
                "VAT_SPECIAL|033001900111|00000001");
        doThrow(new IllegalStateException("verification failed"))
                .when(deduplicationService)
                .evaluate(any(), any());
        try {
            assertThatThrownBy(() -> mvc.perform(patch("/api/invoices/{id}", invoiceId)
                            .with(csrf())
                            .cookie(token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"invoiceNumber\":\"00123456\"}}")))
                    .isInstanceOf(ServletException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("verification failed");
        } finally {
            reset(deduplicationService);
        }

        assertThat(jdbc.queryForObject(
                "select invoice_number from invoices where id = ?",
                String.class,
                invoiceId)).isEqualTo("00123456");
        assertThat(jdbc.queryForObject(
                "select status from invoices where id = ?",
                String.class,
                invoiceId)).isEqualTo("BLOCKED_DUPLICATE");
    }

    @Test
    void definiteDuplicateCannotBeForcedToPassed() throws Exception {
        Cookie token = register("dup-owner@example.com");
        UUID userId = userId("dup-owner@example.com");
        seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "PASSED",
                "VAT_SPECIAL|033001900111|00123456");
        UUID blockedId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "BLOCKED_DUPLICATE",
                "VAT_SPECIAL|033001900111|00123456");

        mvc.perform(patch("/api/invoices/{id}", blockedId)
                        .with(csrf())
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CANNOT_PASS"));

        String status = jdbc.queryForObject(
                "select status from invoices where id = ?", String.class, blockedId);
        assertThat(status).isEqualTo("BLOCKED_DUPLICATE");
    }

    @Test
    void passingReviewRequiredInvoiceAuditsStatusAndOpensLedgerExportAndErp() throws Exception {
        Cookie token = register("pass-owner@example.com");
        UUID userId = userId("pass-owner@example.com");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "REVIEW_REQUIRED",
                null);

        mvc.perform(patch("/api/invoices/{id}", invoiceId)
                        .with(csrf())
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));

        assertThat(jdbc.queryForObject(
                "select status from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject(
                """
                select count(*) from audit_events
                where user_id = ? and invoice_id = ? and field_name = ? and old_value = ? and new_value = ?
                """,
                Long.class,
                userId,
                invoiceId,
                "status",
                "REVIEW_REQUIRED",
                "PASSED")).isEqualTo(1L);

        mvc.perform(get("/api/ledger").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invoiceId.toString()))
                .andExpect(jsonPath("$[0].status").value("PASSED"));
        mvc.perform(get("/api/ledger/export.csv").cookie(token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(invoiceId.toString())));
        mvc.perform(get("/api/erp/invoices").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invoiceId.toString()))
                .andExpect(jsonPath("$[0].status").value("PASSED"));
    }

    @Test
    void providerFieldCorrectionUpdatesColumnsConfidenceKeyLedgerAndErp() throws Exception {
        Cookie token = register("alias-owner@example.com");
        UUID userId = userId("alias-owner@example.com");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00000001",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "REVIEW_REQUIRED",
                "VAT_SPECIAL|033001900111|00000001",
                "{\"InvoiceNum\":{\"sourceText\":\"00000001\",\"confidence\":0.10},"
                        + "\"InvoiceCode\":{\"sourceText\":\"033001900111\",\"confidence\":0.99},"
                        + "\"InvoiceDate\":{\"sourceText\":\"2024-01-15\",\"confidence\":0.99},"
                        + "\"TotalAmount\":{\"sourceText\":\"100.00\",\"confidence\":0.99},"
                        + "\"TotalTax\":{\"sourceText\":\"13.00\",\"confidence\":0.99},"
                        + "\"AmountInFiguers\":{\"sourceText\":\"100.00\",\"confidence\":0.10}}");

        mvc.perform(patch("/api/invoices/{id}", invoiceId)
                        .with(csrf())
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fields":{"InvoiceNum":"00998877","AmountInFiguers":"113.00"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("00998877"))
                .andExpect(jsonPath("$.total").value(113.00))
                .andExpect(jsonPath("$.fields.InvoiceNum").value("00998877"))
                .andExpect(jsonPath("$.fieldConfidence.InvoiceNum").value(1))
                .andExpect(jsonPath("$.fieldConfidence.AmountInFiguers").value(1))
                .andExpect(jsonPath("$.status").value("PASSED"));

        assertThat(jdbc.queryForObject(
                "select invoice_number from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("00998877");
        assertThat(jdbc.queryForObject(
                "select total from invoices where id = ?", BigDecimal.class, invoiceId))
                .isEqualByComparingTo("113.00");
        assertThat(jdbc.queryForObject(
                "select exact_duplicate_key from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("VAT_SPECIAL|033001900111|00998877");

        mvc.perform(get("/api/ledger").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invoiceNumber").value("00998877"))
                .andExpect(jsonPath("$[0].total").value(113.00));
        mvc.perform(get("/api/erp/invoices").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invoiceNumber").value("00998877"));
    }

    @Test
    void listAndDetailExposeConfidenceIssuesAndDuplicateEvidence() throws Exception {
        Cookie token = register("evidence-owner@example.com");
        UUID userId = userId("evidence-owner@example.com");
        UUID matchedId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900111",
                "00111111",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "PASSED",
                "VAT_SPECIAL|033001900111|00111111");
        UUID invoiceId = seedInvoice(
                userId,
                "VAT_SPECIAL",
                "033001900222",
                "00222222",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                "REVIEW_REQUIRED",
                null,
                "{\"InvoiceNum\":{\"sourceText\":\"00222222\",\"confidence\":0.20}}");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into verification_issues(
                    id, user_id, invoice_id, code, field_name, message, severity, created_at)
                values (?, ?, ?, 'LOW_CONFIDENCE', 'InvoiceNum', 'field confidence is below threshold', 'REVIEW', ?)
                """,
                UUID.randomUUID(),
                userId,
                invoiceId,
                now);
        jdbc.update(
                """
                insert into duplicate_matches(
                    id, user_id, invoice_id, matched_invoice_id, match_type, score, matched_fields_json, created_at)
                values (?, ?, ?, ?, 'SUSPECTED', 0.7500, '["invoiceDate","total"]', ?)
                """,
                UUID.randomUUID(),
                userId,
                invoiceId,
                matchedId,
                now);

        mvc.perform(get("/api/invoices").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].fieldConfidence.InvoiceNum".formatted(invoiceId))
                        .value(org.hamcrest.Matchers.hasItem(0.2)))
                .andExpect(jsonPath("$[?(@.id == '%s')].issues[0].code".formatted(invoiceId))
                        .value(org.hamcrest.Matchers.hasItem("LOW_CONFIDENCE")))
                .andExpect(jsonPath("$[?(@.id == '%s')].duplicateEvidence.matchType".formatted(invoiceId))
                        .value(org.hamcrest.Matchers.hasItem("SUSPECTED")))
                .andExpect(jsonPath("$[?(@.id == '%s')].duplicateEvidence.matchedInvoiceId".formatted(invoiceId))
                        .value(org.hamcrest.Matchers.hasItem(matchedId.toString())));

        mvc.perform(get("/api/invoices/{id}", invoiceId).cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldConfidence.InvoiceNum").value(0.2))
                .andExpect(jsonPath("$.issues[0].code").value("LOW_CONFIDENCE"))
                .andExpect(jsonPath("$.issues[0].field").value("InvoiceNum"))
                .andExpect(jsonPath("$.duplicateEvidence.matchType").value("SUSPECTED"))
                .andExpect(jsonPath("$.duplicateEvidence.matchedInvoiceId").value(matchedId.toString()))
                .andExpect(jsonPath("$.duplicateEvidence.matchedFields[0]").value("invoiceDate"));
    }

    @Test
    void crossUserInvoiceIdsReturn404() throws Exception {
        Cookie owner = register("owner-review@example.com");
        Cookie other = register("other-review@example.com");
        UUID ownerId = userId("owner-review@example.com");
        UUID invoiceId = seedInvoice(
                ownerId,
                "VAT_SPECIAL",
                "033001900222",
                "00111111",
                LocalDate.of(2024, 2, 1),
                new BigDecimal("50.00"),
                new BigDecimal("6.50"),
                new BigDecimal("56.50"),
                "REVIEW_REQUIRED",
                null);

        mvc.perform(get("/api/invoices/{id}", invoiceId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/invoices/{id}", invoiceId)
                        .with(csrf())
                        .cookie(other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"invoiceNumber\":\"00122222\"}}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/invoices/{id}", invoiceId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoiceId.toString()));
    }

    private Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}"))
                .andReturn()
                .getResponse()
                .getCookie("access_token");
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private UUID seedInvoice(
            UUID userId,
            String type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            String status,
            String exactKey) {
        return seedInvoice(
                userId, type, code, number, date, amount, tax, total, status, exactKey, "{}");
    }

    private UUID seedInvoice(
            UUID userId,
            String type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            String status,
            String exactKey,
            String fieldsJson) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into invoices(
                    id, user_id, invoice_type, invoice_code, invoice_number, invoice_date,
                    amount, tax, total, status, exact_duplicate_key, fields_json, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
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
                fieldsJson,
                now,
                now);
        return id;
    }
}

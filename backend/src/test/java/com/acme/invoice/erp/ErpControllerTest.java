package com.acme.invoice.erp;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ErpControllerTest {

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
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

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
    void erpQueryExposesOnlyPassedInvoices() throws Exception {
        Cookie token = register("erp@example.com");
        UUID userId = userId("erp@example.com");
        UUID passedId = seedInvoice(userId, "PASSED", "033001900111", "00123456");
        seedInvoice(userId, "REVIEW_REQUIRED", "033001900112", "00123457");
        seedInvoice(userId, "BLOCKED_DUPLICATE", "033001900113", "00123458");

        mvc.perform(get("/api/erp/invoices").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(passedId.toString()))
                .andExpect(jsonPath("$[0].status").value("PASSED"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalResponseWithoutSecondSideEffect() throws Exception {
        Cookie token = register("erp-push@example.com");
        UUID userId = userId("erp-push@example.com");
        UUID invoiceId = seedInvoice(userId, "PASSED", "033001900211", "00223456");

        MvcResult first = mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(token)
                        .header("Idempotency-Key", "push-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PUSHED"))
                .andReturn();

        MvcResult second = mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(token)
                        .header("Idempotency-Key", "push-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PUSHED"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        Integer receiptCount = jdbc.queryForObject(
                "select count(*) from erp_push_receipts where user_id = ? and idempotency_key = ?",
                Integer.class,
                userId,
                "push-key-1");
        assertThat(receiptCount).isEqualTo(1);
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

    private UUID seedInvoice(UUID userId, String status, String code, String number) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into invoices(
                    id, user_id, invoice_type, invoice_code, invoice_number, invoice_date,
                    amount, tax, total, status, exact_duplicate_key, fields_json, created_at, updated_at)
                values (?, ?, 'VAT_SPECIAL', ?, ?, ?, 100.00, 13.00, 113.00, ?, null, '{}', ?, ?)
                """,
                id,
                userId,
                code,
                number,
                LocalDate.of(2024, 4, 1),
                status,
                now,
                now);
        return id;
    }
}

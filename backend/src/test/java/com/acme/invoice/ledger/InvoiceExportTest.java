package com.acme.invoice.ledger;

import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvoiceExportTest {

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
    void ledgerExportIncludesOnlyPassedInvoicesAndSanitizesFormulaCells() throws Exception {
        Cookie token = register("ledger@example.com");
        UUID userId = userId("ledger@example.com");

        UUID passedId = seedInvoice(
                userId,
                "PASSED",
                "033001900111",
                "00123456",
                "Acme Co",
                null);
        seedInvoice(userId, "REVIEW_REQUIRED", "033001900112", "00123457", "Review Co", null);
        seedInvoice(userId, "BLOCKED_DUPLICATE", "033001900113", "00123458", "Blocked Co", null);
        seedInvoice(userId, "FAILED", "033001900114", "00123459", "Failed Co", null);
        seedInvoice(userId, "QUEUED", "033001900115", "00123460", "Queued Co", null);
        seedInvoice(userId, "OCR_PROCESSING", "033001900116", "00123461", "Ocr Co", null);
        UUID formulaId = seedInvoice(
                userId,
                "PASSED",
                "@033001900117",
                "-00123462",
                "=1+2",
                "+cmd");
        jdbc.update("update invoices set total = ? where id = ?", new BigDecimal("-113.00"), formulaId);

        MvcResult csvResult = mvc.perform(get("/api/ledger/export.csv").cookie(token))
                .andExpect(status().isOk())
                .andReturn();
        String csv = csvResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains(passedId.toString());
        assertThat(csv).contains(formulaId.toString());
        assertThat(csv).doesNotContain("Review Co");
        assertThat(csv).doesNotContain("Blocked Co");
        assertThat(csv).doesNotContain("Failed Co");
        assertThat(csv).doesNotContain("Queued Co");
        assertThat(csv).doesNotContain("Ocr Co");
        assertThat(csv)
                .contains("'=1+2")
                .contains("'+cmd")
                .contains("'@033001900117")
                .contains("'-00123462")
                .contains("'-113.00");

        MvcResult xlsxResult = mvc.perform(get("/api/ledger/export.xlsx").cookie(token))
                .andExpect(status().isOk())
                .andReturn();
        byte[] xlsxBytes = xlsxResult.getResponse().getContentAsByteArray();
        assertThat(xlsxBytes).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<String> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (Cell cell : row) {
                    if (line.length() > 0) {
                        line.append(',');
                    }
                    line.append(formatter.formatCellValue(cell));
                }
                rows.add(line.toString());
            }
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().anyMatch(r -> r.contains(passedId.toString()))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains(formulaId.toString()))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains("'=1+2"))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains("'+cmd"))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains("'@033001900117"))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains("'-00123462"))).isTrue();
            assertThat(rows.stream().anyMatch(r -> r.contains("'-113.00"))).isTrue();
            assertThat(rows.stream().noneMatch(r -> r.contains("Review Co"))).isTrue();
        }

        mvc.perform(get("/api/ledger").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].status", everyItem(is("PASSED"))));
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
            String status,
            String code,
            String number,
            String purchaser,
            String seller) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String fieldsJson = "{\"PurchaserName\":{\"sourceText\":\"" + purchaser
                + "\",\"confidence\":0.95}";
        if (seller != null) {
            fieldsJson += ",\"SellerName\":{\"sourceText\":\"" + seller + "\",\"confidence\":0.95}";
        }
        fieldsJson += "}";
        jdbc.update(
                """
                insert into invoices(
                    id, user_id, invoice_type, invoice_code, invoice_number, invoice_date,
                    amount, tax, total, status, exact_duplicate_key, fields_json, created_at, updated_at)
                values (?, ?, 'VAT_SPECIAL', ?, ?, ?, 100.00, 13.00, 113.00, ?, null, ?, ?, ?)
                """,
                id,
                userId,
                code,
                number,
                LocalDate.of(2024, 3, 1),
                status,
                fieldsJson,
                now,
                now);
        return id;
    }
}

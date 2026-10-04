package com.acme.invoice.security;

import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CrossUserAccessTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Path STORAGE_ROOT;
    private static final String SECRET_OCR = "RAW_OCR_SECRET_JSON";
    private static final String SECRET_MASK = "SECRET-API-MASK";
    private static final String INVOICE_NUMBER = "00998877";

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("invoice-cross-user-");
        } catch (IOException exception) {
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
        registry.add("app.storage.root", STORAGE_ROOT::toString);
        registry.add("app.paddle.token-url", () -> "http://127.0.0.1:9/oauth/2.0/token");
        registry.add("app.paddle.recognize-url", () -> "http://127.0.0.1:9/ocr");
        registry.add("app.paddle.timeout-ms", () -> "200");
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
        jdbc.update("delete from ocr_credentials");
        jdbc.update("delete from invoice_files");
        jdbc.update("delete from import_batches");
        jdbc.update("delete from users");
    }

    @Test
    void secondUserGets404ForOwnerIdsAndPublicIdsAreUuids() throws Exception {
        Cookie owner = register("owner-access@example.com");
        Cookie other = register("other-access@example.com");
        UUID ownerId = userId("owner-access@example.com");

        String created = mvc.perform(multipart("/api/import-batches")
                        .file(png("invoice.png"))
                        .with(csrf())
                        .cookie(owner))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID batchId = UUID.fromString(created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        UUID fileId = jdbc.queryForObject(
                "select id from invoice_files where batch_id = ?", UUID.class, batchId);
        UUID invoiceId = seedInvoice(ownerId, fileId);

        jdbc.update(
                """
                insert into ocr_results(
                    id, user_id, invoice_file_id, provider_request_id, raw_json, created_at)
                values (?, ?, ?, 'req-secret', ?, now())
                """,
                UUID.randomUUID(), ownerId, fileId, SECRET_OCR);
        jdbc.update(
                """
                insert into ocr_credentials(
                    id, user_id, api_key_iv, api_key_ciphertext, secret_key_iv,
                    secret_key_ciphertext, api_key_mask, validation_status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'VALID', now(), now())
                """,
                UUID.randomUUID(),
                ownerId,
                new byte[]{1},
                new byte[]{2},
                new byte[]{3},
                new byte[]{4},
                SECRET_MASK);

        List<Map<String, Object>> idColumns = jdbc.queryForList(
                """
                select data_type from information_schema.columns
                where table_schema = 'public' and column_name = 'id'
                """);
        assertThat(idColumns).isNotEmpty();
        assertThat(idColumns).allSatisfy(row -> assertThat(row.get("data_type")).isEqualTo("uuid"));
        assertThat(created).containsPattern(
                "\"id\"\\s*:\\s*\"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"");
        assertThat(created).doesNotContainPattern("\"id\"\\s*:\\s*\\d");

        String invoiceBody = mvc.perform(get("/api/invoices/{id}", invoiceId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoiceId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(invoiceBody).doesNotContain(SECRET_OCR).doesNotContain(SECRET_MASK);
        assertThat(invoiceBody).doesNotContainPattern("\"id\"\\s*:\\s*\\d");

        mvc.perform(get("/api/invoices/{id}/file", invoiceId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(content().string(not(containsString(SECRET_OCR))))
                .andExpect(content().string(not(containsString(SECRET_MASK))));

        MvcResult async = mvc.perform(get("/api/import-batches/{id}/events", batchId).cookie(owner))
                .andExpect(request().asyncStarted())
                .andReturn();
        String events = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(events).doesNotContain(SECRET_OCR).doesNotContain(SECRET_MASK);
        assertThat(events).doesNotContain("api_key").doesNotContain("raw_json");

        mvc.perform(get("/api/ledger").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INVOICE_NUMBER)));
        mvc.perform(get("/api/ledger/export.csv").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INVOICE_NUMBER)));
        byte[] xlsx = mvc.perform(get("/api/ledger/export.xlsx").cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(xlsxText(xlsx)).contains(INVOICE_NUMBER).contains(invoiceId.toString());
        mvc.perform(get("/api/erp/invoices").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invoiceId.toString()));

        mvc.perform(get("/api/invoices/{id}", invoiceId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/invoices/{id}/file", invoiceId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-batches/{id}", batchId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-batches/{id}/events", batchId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-batches/{batchId}/files/{fileId}/content", batchId, fileId)
                        .cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/erp/invoices/push")
                        .with(csrf())
                        .cookie(other)
                        .header("Idempotency-Key", "cross-user-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/ledger").cookie(other))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(INVOICE_NUMBER))))
                .andExpect(content().string(not(containsString(invoiceId.toString()))));
        mvc.perform(get("/api/ledger/export.csv").cookie(other))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(INVOICE_NUMBER))));
        byte[] otherXlsx = mvc.perform(get("/api/ledger/export.xlsx").cookie(other))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(xlsxText(otherXlsx))
                .doesNotContain(INVOICE_NUMBER)
                .doesNotContain(invoiceId.toString());
        mvc.perform(get("/api/erp/invoices").cookie(other))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(invoiceId.toString()))));
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

    private UUID seedInvoice(UUID userId, UUID fileId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into invoices(
                    id, user_id, invoice_file_id, invoice_type, invoice_code, invoice_number,
                    invoice_date, amount, tax, total, status, exact_duplicate_key, fields_json,
                    created_at, updated_at)
                values (?, ?, ?, 'VAT_SPECIAL', '033001900111', ?, ?, 100.00, 13.00, 113.00,
                        'PASSED', null, '{}', ?, ?)
                """,
                id,
                userId,
                fileId,
                INVOICE_NUMBER,
                LocalDate.of(2024, 1, 15),
                now,
                now);
        return id;
    }

    private static String xlsxText(byte[] body) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder text = new StringBuilder();
            for (Row row : workbook.getSheetAt(0)) {
                for (Cell cell : row) {
                    text.append(formatter.formatCellValue(cell)).append('\n');
                }
            }
            return text.toString();
        }
    }

    private static MockMultipartFile png(String name) {
        byte[] bytes = new byte[64];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return new MockMultipartFile("files", name, "image/png", bytes);
    }
}

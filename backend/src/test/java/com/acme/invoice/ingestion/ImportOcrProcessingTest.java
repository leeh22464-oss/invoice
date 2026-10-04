package com.acme.invoice.ingestion;

import com.acme.invoice.credential.CredentialValidation;
import com.acme.invoice.ocr.OcrDocument;
import com.acme.invoice.ocr.OcrField;
import com.acme.invoice.ocr.OcrProvider;
import com.acme.invoice.ocr.PaddleTokenClient;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ImportOcrProcessingTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("invoice-import-ocr-");
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
    @MockitoBean OcrProvider ocrProvider;
    @MockitoBean PaddleTokenClient tokenClient;

    @BeforeEach
    void clean() throws Exception {
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
        when(tokenClient.validate(any(), any()))
                .thenReturn(new CredentialValidation(true, "Credentials are valid"));
        when(ocrProvider.recognize(any(), any()))
                .thenReturn(vat("00123456"), vat("00223457"));
        try (var paths = Files.walk(STORAGE_ROOT)) {
            paths.filter(path -> !path.equals(STORAGE_ROOT))
                    .sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }

    @Test
    void acceptedPngAndJpegBatchProducesInvoices() throws Exception {
        Cookie token = register("import-ocr@example.com");
        mvc.perform(put("/api/settings/ocr-credentials")
                        .with(csrf())
                        .cookie(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"abcdefgh1234\",\"secretKey\":\"local-secret\"}"))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/import-batches")
                        .file(jpeg("special.jpg"))
                        .file(png("special.png"))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isCreated());

        Integer invoices = jdbc.queryForObject("select count(*) from invoices", Integer.class);
        List<String> numbers = jdbc.queryForList(
                "select invoice_number from invoices order by invoice_number", String.class);
        assertThat(invoices).as("accepted PNG/JPEG batch should create invoices").isEqualTo(2);
        assertThat(numbers).containsExactly("00123456", "00223457");
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

    private static OcrDocument vat(String number) {
        BigDecimal confidence = new BigDecimal("0.99");
        return new OcrDocument("req-" + number, "{\"synthetic\":true}", List.of(
                new OcrField("type", "vat_invoice", confidence),
                new OcrField("InvoiceType", "专用发票", confidence),
                new OcrField("InvoiceCode", "033001900111", confidence),
                new OcrField("InvoiceNum", number, confidence),
                new OcrField("InvoiceDate", "2024年01月15日", confidence),
                new OcrField("TotalAmount", "100.00", confidence),
                new OcrField("TotalTax", "13.00", confidence),
                new OcrField("AmountInFiguers", "113.00", confidence)));
    }

    private static MockMultipartFile jpeg(String name) {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        bytes[2] = (byte) 0xff;
        return new MockMultipartFile("files", name, "image/jpeg", bytes);
    }

    private static MockMultipartFile png(String name) {
        byte[] bytes = new byte[64];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return new MockMultipartFile("files", name, "image/png", bytes);
    }
}

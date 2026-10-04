package com.acme.invoice.security;

import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.LocalFileStorage;
import com.acme.invoice.ingestion.PdfBoxPageChecker;
import com.acme.invoice.ingestion.StoredFile;
import com.acme.invoice.ingestion.UploadPolicy;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(UploadSecurityTest.StorageTestConfiguration.class)
class UploadSecurityTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("invoice-upload-security-");
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
    @Autowired CountingStorage storage;

    @BeforeEach
    void clean() throws Exception {
        storage.reset();
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
    void rejectsPlainTextAndMismatchedMediaTypeBeforeStorage() throws Exception {
        Cookie token = register("upload-type@example.com");

        mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile(
                                "files", "notes.txt", "text/plain", "hello".getBytes()))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile(
                                "files", "photo.png", "image/jpeg", pngBytes(64)))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isBadRequest());

        assertThat(storage.calls()).isZero();
        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void rejectsOversizeFileBeforeStorage() throws Exception {
        Cookie token = register("upload-size@example.com");
        byte[] oversized = pngBytes((int) UploadPolicy.MAX_FILE_BYTES + 1);

        mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile("files", "large.png", "image/png", oversized))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isBadRequest());

        assertThat(storage.calls()).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void rejectsPdfWithMoreThanTwentyPages() throws Exception {
        Cookie token = register("upload-pdf@example.com");

        mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile(
                                "files", "long.pdf", "application/pdf", pdfPages(21)))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isBadRequest());

        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void pathTraversalFilenameStaysInsideStorageRootAndPreviewIsInline() throws Exception {
        Cookie token = register("upload-path@example.com");
        String body = mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile(
                                "files", "../../secret.png", "image/png", pngBytes(64)))
                        .with(csrf())
                        .cookie(token))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID batchId = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        UUID fileId = jdbc.queryForObject(
                "select id from invoice_files where batch_id = ?", UUID.class, batchId);
        UUID userId = jdbc.queryForObject(
                "select user_id from invoice_files where id = ?", UUID.class, fileId);
        String storageKey = jdbc.queryForObject(
                "select storage_key from invoice_files where id = ?", String.class, fileId);

        Path stored = STORAGE_ROOT.resolve(userId.toString()).resolve(storageKey).normalize();
        assertThat(storageKey).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(stored.startsWith(STORAGE_ROOT)).isTrue();
        assertThat(Files.isRegularFile(stored)).isTrue();
        assertThat(Files.exists(STORAGE_ROOT.getParent().resolve("secret.png"))).isFalse();
        for (Path file : regularFiles()) {
            assertThat(file.normalize().startsWith(STORAGE_ROOT)).isTrue();
        }

        mvc.perform(get("/api/import-batches/{batchId}/files/{fileId}/content", batchId, fileId)
                        .cookie(token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                containsString("inline"),
                                not(containsString("../")))))
                .andExpect(content().contentType("image/png"));
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

    private int rowCount(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private java.util.List<Path> regularFiles() throws IOException {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    private static byte[] pdfPages(int pages) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @TestConfiguration
    static class StorageTestConfiguration {
        @Bean
        @Primary
        CountingStorage testFileStorage(UploadPolicy policy) {
            return new CountingStorage(new LocalFileStorage(
                    STORAGE_ROOT.toString(),
                    policy,
                    new PdfBoxPageChecker(policy)));
        }
    }

    static final class CountingStorage implements FileStorage {
        private final FileStorage delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingStorage(FileStorage delegate) {
            this.delegate = delegate;
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public StoredFile store(
                UUID userId, UUID fileId, String originalName, InputStream content) {
            calls.incrementAndGet();
            return delegate.store(userId, fileId, originalName, content);
        }

        @Override
        public InputStream open(UUID userId, String storageKey) {
            return delegate.open(userId, storageKey);
        }

        @Override
        public void delete(UUID userId, String storageKey) {
            delegate.delete(userId, storageKey);
        }
    }
}

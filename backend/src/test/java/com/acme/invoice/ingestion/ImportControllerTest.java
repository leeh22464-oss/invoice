package com.acme.invoice.ingestion;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ImportControllerTest.StorageTestConfiguration.class)
class ImportControllerTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Path STORAGE_ROOT;

    static {
        POSTGRES.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("invoice-upload-test-");
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
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FailingStorage storage;

    @BeforeEach
    void clean() throws Exception {
        storage.reset();
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
    void acceptsExactlyOneHundredFilesAndRejectsOneHundredOneWithoutRows() throws Exception {
        Cookie token = register("limits@example.com");
        MockMultipartHttpServletRequestBuilder accepted = multipart("/api/import-batches");
        accepted.with(csrf()).cookie(token);
        for (int i = 0; i < 100; i++) {
            accepted.file(png("file-" + i + ".png"));
        }
        mvc.perform(accepted)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalFiles").value(100))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(STORAGE_ROOT.toString()))));

        jdbc.update("delete from invoice_files");
        jdbc.update("delete from import_batches");
        MockMultipartHttpServletRequestBuilder rejected = multipart("/api/import-batches");
        rejected.with(csrf()).cookie(token);
        for (int i = 0; i < 101; i++) {
            rejected.file(png("file-" + i + ".png"));
        }
        mvc.perform(rejected).andExpect(status().isBadRequest());
        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
    }

    @Test
    void rejectsOneByteOverLimitBeforeCallingStorage() throws Exception {
        Cookie token = register("size@example.com");
        byte[] oversized = pngBytes((int) UploadPolicy.MAX_FILE_BYTES + 1);

        mvc.perform(multipart("/api/import-batches").file(
                        new MockMultipartFile("files", "large.png", "image/png", oversized))
                        .with(csrf()).cookie(token))
                .andExpect(status().isBadRequest());

        assertThat(storage.calls()).isZero();
        assertThat(rowCount("import_batches")).isZero();
    }

    @Test
    void isolatesBatchAndPreviewAndUsesSafePreviewHeaders() throws Exception {
        Cookie owner = register("owner-upload@example.com");
        Cookie other = register("other-upload@example.com");
        String body = mvc.perform(multipart("/api/import-batches")
                        .file(new MockMultipartFile(
                                "files", "../../secret.png", "image/png", pngBytes(64)))
                        .with(csrf()).cookie(owner))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        UUID fileId = jdbc.queryForObject(
                "select id from invoice_files where batch_id = ?", UUID.class, batchId);

        mvc.perform(get("/api/import-batches/{id}", batchId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(batchId.toString()));
        mvc.perform(get("/api/import-batches/{id}", batchId).cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-batches/{batchId}/files/{fileId}/content", batchId, fileId)
                        .cookie(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-batches/{batchId}/files/{fileId}/content", batchId, fileId)
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("inline"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("../")))))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(pngBytes(64)));
    }

    @Test
    void deletesPriorFilesAndRollsBackRowsWhenLaterStorageFails() throws Exception {
        Cookie token = register("rollback@example.com");
        storage.failOn(2);

        mvc.perform(multipart("/api/import-batches")
                        .file(png("first.png"))
                        .file(png("second.png"))
                        .with(csrf()).cookie(token))
                .andExpect(status().isInternalServerError());

        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        try (var paths = Files.walk(STORAGE_ROOT)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void retriesTransientDeleteFailureThenSucceedsLeavingNoFiles() throws Exception {
        Cookie token = register("cleanup-retry@example.com");
        storage.failOn(2);
        storage.failNextDeletes(1);

        mvc.perform(multipart("/api/import-batches")
                        .file(png("first.png"))
                        .file(png("second.png"))
                        .with(csrf()).cookie(token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_FAILED"));

        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        assertThat(storage.deleteAttempts()).isGreaterThanOrEqualTo(2);
        try (var paths = Files.walk(STORAGE_ROOT)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void surfacesPersistentDeleteFailureInsteadOfSwallowingIt() throws Exception {
        Cookie token = register("cleanup-fail@example.com");
        storage.failOn(2);
        storage.failDeletesAlways();

        mvc.perform(multipart("/api/import-batches")
                        .file(png("first.png"))
                        .file(png("second.png"))
                        .with(csrf()).cookie(token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_CLEANUP_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("removed")));

        assertThat(rowCount("import_batches")).isZero();
        assertThat(rowCount("invoice_files")).isZero();
        assertThat(storage.deleteAttempts()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        Cookie token = register("security-upload@example.com");
        mvc.perform(multipart("/api/import-batches").file(png("a.png")).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/import-batches").file(png("a.png")).cookie(token))
                .andExpect(status().isForbidden());
    }

    private Cookie register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"StrongPass1!\"}"))
                .andReturn().getResponse().getCookie("access_token");
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("files", name, "image/png", pngBytes(64));
    }

    private byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    @TestConfiguration
    static class StorageTestConfiguration {
        @Bean
        @Primary
        FailingStorage testFileStorage(UploadPolicy policy) {
            return new FailingStorage(
                    new LocalFileStorage(
                            STORAGE_ROOT.toString(),
                            policy,
                            new PdfBoxPageChecker(policy)));
        }
    }

    static final class FailingStorage implements FileStorage {
        private final FileStorage delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger deleteAttempts = new AtomicInteger();
        private volatile int failOn = Integer.MAX_VALUE;
        private volatile int remainingDeleteFailures;
        private volatile boolean failDeletesAlways;

        FailingStorage(FileStorage delegate) {
            this.delegate = delegate;
        }

        void failOn(int call) {
            failOn = call;
        }

        void failNextDeletes(int count) {
            remainingDeleteFailures = count;
            failDeletesAlways = false;
        }

        void failDeletesAlways() {
            failDeletesAlways = true;
            remainingDeleteFailures = Integer.MAX_VALUE;
        }

        int calls() {
            return calls.get();
        }

        int deleteAttempts() {
            return deleteAttempts.get();
        }

        void reset() {
            calls.set(0);
            deleteAttempts.set(0);
            failOn = Integer.MAX_VALUE;
            remainingDeleteFailures = 0;
            failDeletesAlways = false;
        }

        @Override
        public StoredFile store(
                UUID userId, UUID fileId, String originalName, InputStream content) {
            if (calls.incrementAndGet() == failOn) {
                throw new UploadException(
                        "STORAGE_FAILED", "Storage failed", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return delegate.store(userId, fileId, originalName, content);
        }

        @Override
        public InputStream open(UUID userId, String storageKey) {
            return delegate.open(userId, storageKey);
        }

        @Override
        public void delete(UUID userId, String storageKey) {
            deleteAttempts.incrementAndGet();
            if (failDeletesAlways || remainingDeleteFailures > 0) {
                if (!failDeletesAlways) {
                    remainingDeleteFailures--;
                }
                throw new UploadException(
                        "STORAGE_CLEANUP_FAILED",
                        "Stored file could not be removed",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            delegate.delete(userId, storageKey);
        }
    }
}

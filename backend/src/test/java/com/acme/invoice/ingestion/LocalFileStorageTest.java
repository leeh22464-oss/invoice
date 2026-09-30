package com.acme.invoice.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @TempDir
    Path root;

    @Test
    void storesUnderGeneratedUserAndFileIdsWithStreamingDigest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] png = pngBytes(32_000);
        LocalFileStorage storage = storage();

        StoredFile stored = storage.store(
                userId, fileId, "../../secret.png", new ByteArrayInputStream(png));

        assertThat(stored.storageKey()).isEqualTo(fileId.toString());
        assertThat(stored.size()).isEqualTo(png.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(png)));
        assertThat(stored.mediaType()).isEqualTo("image/png");
        assertThat(Files.readAllBytes(root.resolve(userId.toString()).resolve(fileId.toString())))
                .isEqualTo(png);
        assertThat(Files.exists(root.resolve("secret.png"))).isFalse();
        assertThat(storage.open(userId, stored.storageKey()).readAllBytes()).isEqualTo(png);
    }

    @Test
    void rejectsOversizedStreamAndLeavesNoFile() {
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        LocalFileStorage storage = storage();
        byte[] oversized = pngBytes((int) UploadPolicy.MAX_FILE_BYTES + 1);

        assertThatThrownBy(() -> storage.store(
                userId, fileId, "large.png", new ByteArrayInputStream(oversized)))
                .isInstanceOf(UploadException.class);
        assertThat(root.resolve(userId.toString()).resolve(fileId.toString())).doesNotExist();
        assertThat(root.resolve(userId.toString()).resolve(fileId + ".part")).doesNotExist();
    }

    @Test
    void rejectsMissingRootAndStorageKeysThatCanTraverse() {
        assertThatThrownBy(() -> new LocalFileStorage(
                " ", new UploadPolicy(), new PdfBoxPageChecker(new UploadPolicy())))
                .isInstanceOf(IllegalStateException.class);

        LocalFileStorage storage = storage();
        assertThatThrownBy(() -> storage.open(UUID.randomUUID(), "../../secret"))
                .isInstanceOf(UploadException.class);
    }

    @Test
    void rejectsWhenPdfCheckTimesOutAndRemovesPartFile() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        PdfPageChecker hanging = path -> {
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        try (TimedPdfPageChecker checker = new TimedPdfPageChecker(
                hanging, Duration.ofMillis(50))) {
            LocalFileStorage storage = new LocalFileStorage(
                    root.toString(), new UploadPolicy(), checker);
            byte[] pdf = pdf(1);

            assertThatThrownBy(() -> storage.store(
                    userId, fileId, "slow.pdf", new ByteArrayInputStream(pdf)))
                    .isInstanceOf(UploadException.class)
                    .extracting(exception -> ((UploadException) exception).getCode())
                    .isEqualTo("PDF_VALIDATION_TIMEOUT");
            assertThat(root.resolve(userId.toString()).resolve(fileId.toString())).doesNotExist();
            assertThat(root.resolve(userId.toString()).resolve(fileId + ".part")).doesNotExist();
        }
    }

    @Test
    void surfacesTemporaryPartDeleteFailure() {
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        AtomicInteger deletes = new AtomicInteger();
        LocalFileStorage storage = new LocalFileStorage(
                root.toString(),
                new UploadPolicy(),
                new PdfBoxPageChecker(new UploadPolicy()),
                path -> {
                    deletes.incrementAndGet();
                    throw new IOException("part locked");
                });
        byte[] oversized = pngBytes((int) UploadPolicy.MAX_FILE_BYTES + 1);

        assertThatThrownBy(() -> storage.store(
                userId, fileId, "large.png", new ByteArrayInputStream(oversized)))
                .isInstanceOf(UploadException.class)
                .satisfies(exception -> {
                    UploadException upload = (UploadException) exception;
                    assertThat(upload.getCode()).isEqualTo("STORAGE_CLEANUP_FAILED");
                    assertThat(upload.getMessage()).contains("Temporary");
                });
        assertThat(deletes.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void storesValidPdfUsingBoundedChecker() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] pdf = pdf(2);
        LocalFileStorage storage = storage();

        StoredFile stored = storage.store(
                userId, fileId, "ok.pdf", new ByteArrayInputStream(pdf));

        assertThat(stored.mediaType()).isEqualTo("application/pdf");
        assertThat(stored.size()).isEqualTo(pdf.length);
    }

    private LocalFileStorage storage() {
        return new LocalFileStorage(
                root.toString(),
                new UploadPolicy(),
                new PdfBoxPageChecker(new UploadPolicy()));
    }

    private byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    private byte[] pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}

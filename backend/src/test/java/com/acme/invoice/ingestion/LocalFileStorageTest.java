package com.acme.invoice.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

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
        LocalFileStorage storage = new LocalFileStorage(root.toString(), new UploadPolicy());

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
        LocalFileStorage storage = new LocalFileStorage(root.toString(), new UploadPolicy());
        byte[] oversized = pngBytes((int) UploadPolicy.MAX_FILE_BYTES + 1);

        assertThatThrownBy(() -> storage.store(
                userId, fileId, "large.png", new ByteArrayInputStream(oversized)))
                .isInstanceOf(UploadException.class);
        assertThat(root.resolve(userId.toString()).resolve(fileId.toString())).doesNotExist();
    }

    @Test
    void rejectsMissingRootAndStorageKeysThatCanTraverse() {
        assertThatThrownBy(() -> new LocalFileStorage(" ", new UploadPolicy()))
                .isInstanceOf(IllegalStateException.class);

        LocalFileStorage storage = new LocalFileStorage(root.toString(), new UploadPolicy());
        assertThatThrownBy(() -> storage.open(UUID.randomUUID(), "../../secret"))
                .isInstanceOf(UploadException.class);
    }

    private byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }
}

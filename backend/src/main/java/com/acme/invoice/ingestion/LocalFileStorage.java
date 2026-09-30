package com.acme.invoice.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public class LocalFileStorage implements FileStorage {

    private static final int BUFFER_SIZE = 8192;

    private final Path root;
    private final UploadPolicy policy;

    public LocalFileStorage(String configuredRoot, UploadPolicy policy) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException("APP_STORAGE_ROOT must be configured");
        }
        this.root = Path.of(configuredRoot).toAbsolutePath().normalize();
        this.policy = policy;
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("APP_STORAGE_ROOT is not a directory");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("APP_STORAGE_ROOT cannot be initialized", exception);
        }
    }

    @Override
    public StoredFile store(
            UUID userId, UUID fileId, String originalName, InputStream content) {
        Path userDirectory = safeResolve(root, userId.toString());
        Path target = safeResolve(userDirectory, fileId.toString());
        Path temporary = safeResolve(userDirectory, fileId + ".part");
        String expectedType = policy.expectedMediaType(originalName);
        try {
            Files.createDirectories(userDirectory);
            ensureDirectoryInsideRoot(userDirectory);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new UploadException(
                        "STORAGE_COLLISION",
                        "Generated storage identifier already exists",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] prefix = new byte[8];
            int prefixLength = 0;
            long size = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = new BufferedInputStream(content);
                 OutputStream output = new BufferedOutputStream(
                         Files.newOutputStream(temporary))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > UploadPolicy.MAX_FILE_BYTES) {
                        throw new UploadException(
                                "FILE_TOO_LARGE", "Each file must be at most 20 MiB");
                    }
                    int copy = Math.min(read, prefix.length - prefixLength);
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                        prefixLength += copy;
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String actualType = policy.detectMediaType(
                    originalName, expectedType,
                    java.util.Arrays.copyOf(prefix, prefixLength));
            if ("application/pdf".equals(actualType)) {
                try {
                    try (PDDocument document = Loader.loadPDF(temporary.toFile())) {
                        policy.validatePdfPages(document);
                    }
                } catch (IOException exception) {
                    throw new UploadException(
                            "INVALID_PDF", "PDF content is invalid");
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            return new StoredFile(
                    fileId.toString(),
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    actualType);
        } catch (UploadException exception) {
            cleanup(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            cleanup(temporary);
            throw new UploadException(
                    "STORAGE_FAILED",
                    "File could not be stored",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    exception);
        }
    }

    @Override
    public InputStream open(UUID userId, String storageKey) {
        Path path = storedPath(userId, storageKey);
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(root.toRealPath())
                    || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new UploadException(
                        "FILE_NOT_FOUND", "File not found", HttpStatus.NOT_FOUND);
            }
            return Files.newInputStream(realPath);
        } catch (IOException exception) {
            throw new UploadException(
                    "FILE_NOT_FOUND", "File not found", HttpStatus.NOT_FOUND);
        }
    }

    @Override
    public void delete(UUID userId, String storageKey) {
        Path path = storedPath(userId, storageKey);
        try {
            Files.deleteIfExists(path);
            Path userDirectory = path.getParent();
            if (Files.isDirectory(userDirectory)) {
                try (var entries = Files.list(userDirectory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(userDirectory);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UploadException(
                    "STORAGE_CLEANUP_FAILED",
                    "Stored file could not be removed",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    exception);
        }
    }

    private Path storedPath(UUID userId, String storageKey) {
        UUID fileId;
        try {
            fileId = UUID.fromString(storageKey);
        } catch (RuntimeException exception) {
            throw new UploadException("INVALID_STORAGE_KEY", "Invalid storage key");
        }
        if (!fileId.toString().equals(storageKey)) {
            throw new UploadException("INVALID_STORAGE_KEY", "Invalid storage key");
        }
        Path userDirectory = safeResolve(root, userId.toString());
        if (Files.exists(userDirectory, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectoryInsideRoot(userDirectory);
        }
        return safeResolve(userDirectory, storageKey);
    }

    private Path safeResolve(Path parent, String child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(child).normalize();
        if (!resolved.startsWith(normalizedParent)) {
            throw new UploadException("INVALID_STORAGE_KEY", "Invalid storage path");
        }
        return resolved;
    }

    private void ensureDirectoryInsideRoot(Path directory) {
        try {
            Path realRoot = root.toRealPath();
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(realRoot) || !Files.isDirectory(realDirectory)) {
                throw new UploadException(
                        "INVALID_STORAGE_PATH", "Storage path is invalid");
            }
        } catch (IOException exception) {
            throw new UploadException(
                    "STORAGE_FAILED",
                    "Storage path could not be verified",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    exception);
        }
    }

    private void cleanup(Path... paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Preserve the original failure; request-level cleanup retries stored files.
            }
        }
    }
}

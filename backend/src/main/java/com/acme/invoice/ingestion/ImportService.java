package com.acme.invoice.ingestion;

import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!smoke")
public class ImportService {

    static final int DELETE_ATTEMPTS = 3;

    private final UploadPolicy policy;
    private final FileStorage storage;
    private final ImportBatchRepository batchRepository;
    private final InvoiceFileRepository fileRepository;

    public ImportService(
            UploadPolicy policy,
            FileStorage storage,
            ImportBatchRepository batchRepository,
            InvoiceFileRepository fileRepository) {
        this.policy = policy;
        this.storage = storage;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional
    public ImportBatchView create(UUID userId, List<MultipartFile> files) {
        policy.validateBatchSize(files == null ? 0 : files.size());
        for (MultipartFile file : files) {
            policy.validateMetadata(
                    file.getOriginalFilename(), file.getContentType(), file.getSize());
        }

        List<StoredReference> written = new ArrayList<>();
        registerRollbackCleanup(userId, written);
        try {
            Instant now = Instant.now();
            UUID batchId = UUID.randomUUID();
            ImportBatch batch = new ImportBatch(
                    batchId,
                    userId,
                    "IMP-" + batchId,
                    "QUEUED",
                    files.size(),
                    now);
            batchRepository.save(batch);

            for (MultipartFile file : files) {
                UUID fileId = UUID.randomUUID();
                StoredFile stored;
                try {
                    stored = storage.store(
                            userId,
                            fileId,
                            file.getOriginalFilename(),
                            file.getInputStream());
                } catch (IOException exception) {
                    throw new UploadException(
                            "UPLOAD_READ_FAILED",
                            "Uploaded file could not be read",
                            HttpStatus.BAD_REQUEST,
                            exception);
                }
                written.add(new StoredReference(stored.storageKey()));
                fileRepository.save(new InvoiceFile(
                        fileId,
                        userId,
                        batchId,
                        file.getOriginalFilename(),
                        stored,
                        now));
            }
            fileRepository.flush();
            batchRepository.flush();
            return ImportBatchView.from(batch);
        } catch (RuntimeException exception) {
            cleanup(userId, written);
            if (!written.isEmpty()) {
                UploadException cleanupFailure = new UploadException(
                        "STORAGE_CLEANUP_FAILED",
                        "Stored files could not be removed after upload failure",
                        HttpStatus.INTERNAL_SERVER_ERROR);
                cleanupFailure.addSuppressed(exception);
                throw cleanupFailure;
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ImportBatchView get(UUID userId, UUID batchId) {
        return batchRepository.findByIdAndUserId(batchId, userId)
                .map(ImportBatchView::from)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public PreviewFile open(UUID userId, UUID batchId, UUID fileId) {
        InvoiceFile file = fileRepository.findByIdAndBatchIdAndUserId(
                        fileId, batchId, userId)
                .orElseThrow(this::notFound);
        return new PreviewFile(
                storage.open(userId, file.getStorageKey()),
                file.getMediaType(),
                file.getSize(),
                sanitizeDisplayName(file.getOriginalName()));
    }

    private void registerRollbackCleanup(
            UUID userId, List<StoredReference> written) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            cleanup(userId, written);
                            if (!written.isEmpty()) {
                                throw new UploadException(
                                        "STORAGE_CLEANUP_FAILED",
                                        "Stored files could not be removed after upload failure",
                                        HttpStatus.INTERNAL_SERVER_ERROR);
                            }
                        }
                    }
                });
    }

    private void cleanup(UUID userId, List<StoredReference> written) {
        Iterator<StoredReference> iterator = written.iterator();
        while (iterator.hasNext()) {
            StoredReference reference = iterator.next();
            if (deleteWithRetry(userId, reference.storageKey())) {
                iterator.remove();
            }
        }
    }

    private boolean deleteWithRetry(UUID userId, String storageKey) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < DELETE_ATTEMPTS; attempt++) {
            try {
                storage.delete(userId, storageKey);
                return true;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        return lastFailure == null;
    }

    private UploadException notFound() {
        return new UploadException(
                "IMPORT_NOT_FOUND", "Import batch or file not found", HttpStatus.NOT_FOUND);
    }

    private String sanitizeDisplayName(String originalName) {
        String sanitized = originalName
                .replace('\\', '/')
                .substring(originalName.replace('\\', '/').lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\"]", "_");
        return sanitized.isBlank() ? "invoice" : sanitized;
    }

    private record StoredReference(String storageKey) {
    }
}

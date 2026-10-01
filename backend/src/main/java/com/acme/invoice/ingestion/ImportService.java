package com.acme.invoice.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("!smoke")
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    static final int DELETE_ATTEMPTS = 3;

    private final UploadPolicy policy;
    private final FileStorage storage;
    private final ImportBatchRepository batchRepository;
    private final InvoiceFileRepository fileRepository;
    private final PendingCleanupRegistry pendingCleanup;

    public ImportService(
            UploadPolicy policy,
            FileStorage storage,
            ImportBatchRepository batchRepository,
            InvoiceFileRepository fileRepository,
            PendingCleanupRegistry pendingCleanup) {
        this.policy = policy;
        this.storage = storage;
        this.batchRepository = batchRepository;
        this.fileRepository = fileRepository;
        this.pendingCleanup = pendingCleanup;
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
            try {
                cleanup(userId, written);
            } catch (UploadException cleanupFailure) {
                cleanupFailure.addSuppressed(exception);
                throw cleanupFailure;
            }
            throw exception;
        }
    }

    public int retryPendingCleanups() {
        int removed = 0;
        for (PendingCleanupRegistry.Entry entry : pendingCleanup.snapshot()) {
            try {
                storage.delete(entry.userId(), entry.storageKey());
                pendingCleanup.remove(entry.userId(), entry.storageKey());
                removed++;
            } catch (RuntimeException exception) {
                log.error(
                        "Retry cleanup failed userId={} storageKey={}",
                        entry.userId(),
                        entry.storageKey(),
                        exception);
            }
        }
        return removed;
    }

    @Transactional(readOnly = true)
    public ImportBatchView get(UUID userId, UUID batchId) {
        return batchRepository.findByIdAndUserId(batchId, userId)
                .map(ImportBatchView::from)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public BatchProgressEvent progress(UUID userId, UUID batchId) {
        ImportBatch batch = batchRepository.findByIdAndUserId(batchId, userId)
                .orElseThrow(this::notFound);
        Map<String, Long> statusCounts = fileRepository.findByBatchIdAndUserId(batchId, userId)
                .stream()
                .collect(Collectors.groupingBy(InvoiceFile::getStatus, Collectors.counting()));
        return new BatchProgressEvent(batch.getTotalFiles(), batch.getStatus(), statusCounts);
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
                            try {
                                cleanup(userId, written);
                            } catch (UploadException cleanupFailure) {
                                log.error(
                                        "Rollback cleanup left pending entries userId={}",
                                        userId,
                                        cleanupFailure);
                            }
                        }
                    }
                });
    }

    private void cleanup(UUID userId, List<StoredReference> written) {
        UploadException aggregate = null;
        Iterator<StoredReference> iterator = written.iterator();
        while (iterator.hasNext()) {
            StoredReference reference = iterator.next();
            try {
                deleteWithRetry(userId, reference.storageKey());
                iterator.remove();
            } catch (UploadException failure) {
                if (aggregate == null) {
                    aggregate = failure;
                } else {
                    aggregate.addSuppressed(failure);
                }
            }
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    private void deleteWithRetry(UUID userId, String storageKey) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < DELETE_ATTEMPTS; attempt++) {
            try {
                storage.delete(userId, storageKey);
                pendingCleanup.remove(userId, storageKey);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        pendingCleanup.register(userId, storageKey);
        UploadException failure = new UploadException(
                "STORAGE_CLEANUP_FAILED",
                "Stored files could not be removed after upload failure: userId="
                        + userId
                        + ", storageKey="
                        + storageKey,
                HttpStatus.INTERNAL_SERVER_ERROR,
                lastFailure);
        log.error(
                "Persistent storage cleanup failure userId={} storageKey={}",
                userId,
                storageKey,
                lastFailure);
        throw failure;
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

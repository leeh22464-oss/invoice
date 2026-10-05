package com.acme.invoice.ingestion;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!smoke")
public class BatchStatusSync {

    private final InvoiceFileRepository files;
    private final ImportBatchRepository batches;
    private final Clock clock;

    public BatchStatusSync(
            InvoiceFileRepository files,
            ImportBatchRepository batches,
            Clock clock) {
        this.files = files;
        this.batches = batches;
        this.clock = clock;
    }

    public void refresh(UUID userId, UUID batchId) {
        List<InvoiceFile> rows = files.findByBatchIdAndUserId(batchId, userId);
        if (rows.isEmpty()) {
            return;
        }
        String next = derive(rows);
        batches.findByIdAndUserId(batchId, userId).ifPresent(batch -> {
            if (!next.equals(batch.getStatus())) {
                batch.markStatus(next, clock.instant());
                batches.save(batch);
            }
        });
    }

    static String derive(Collection<InvoiceFile> files) {
        if (files == null || files.isEmpty()) {
            return "QUEUED";
        }
        boolean anyOpen = false;
        boolean allQueued = true;
        boolean allFailed = true;
        for (InvoiceFile file : files) {
            String status = file.getStatus();
            if ("QUEUED".equals(status) || "OCR_PROCESSING".equals(status)) {
                anyOpen = true;
            }
            if (!"QUEUED".equals(status)) {
                allQueued = false;
            }
            if (!"FAILED".equals(status)) {
                allFailed = false;
            }
        }
        if (anyOpen) {
            return allQueued ? "QUEUED" : "PROCESSING";
        }
        return allFailed ? "FAILED" : "COMPLETED";
    }
}

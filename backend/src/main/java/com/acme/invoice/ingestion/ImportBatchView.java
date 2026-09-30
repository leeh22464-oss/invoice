package com.acme.invoice.ingestion;

import java.util.UUID;

public record ImportBatchView(UUID id, int totalFiles, String status) {

    static ImportBatchView from(ImportBatch batch) {
        return new ImportBatchView(
                batch.getId(), batch.getTotalFiles(), batch.getStatus());
    }
}

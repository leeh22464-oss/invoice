package com.acme.invoice.ingestion;

import java.util.Map;
import java.util.UUID;

public record ImportBatchView(
        UUID id,
        int totalFiles,
        String status,
        Map<String, Long> statusCounts) {

    static ImportBatchView from(ImportBatch batch) {
        return from(batch, Map.of());
    }

    static ImportBatchView from(ImportBatch batch, Map<String, Long> statusCounts) {
        return new ImportBatchView(
                batch.getId(),
                batch.getTotalFiles(),
                batch.getStatus(),
                statusCounts == null ? Map.of() : Map.copyOf(statusCounts));
    }
}

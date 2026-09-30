package com.acme.invoice.ingestion;

public record StoredFile(
        String storageKey,
        long size,
        String sha256,
        String mediaType
) {
}

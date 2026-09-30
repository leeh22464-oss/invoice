package com.acme.invoice.ingestion;

import java.io.InputStream;
import java.util.UUID;

public interface FileStorage {

    StoredFile store(UUID userId, UUID fileId, String originalName, InputStream content);

    InputStream open(UUID userId, String storageKey);

    void delete(UUID userId, String storageKey);
}

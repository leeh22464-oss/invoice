package com.acme.invoice.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.util.UUID;

@Configuration
@Profile("!smoke")
class FileStorageConfiguration {

    @Bean
    FileStorage fileStorage(
            @Value("${app.storage.root:}") String configuredRoot,
            UploadPolicy policy) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return new UnavailableFileStorage();
        }
        return new LocalFileStorage(configuredRoot, policy);
    }

    private static final class UnavailableFileStorage implements FileStorage {
        @Override
        public StoredFile store(
                UUID userId, UUID fileId, String originalName, InputStream content) {
            throw unavailable();
        }

        @Override
        public InputStream open(UUID userId, String storageKey) {
            throw unavailable();
        }

        @Override
        public void delete(UUID userId, String storageKey) {
            throw unavailable();
        }

        private UploadException unavailable() {
            return new UploadException(
                    "STORAGE_NOT_CONFIGURED",
                    "File storage is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}

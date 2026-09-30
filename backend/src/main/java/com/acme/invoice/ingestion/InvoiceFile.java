package com.acme.invoice.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice_files")
public class InvoiceFile {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "media_type", nullable = false, length = 64)
    private String mediaType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 64)
    private String storageKey;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvoiceFile() {
    }

    public InvoiceFile(
            UUID id,
            UUID userId,
            UUID batchId,
            String originalName,
            StoredFile stored,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.batchId = batchId;
        this.originalName = originalName;
        this.mediaType = stored.mediaType();
        this.size = stored.size();
        this.sha256 = stored.sha256();
        this.storageKey = stored.storageKey();
        this.status = "QUEUED";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void markStatus(String status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public StoredFile toStoredFile() {
        return new StoredFile(storageKey, size, sha256, mediaType);
    }
}

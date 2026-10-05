package com.acme.invoice.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_batches")
public class ImportBatch {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "total_files", nullable = false)
    private int totalFiles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportBatch() {
    }

    public ImportBatch(
            UUID id,
            UUID userId,
            String batchNumber,
            String status,
            int totalFiles,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.batchNumber = batchNumber;
        this.status = status;
        this.totalFiles = totalFiles;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public void markStatus(String status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }
}
